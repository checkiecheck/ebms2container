package nl.logius.ebms.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.xml.soap.*;
import nl.logius.ebms.common.exception.DuplicateMessageException;
import nl.logius.ebms.common.exception.XmlSecurityException;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsAckEvent;
import nl.logius.ebms.common.model.amqp.EbmsInboundMessage;
import nl.logius.ebms.common.model.cpa.CpaDto;
import nl.logius.ebms.common.model.ebxml.*;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageDirection;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.service.CpaValidationResult;
import nl.logius.ebms.orchestrator.service.CpaValidationService;
import nl.logius.ebms.orchestrator.service.CryptoServiceClient;
import nl.logius.ebms.orchestrator.service.OrchestratorService;
import nl.logius.ebms.orchestrator.soap.SoapHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integratietest voor de complete inbound ebMS2-pipeline.
 *
 * <p>Test-matrix:
 * <ul>
 *   <li>osb-be    – plaintext → geen crypto, persistentie, AMQP-publish</li>
 *   <li>osb-be-s  – handtekening → verify aangeroepen, geen decrypt</li>
 *   <li>osb-be-e  – versleuteld → decrypt aangeroepen, geen verify op buitenste envelop</li>
 *   <li>osb-rm-e  – versleuteld + ondertekend (buitenste handtekening) → decrypt → verify, rm-ACK</li>
 *   <li>Foutpad   – ongeldige handtekening → XmlSecurityException, geen DB-opslag</li>
 *   <li>Foutpad   – dubbele messageId → DuplicateMessageException</li>
 *   <li>ACK       – SENT → DELIVERED + EbmsAckEvent op ebms.ack.events queue</li>
 * </ul>
 *
 * <p>Externe HTTP-afhankelijkheden ({@link CpaValidationService} en {@link CryptoServiceClient})
 * worden via {@code @MockitoBean} gesimuleerd. PostgreSQL en RabbitMQ draaien in
 * echte Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class InboundPipelineIntegrationTest {

    // ── Testcontainers ────────────────────────────────────────────────────────

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbitmq =
        new RabbitMQContainer("rabbitmq:3.13-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.rabbitmq.host",         rabbitmq::getHost);
        registry.add("spring.rabbitmq.port",         rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username",     rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password",     rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");

        // Externe services zijn onbereikbaar in testomgeving – worden gemockt
        registry.add("ebms.cpa-service-url",    () -> "http://localhost:19999");
        registry.add("ebms.crypto-service-url", () -> "http://localhost:19998");
    }

    // ── Spring beans ──────────────────────────────────────────────────────────

    @MockitoBean
    CpaValidationService cpaValidationService;

    @MockitoBean
    CryptoServiceClient cryptoServiceClient;

    @Autowired
    OrchestratorService orchestratorService;

    @Autowired
    EbmsMessageRepository messageRepository;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    // ── Testconstanten ────────────────────────────────────────────────────────

    private static final String TEST_CPA_ID   = "urn:test:cpa:pipeline";
    private static final String FROM_OIN      = "00000000000000000001";
    private static final String TO_OIN        = "00000000000000000002";
    private static final String CLIENT_OIN    = FROM_OIN;

    // ── Testopzet ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        drainQueue(RabbitMqConfig.QUEUE_INBOUND);
        drainQueue(RabbitMqConfig.QUEUE_AUDIT);
        drainQueue(RabbitMqConfig.QUEUE_ACK);

        // Standaard: CPA-validatie slaagt
        when(cpaValidationService.validateCpaAndOin(any(), any()))
            .thenReturn(CpaValidationResult.success(
                CpaDto.builder().cpaId(TEST_CPA_ID).status("ACTIVE").cpaXml("<cpa/>").build()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 1 – Versleuteld bericht (osb-be-e): decrypt → persist → AMQP
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Versleuteld bericht: decrypt aangeroepen, ontsleutelde SOAP opgeslagen en gepubliceerd")
    void processInboundMessage_encryptedBody_decryptCalledAndDecryptedSoapPersisted() throws Exception {
        String encryptedSoap = buildSoapXml("msg-enc-001", false, true, false);
        String decryptedSoap = buildSoapXml("msg-enc-001", false, false, false);

        when(cryptoServiceClient.decrypt(eq(encryptedSoap), anyString(), eq("msg-enc-001")))
            .thenReturn(decryptedSoap);

        SOAPMessage request = parseSoap(encryptedSoap);
        EbxmlMessageHeader header = buildHeader("msg-enc-001", "conv-enc-001", null);

        orchestratorService.processInboundMessage(request, header, encryptedSoap, CLIENT_OIN);

        // Crypto: decrypt JA, verify NEE (geen handtekening op buitenste envelop)
        verify(cryptoServiceClient).decrypt(eq(encryptedSoap), anyString(), eq("msg-enc-001"));
        verify(cryptoServiceClient, never()).verify(any(), any());

        // DB: ontsleuteld bericht opgeslagen
        EbmsMessageEntity entity = messageRepository.findByMessageId("msg-enc-001").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(entity.getRawSoapXml()).isEqualTo(decryptedSoap);
        assertThat(entity.getDirection()).isEqualTo(MessageDirection.INBOUND);
        assertThat(entity.getCpaId()).isEqualTo(TEST_CPA_ID);
        assertThat(entity.getFromPartyId()).isEqualTo(FROM_OIN);
        assertThat(entity.getToPartyId()).isEqualTo(TO_OIN);

        // AMQP: inbound-queue bevat ontsleuteld bericht
        EbmsInboundMessage inboundMsg = receiveFromQueue(RabbitMqConfig.QUEUE_INBOUND, EbmsInboundMessage.class);
        assertThat(inboundMsg).isNotNull();
        assertThat(inboundMsg.getMessageId()).isEqualTo("msg-enc-001");
        assertThat(inboundMsg.getRawSoapXml()).isEqualTo(decryptedSoap);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 2 – Ondertekend bericht (osb-be-s): verify → persist → AMQP
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Ondertekend bericht: verify aangeroepen, decrypt NIET, originele SOAP opgeslagen")
    void processInboundMessage_signedBody_verifyCalledWithoutDecrypt() throws Exception {
        String signedSoap = buildSoapXml("msg-sig-001", true, false, false);

        when(cryptoServiceClient.verify(eq(signedSoap), eq("msg-sig-001"))).thenReturn(true);

        SOAPMessage request = parseSoap(signedSoap);
        EbxmlMessageHeader header = buildHeader("msg-sig-001", "conv-msg-sig-001", null);

        orchestratorService.processInboundMessage(request, header, signedSoap, CLIENT_OIN);

        // Crypto: verify JA (met originele SOAP), decrypt NEE
        verify(cryptoServiceClient, never()).decrypt(any(), any(), any());
        verify(cryptoServiceClient).verify(eq(signedSoap), eq("msg-sig-001"));

        // DB: originele (niet-gewijzigde) SOAP opgeslagen
        EbmsMessageEntity entity = messageRepository.findByMessageId("msg-sig-001").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(entity.getRawSoapXml()).isEqualTo(signedSoap);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 3 – Versleuteld + ondertekend (osb-rm-e): decrypt EERST, dan verify
    //          met de ontsleutelde XML. RM-ACK teruggegeven.
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-rm-e: decrypt eerst, dan verify met decrypted SOAP, volgorde gegarandeerd")
    void processInboundMessage_encryptedAndSigned_decryptThenVerifyInOrder() throws Exception {
        // Buitenste envelop: signature in header + versleuteld body
        String encSignedSoap  = buildSoapXml("msg-full-001", true, true, true);
        // Na decryptie: body is leesbaar, signature blijft in header
        String decryptedSoap  = buildSoapXml("msg-full-001", true, false, true);

        when(cryptoServiceClient.decrypt(eq(encSignedSoap), anyString(), eq("msg-full-001")))
            .thenReturn(decryptedSoap);
        when(cryptoServiceClient.verify(eq(decryptedSoap), eq("msg-full-001"))).thenReturn(true);

        SOAPMessage request = parseSoap(encSignedSoap);
        AckRequested ackRequested = AckRequested.builder().mustUnderstand(true).signed(false).build();
        EbxmlMessageHeader header = buildHeader("msg-full-001", "conv-msg-full-001", ackRequested);

        SOAPMessage response = orchestratorService.processInboundMessage(
            request, header, encSignedSoap, CLIENT_OIN);

        // Volgorde: decrypt vóór verify
        InOrder inOrder = inOrder(cryptoServiceClient);
        inOrder.verify(cryptoServiceClient)
            .decrypt(eq(encSignedSoap), anyString(), eq("msg-full-001"));
        inOrder.verify(cryptoServiceClient)
            .verify(eq(decryptedSoap), eq("msg-full-001"));

        // DB: ontsleutelde SOAP opgeslagen, ackRequested=true
        EbmsMessageEntity entity = messageRepository.findByMessageId("msg-full-001").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(MessageStatus.PROCESSING);
        assertThat(entity.getRawSoapXml()).isEqualTo(decryptedSoap);
        assertThat(entity.isAckRequested()).isTrue();

        // AMQP: ontsleutelde XML gepubliceerd
        EbmsInboundMessage inboundMsg = receiveFromQueue(
            RabbitMqConfig.QUEUE_INBOUND, EbmsInboundMessage.class);
        assertThat(inboundMsg.getMessageId()).isEqualTo("msg-full-001");
        assertThat(inboundMsg.getRawSoapXml()).isEqualTo(decryptedSoap);

        // Response: SOAP ACK (geen SOAP fault)
        assertThat(response.getSOAPBody().hasFault()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 4 – Plaintext bericht (osb-be): geen crypto
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-be plaintext: geen crypto-aanroepen, bericht opgeslagen en gepubliceerd")
    void processInboundMessage_plainTextBestEffort_noCryptoOperations() throws Exception {
        String rawSoap = buildSoapXml("msg-plain-001", false, false, false);

        SOAPMessage request = parseSoap(rawSoap);
        EbxmlMessageHeader header = buildHeader("msg-plain-001", "conv-msg-plain-001", null);

        orchestratorService.processInboundMessage(request, header, rawSoap, CLIENT_OIN);

        // Geen enkele crypto-aanroep
        verifyNoInteractions(cryptoServiceClient);

        // DB: bericht aanwezig
        assertThat(messageRepository.existsByMessageId("msg-plain-001")).isTrue();

        // AMQP
        EbmsInboundMessage inboundMsg = receiveFromQueue(
            RabbitMqConfig.QUEUE_INBOUND, EbmsInboundMessage.class);
        assertThat(inboundMsg).isNotNull();
        assertThat(inboundMsg.getMessageId()).isEqualTo("msg-plain-001");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 5 – Ongeldige handtekening: XmlSecurityException, geen DB-opslag
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Ongeldige handtekening: XmlSecurityException gegooid, bericht NIET opgeslagen in DB")
    void processInboundMessage_invalidSignature_throwsExceptionWithoutPersistence() throws Exception {
        String signedSoap = buildSoapXml("msg-badsig-001", true, false, false);

        when(cryptoServiceClient.verify(any(), any()))
            .thenThrow(new XmlSecurityException("Handtekening ongeldig: digest-mismatch"));

        SOAPMessage request = parseSoap(signedSoap);
        EbxmlMessageHeader header = buildHeader("msg-badsig-001", "conv-msg-badsig-001", null);

        assertThatThrownBy(() ->
            orchestratorService.processInboundMessage(request, header, signedSoap, CLIENT_OIN))
            .isInstanceOf(XmlSecurityException.class)
            .hasMessageContaining("Handtekening ongeldig");

        // Geen DB-opslag na verificatiefout
        assertThat(messageRepository.existsByMessageId("msg-badsig-001")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 6 – Duplicate-suppression
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Dubbele messageId: DuplicateMessageException gegooid bij tweede aanbieding")
    void processInboundMessage_duplicateMessageId_rejectsSecondSubmission() throws Exception {
        String rawSoap = buildSoapXml("msg-dup-001", false, false, false);
        EbxmlMessageHeader header = buildHeader("msg-dup-001", "conv-msg-dup-001", null);

        // Eerste verwerking: slaagt
        orchestratorService.processInboundMessage(parseSoap(rawSoap), header, rawSoap, CLIENT_OIN);

        // Tweede verwerking: zelfde messageId
        assertThatThrownBy(() ->
            orchestratorService.processInboundMessage(parseSoap(rawSoap), header, rawSoap, CLIENT_OIN))
            .isInstanceOf(DuplicateMessageException.class);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 7 – CPA-validatie geblokkeerd
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CPA-validatie mislukt: EbmsException gegooid, geen DB-opslag, geen crypto")
    void processInboundMessage_cpaValidationFails_throwsEbmsExceptionWithoutCrypto() throws Exception {
        when(cpaValidationService.validateCpaAndOin(any(), any()))
            .thenReturn(CpaValidationResult.failure("CPA niet gevonden: urn:onbekend:cpa"));

        String rawSoap = buildSoapXml("msg-cpablock-001", false, false, false);
        EbxmlMessageHeader header = buildHeader("msg-cpablock-001", "conv-msg-cpablock-001", null);

        assertThatThrownBy(() ->
            orchestratorService.processInboundMessage(parseSoap(rawSoap), header, rawSoap, CLIENT_OIN))
            .isInstanceOf(nl.logius.ebms.common.exception.EbmsException.class);

        verifyNoInteractions(cryptoServiceClient);
        assertThat(messageRepository.existsByMessageId("msg-cpablock-001")).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 8 – ACK-afhandeling: SENT → DELIVERED + EbmsAckEvent publicatie
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ACK ontvangen: SENT→DELIVERED status-overgang en EbmsAckEvent op ack-queue")
    void handleAcknowledgment_sentMessage_transitionsToDeliveredAndPublishesAckEvent() throws Exception {
        // Opslaan van een eerder verzonden rm-bericht (status=SENT)
        messageRepository.save(buildSentOutboundEntity("msg-ack-001"));

        orchestratorService.handleAcknowledgment("msg-ack-001");

        // DB: SENT → DELIVERED
        EbmsMessageEntity updated = messageRepository.findByMessageId("msg-ack-001").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(MessageStatus.DELIVERED);

        // AMQP: EbmsAckEvent op ebms.ack.events
        EbmsAckEvent ackEvent = receiveFromQueue(RabbitMqConfig.QUEUE_ACK, EbmsAckEvent.class);
        assertThat(ackEvent).isNotNull();
        assertThat(ackEvent.getMessageId()).isEqualTo("msg-ack-001");
        assertThat(ackEvent.getCpaId()).isEqualTo(TEST_CPA_ID);
        assertThat(ackEvent.getAckSenderPartyId()).isEqualTo(TO_OIN); // degene die de ACK stuurde
        assertThat(ackEvent.getAcknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("ACK voor niet-SENT bericht: geen DB-mutatie, geen exception (dubbele ACK)")
    void handleAcknowledgment_unknownMessageId_noExceptionNoStateChange() {
        // Bericht bestaat niet in DB
        assertThatCode(() -> orchestratorService.handleAcknowledgment("msg-nonexistent-001"))
            .doesNotThrowAnyException();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 9 – Audit-events
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Succesvol verwerkt bericht: AuditEvent 'MESSAGE_RECEIVED' gepubliceerd")
    void processInboundMessage_success_publishesAuditEvent() throws Exception {
        String rawSoap = buildSoapXml("msg-audit-001", false, false, false);
        EbxmlMessageHeader header = buildHeader("msg-audit-001", "conv-msg-audit-001", null);

        orchestratorService.processInboundMessage(parseSoap(rawSoap), header, rawSoap, CLIENT_OIN);

        AuditEvent auditEvent = receiveFromQueue(RabbitMqConfig.QUEUE_AUDIT, AuditEvent.class);
        assertThat(auditEvent).isNotNull();
        assertThat(auditEvent.getEventType()).isEqualTo("MESSAGE_RECEIVED");
        assertThat(auditEvent.getMessageId()).isEqualTo("msg-audit-001");
        assertThat(auditEvent.getCpaId()).isEqualTo(TEST_CPA_ID);
        assertThat(auditEvent.getResult()).isEqualTo("SUCCESS");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bouw-helpers voor SOAP-berichten
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Construeert een minimale, syntactisch correcte ebMS2 SOAP-envelop.
     *
     * @param messageId het ebXML MessageId
     * @param withSig   voeg gesimuleerde {@code ds:Signature} toe in de SOAP-header
     * @param withEnc   voeg gesimuleerde {@code xenc:EncryptedData} toe in de SOAP-body
     * @param withAck   voeg {@code AckRequested} toe in de SOAP-header
     */
    private String buildSoapXml(String messageId, boolean withSig,
                                  boolean withEnc, boolean withAck) throws Exception {
        MessageFactory mf   = MessageFactory.newInstance();
        SOAPMessage   msg   = mf.createMessage();
        SOAPEnvelope  env   = msg.getSOAPPart().getEnvelope();
        SOAPHeader    sh    = msg.getSOAPHeader();
        SOAPBody      body  = msg.getSOAPBody();

        final String NS = SoapHelper.EBXML_MSG_NS;

        // ── ebXML MessageHeader ───────────────────────────────────────────
        SOAPElement mh = sh.addChildElement("MessageHeader", "eb", NS);
        mh.addAttribute(env.createName("mustUnderstand", "SOAP-ENV", SoapHelper.SOAP_ENV_NS), "1");
        mh.addAttribute(env.createName("version", "eb", NS), "2.0");

        SOAPElement from = mh.addChildElement("From", "eb", NS);
        from.addChildElement("PartyId", "eb", NS).addTextNode(FROM_OIN);

        SOAPElement to = mh.addChildElement("To", "eb", NS);
        to.addChildElement("PartyId", "eb", NS).addTextNode(TO_OIN);

        mh.addChildElement("CPAId",          "eb", NS).addTextNode(TEST_CPA_ID);
        mh.addChildElement("ConversationId", "eb", NS).addTextNode("conv-" + messageId);

        SOAPElement svc = mh.addChildElement("Service", "eb", NS);
        svc.addTextNode("urn:test:service");

        mh.addChildElement("Action", "eb", NS).addTextNode("TestAction");

        SOAPElement mi = mh.addChildElement("MessageInfo", "eb", NS);
        mi.addChildElement("Timestamp", "eb", NS).addTextNode(Instant.now().toString());
        mi.addChildElement("MessageId",  "eb", NS).addTextNode(messageId);

        // ── XML-DSig Signature (gesimuleerd – leeg element voor detectie) ─
        if (withSig) {
            sh.addChildElement("Signature", "ds", "http://www.w3.org/2000/09/xmldsig#");
        }

        // ── AckRequested (rm-profielen) ───────────────────────────────────
        if (withAck) {
            SOAPElement ackReq = sh.addChildElement("AckRequested", "eb", NS);
            ackReq.addAttribute(env.createName("mustUnderstand", "SOAP-ENV", SoapHelper.SOAP_ENV_NS), "1");
            ackReq.addAttribute(env.createName("signed", "eb", NS), "false");
        }

        // ── XML-Enc EncryptedData (gesimuleerd) of normale payload ────────
        if (withEnc) {
            body.addChildElement("EncryptedData", "xenc", "http://www.w3.org/2001/04/xmlenc#");
        } else {
            body.addChildElement("TestPayload", "tst", "urn:test:payload")
                .addTextNode("Testinhoud-" + messageId);
        }

        msg.saveChanges();
        return soapToString(msg);
    }

    private SOAPMessage parseSoap(String xml) throws Exception {
        return MessageFactory.newInstance().createMessage(
            new MimeHeaders(),
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private String soapToString(SOAPMessage msg) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            msg.writeTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bouw-helper voor EbxmlMessageHeader
    // ═══════════════════════════════════════════════════════════════════════════

    private EbxmlMessageHeader buildHeader(String messageId, String conversationId,
                                            AckRequested ackRequested) {
        return EbxmlMessageHeader.builder()
            .cpaId(TEST_CPA_ID)
            .conversationId(conversationId)
            .from(List.of(
                PartyId.builder()
                    .value(FROM_OIN)
                    .type("urn:oasis:names:tc:ebcore:partyid-type:iso6523:0190")
                    .build()))
            .fromRole("aanbieder")
            .to(List.of(
                PartyId.builder()
                    .value(TO_OIN)
                    .type("urn:oasis:names:tc:ebcore:partyid-type:iso6523:0190")
                    .build()))
            .toRole("afnemer")
            .service(ServiceType.builder().value("urn:test:service").build())
            .action("TestAction")
            .messageInfo(MessageInfo.builder()
                .messageId(messageId)
                .timestamp(Instant.now())
                .build())
            .ackRequested(ackRequested)
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bouw-helper voor uitgaand SENT-bericht (ACK-scenario)
    // ═══════════════════════════════════════════════════════════════════════════

    private EbmsMessageEntity buildSentOutboundEntity(String messageId) {
        return EbmsMessageEntity.builder()
            .messageId(messageId)
            .conversationId("conv-" + messageId)
            .cpaId(TEST_CPA_ID)
            .fromPartyId(FROM_OIN)
            .toPartyId(TO_OIN)
            .service("urn:test:service")
            .action("TestAction")
            .direction(MessageDirection.OUTBOUND)
            .status(MessageStatus.SENT)
            .ackRequested(true)
            .timestamp(Instant.now())
            .rawSoapXml("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>")
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AMQP hulpfuncties
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Leest één bericht van de opgegeven RabbitMQ-queue (timeout 5 seconden).
     * Deserialiseert de JSON-body naar het opgegeven type.
     *
     * @return gedeserialiseerd bericht of {@code null} als de queue leeg blijft
     */
    private <T> T receiveFromQueue(String queueName, Class<T> type) throws Exception {
        Message amqpMsg = rabbitTemplate.receive(queueName, 5_000);
        if (amqpMsg == null) return null;
        return objectMapper.readValue(amqpMsg.getBody(), type);
    }

    /** Leegt een queue van eventuele resten van vorige tests. */
    private void drainQueue(String queueName) {
        Message msg;
        do {
            msg = rabbitTemplate.receive(queueName, 200);
        } while (msg != null);
    }
}
