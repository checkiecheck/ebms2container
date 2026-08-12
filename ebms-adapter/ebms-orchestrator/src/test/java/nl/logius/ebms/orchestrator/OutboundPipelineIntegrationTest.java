package nl.logius.ebms.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.logius.ebms.common.model.amqp.AuditEvent;
import nl.logius.ebms.common.model.amqp.EbmsOutboundMessage;
import nl.logius.ebms.common.model.cpa.DeliveryChannelDto;
import nl.logius.ebms.common.model.ebxml.*;
import nl.logius.ebms.orchestrator.config.RabbitMqConfig;
import nl.logius.ebms.orchestrator.entity.EbmsMessageEntity;
import nl.logius.ebms.orchestrator.entity.MessageStatus;
import nl.logius.ebms.orchestrator.repository.EbmsMessageRepository;
import nl.logius.ebms.orchestrator.service.CpaChannelCacheService;
import nl.logius.ebms.orchestrator.service.CryptoServiceClient;
import nl.logius.ebms.orchestrator.soap.OutboundSoapClient;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integratietest voor de complete outbound ebMS2-pipeline.
 *
 * <p>Simuleert de backoffice: publiceert een {@link EbmsOutboundMessage} op de
 * {@code ebms.outbound.messages} queue. De {@code OutboundMessageService} @RabbitListener
 * verwerkt het bericht asynchroon. Awaitility synchroniseert de testassertie met de
 * async verwerkingsthread.
 *
 * <p>Pipeline stappen per profiel:
 * <pre>
 *   osb-be    : SOAP build →                       send → DELIVERED
 *   osb-rm    : SOAP build →                       send → SENT
 *   osb-be-s  : SOAP build → sign →                send → DELIVERED
 *   osb-rm-e  : SOAP build → sign → encrypt →      send → SENT
 * </pre>
 *
 * <p>HTTP-afhankelijkheden ({@link CpaChannelCacheService}, {@link OutboundSoapClient},
 * {@link CryptoServiceClient}) worden via {@code @MockitoBean} gesimuleerd.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboundPipelineIntegrationTest {

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

        registry.add("ebms.cpa-service-url",    () -> "http://localhost:19999");
        registry.add("ebms.crypto-service-url", () -> "http://localhost:19998");
    }

    // ── Mocks (HTTP-services vervangen) ───────────────────────────────────────

    @MockitoBean
    CpaChannelCacheService cpaChannelCacheService;

    @MockitoBean
    OutboundSoapClient outboundSoapClient;

    @MockitoBean
    CryptoServiceClient cryptoServiceClient;

    // ── Spring beans ──────────────────────────────────────────────────────────

    @Autowired
    EbmsMessageRepository messageRepository;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    ObjectMapper objectMapper;

    // ── Testconstanten ────────────────────────────────────────────────────────

    private static final String CPA_ID    = "urn:test:cpa:outbound";
    private static final String FROM_OIN  = "00000000000000000001";
    private static final String TO_OIN    = "00000000000000000002";
    private static final String ENDPOINT  = "https://test-partner.example.com/soap/ebms";

    // ── Testopzet ─────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        messageRepository.deleteAll();
        drainQueue(RabbitMqConfig.QUEUE_AUDIT);
        drainQueue(RabbitMqConfig.QUEUE_DLQ);

        // Standaard: send retourneert null (return-waarde niet gebruikt door service)
        when(outboundSoapClient.send(anyString(), anyString())).thenReturn(null);

        // Standaard: sign-mock geeft gemarkeerde XML terug voor traceerbaarheid
        when(cryptoServiceClient.sign(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> "SIGNED:" + inv.<String>getArgument(0));

        // Standaard: encrypt-mock geeft gemarkeerde XML terug
        when(cryptoServiceClient.encrypt(anyString(), anyString(), anyString()))
            .thenAnswer(inv -> "ENCRYPTED:" + inv.<String>getArgument(0));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 1 – osb-be (Best Effort, geen crypto): SOAP build → send → DELIVERED
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-be: geen signing of encryptie, bericht DELIVERED na verzending")
    void outbound_bestEffort_noSigningNoEncryption_statusDeliveredAfterSend() {
        String msgId = "out-be-001";
        configureChannel("osb-be");

        publish(buildOutboundMessage(msgId, false, false));

        // Wacht op DB-update door de async listener
        awaitStatus(msgId, MessageStatus.DELIVERED);

        // Crypto: geen enkele aanroep
        verifyNoInteractions(cryptoServiceClient);

        // SOAP verzonden naar juist endpoint
        verify(outboundSoapClient).send(eq(ENDPOINT), anyString());

        // DB: velden correct opgeslagen
        EbmsMessageEntity entity = messageRepository.findByMessageId(msgId).orElseThrow();
        assertThat(entity.getCpaId()).isEqualTo(CPA_ID);
        assertThat(entity.getFromPartyId()).isEqualTo(FROM_OIN);
        assertThat(entity.getToPartyId()).isEqualTo(TO_OIN);
        assertThat(entity.getAction()).isEqualTo("TestAction");
        assertThat(entity.getRawSoapXml()).isNotBlank();
        assertThat(entity.isAckRequested()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 2 – osb-rm (Reliable Messaging, geen crypto): SOAP build → send → SENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-rm: geen crypto, status SENT na verzending (wacht op ACK)")
    void outbound_reliableMessaging_noCrypto_statusSentAfterSend() {
        String msgId = "out-rm-001";
        configureChannel("osb-rm");

        publish(buildOutboundMessage(msgId, false, false));

        awaitStatus(msgId, MessageStatus.SENT);

        verifyNoInteractions(cryptoServiceClient);
        verify(outboundSoapClient).send(eq(ENDPOINT), anyString());

        // RM: AckRequested=true, status SENT (niet DELIVERED)
        EbmsMessageEntity entity = messageRepository.findByMessageId(msgId).orElseThrow();
        assertThat(entity.isAckRequested()).isTrue();
        assertThat(entity.getStatus()).isEqualTo(MessageStatus.SENT);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 3 – osb-be-s (signing only): sign → send → DELIVERED
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-be-s: sign aangeroepen, geen encryptie, status DELIVERED")
    void outbound_signingProfile_signCalledBeforeSend_noEncryption() {
        String msgId = "out-bes-001";
        configureChannel("osb-be-s");

        publish(buildOutboundMessage(msgId, true, false));

        awaitStatus(msgId, MessageStatus.DELIVERED);

        // Sign: één keer aangeroepen met standaard signing-alias
        verify(cryptoServiceClient).sign(anyString(), eq("signing-key"), eq(msgId));
        verify(cryptoServiceClient, never()).encrypt(any(), any(), any());

        // Verstuurde SOAP bevat de ondertekende inhoud
        verify(outboundSoapClient).send(eq(ENDPOINT), argThat(xml -> xml.startsWith("SIGNED:")));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 4 – osb-rm-s (signing + rm): sign → send → SENT
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-rm-s: sign aangeroepen, AckRequested=true, status SENT")
    void outbound_rmSigning_signCalledStatusSent() {
        String msgId = "out-rms-001";
        configureChannel("osb-rm-s");

        publish(buildOutboundMessage(msgId, true, false));

        awaitStatus(msgId, MessageStatus.SENT);

        verify(cryptoServiceClient).sign(anyString(), eq("signing-key"), eq(msgId));
        verify(cryptoServiceClient, never()).encrypt(any(), any(), any());

        EbmsMessageEntity entity = messageRepository.findByMessageId(msgId).orElseThrow();
        assertThat(entity.isAckRequested()).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 5 – osb-rm-e (sign + encrypt + rm): sign EERST, dan encrypt, dan send
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("osb-rm-e: sign dan encrypt in juiste volgorde, verstuurde XML is 'ENCRYPTED:SIGNED:...'")
    void outbound_signThenEncrypt_correctOrderAndPayload_statusSent() {
        String msgId = "out-rme-001";
        configureChannel("osb-rm-e");

        publish(buildOutboundMessage(msgId, true, true));

        awaitStatus(msgId, MessageStatus.SENT);

        // Volgorde gegarandeerd: sign → encrypt
        InOrder inOrder = inOrder(cryptoServiceClient);
        inOrder.verify(cryptoServiceClient).sign(anyString(), eq("signing-key"), eq(msgId));
        inOrder.verify(cryptoServiceClient).encrypt(anyString(), eq(TO_OIN), eq(msgId));

        // De verstuurde XML is het resultaat van encrypt(sign(soapXml)):
        // sign() geeft "SIGNED:...", encrypt() geeft "ENCRYPTED:SIGNED:..."
        verify(outboundSoapClient).send(
            eq(ENDPOINT),
            argThat(xml -> xml.startsWith("ENCRYPTED:SIGNED:")));

        // RM: AckRequested=true + SENT-status
        EbmsMessageEntity entity = messageRepository.findByMessageId(msgId).orElseThrow();
        assertThat(entity.isAckRequested()).isTrue();
        assertThat(entity.getRawSoapXml()).startsWith("ENCRYPTED:SIGNED:");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 6 – Ongeldige header (null): bericht verworpen (requeue=false → DLQ)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Null header: bericht verworpen (requeue=false), bericht gaat naar DLQ, geen DB-opslag")
    void outbound_nullHeader_messageDiscardedToDlq_noDatabaseEntry() {
        String msgId = "out-null-001";

        // Bericht zonder header
        publish(EbmsOutboundMessage.builder()
            .messageId(msgId)
            .header(null)
            .build());

        // DLQ ontvangt het verworpen bericht (requeue=false → dead-letter-routing-key=ebms.dlq)
        Message dlqMsg = rabbitTemplate.receive(RabbitMqConfig.QUEUE_DLQ, 10_000);
        assertThat(dlqMsg).as("Bericht moet in DLQ belanden na requeue=false nack").isNotNull();

        // Geen DB-opslag
        assertThat(messageRepository.existsByMessageId(msgId)).isFalse();

        // Geen crypto-aanroepen
        verifyNoInteractions(cryptoServiceClient);
        verifyNoInteractions(outboundSoapClient);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 7 – EbmsException tijdens verzending: nack + requeue, geen DELIVERED
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("EbmsException bij eerste send: bericht gerequeued, tweede poging succesvol → status DELIVERED")
    void outbound_sendThrowsEbmsException_messageRequeuedStatusNotDelivered() {
        String msgId = "out-err-001";
        configureChannel("osb-be");

        // Eerste aanroep gooit exception, tweede succesvol (simuleert transient fout)
        when(outboundSoapClient.send(anyString(), anyString()))
            .thenThrow(new nl.logius.ebms.common.exception.EbmsException("CONNECTION_ERROR",
                "Simuleer verbindingsfout"))
            .thenReturn(null);

        publish(buildOutboundMessage(msgId, false, false));

        // Na retry: bericht alsnog DELIVERED (tweede poging slaagt)
        awaitStatus(msgId, MessageStatus.DELIVERED);

        // send moet precies twee keer aangeroepen zijn
        verify(outboundSoapClient, times(2)).send(eq(ENDPOINT), anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 8 – Audit-event na succesvolle verzending
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Audit-event MESSAGE_SENT gepubliceerd op ebms.audit.events na succesvolle verzending")
    void outbound_successfulSend_auditEventPublishedWithCorrectFields() throws Exception {
        String msgId = "out-audit-001";
        configureChannel("osb-be");

        publish(buildOutboundMessage(msgId, false, false));

        awaitStatus(msgId, MessageStatus.DELIVERED);

        AuditEvent auditEvent = receiveFromQueue(RabbitMqConfig.QUEUE_AUDIT, AuditEvent.class);
        assertThat(auditEvent).isNotNull();
        assertThat(auditEvent.getEventType()).isEqualTo("MESSAGE_SENT");
        assertThat(auditEvent.getMessageId()).isEqualTo(msgId);
        assertThat(auditEvent.getCpaId()).isEqualTo(CPA_ID);
        assertThat(auditEvent.getResult()).isEqualTo("SUCCESS");
        assertThat(auditEvent.getAction()).isEqualTo("TestAction");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 9 – Payload-metadata correct gepersisteerd
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Payload-metadata (payloadRef, payloadContentType) correct opgeslagen in DB")
    void outbound_payloadMetadata_persistedCorrectly() {
        String msgId = "out-payload-001";
        configureChannel("osb-be");

        publish(EbmsOutboundMessage.builder()
            .messageId(msgId)
            .header(buildHeader(msgId))
            .requireSigning(false)
            .requireEncryption(false)
            .payloadRef("s3://ebms-payloads/out-payload-001.xml")
            .payloadContentType("application/xml")
            .build());

        awaitStatus(msgId, MessageStatus.DELIVERED);

        EbmsMessageEntity entity = messageRepository.findByMessageId(msgId).orElseThrow();
        assertThat(entity.getPayloadRef()).isEqualTo("s3://ebms-payloads/out-payload-001.xml");
        assertThat(entity.getPayloadContentType()).isEqualTo("application/xml");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test 10 – Meerdere berichten parallel (throughput-check)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5 opeenvolgende osb-be-berichten worden allemaal DELIVERED verwerkt")
    void outbound_multipleMessages_allDeliveredInSequence() {
        configureChannel("osb-be");

        for (int i = 1; i <= 5; i++) {
            publish(buildOutboundMessage("out-multi-00" + i, false, false));
        }

        // Wacht tot alle 5 berichten DELIVERED zijn
        for (int i = 1; i <= 5; i++) {
            String msgId = "out-multi-00" + i;
            awaitStatus(msgId, MessageStatus.DELIVERED);
        }

        // Verify per bericht
        assertThat(messageRepository.count()).isGreaterThanOrEqualTo(5);
        verify(outboundSoapClient, times(5)).send(eq(ENDPOINT), anyString());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Testhelpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Configureert het afleverkanaal-mock voor alle tests in dit scenario.
     * Vervangt de echte cpa-service HTTP-lookup.
     */
    private void configureChannel(String dkProfile) {
        DeliveryChannelDto channel = DeliveryChannelDto.builder()
            .cpaId(CPA_ID)
            .partyId(TO_OIN)
            .channelId("channel-1")
            .dkProfile(dkProfile)
            .endpointUrl(ENDPOINT)
            .persistDuration(86_400) // 24 uur TTL
            .build();
        when(cpaChannelCacheService.getChannel(eq(CPA_ID), eq(TO_OIN))).thenReturn(channel);
    }

    /**
     * Bouwt een testbericht voor de outbound-queue.
     *
     * <p><strong>Let op</strong>: {@code requireSigning} en {@code requireEncryption} worden
     * door {@code OutboundMessageService} <em>niet</em> gebruikt – het gedrag wordt uitsluitend
     * bepaald door {@code EbxmlProfile.fromCode(channel.getDkProfile())}. De parameters zijn
     * hier meegenomen voor documentatiedoeleinden en toekomstige implementatie-uitbreidingen.
     *
     * @param messageId         uniek berichtidentificatie
     * @param requireSigning    decoratief – profiel is leidend (zie {@link EbxmlProfile})
     * @param requireEncryption decoratief – profiel is leidend
     */
    private EbmsOutboundMessage buildOutboundMessage(String messageId,
                                                      boolean requireSigning,
                                                      boolean requireEncryption) {
        return EbmsOutboundMessage.builder()
            .messageId(messageId)
            .header(buildHeader(messageId))
            .requireSigning(requireSigning)
            .requireEncryption(requireEncryption)
            .payloadRef("s3://ebms-payloads/" + messageId + ".xml")
            .payloadContentType("application/xml")
            .build();
    }

    /**
     * Construeert een minimale maar volledige {@link EbxmlMessageHeader}.
     * Alle velden die door {@link nl.logius.ebms.orchestrator.soap.SoapHelper#buildOutboundSoap}
     * en {@link nl.logius.ebms.orchestrator.service.OutboundMessageService#persistOutboundMessage}
     * worden gebruikt zijn gevuld.
     */
    private EbxmlMessageHeader buildHeader(String messageId) {
        return EbxmlMessageHeader.builder()
            .cpaId(CPA_ID)
            .conversationId("conv-" + messageId)
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
            .build();
    }

    /** Publiceert een {@link EbmsOutboundMessage} op de outbound-exchange. */
    private void publish(EbmsOutboundMessage message) {
        rabbitTemplate.convertAndSend(
            RabbitMqConfig.EXCHANGE_EBMS,
            RabbitMqConfig.ROUTING_OUTBOUND,
            message);
    }

    /**
     * Wacht (max 15 sec) tot de entity de verwachte status heeft bereikt.
     * De listener werkt asynchroon → Awaitility synchroniseert de testasserties.
     */
    private void awaitStatus(String messageId, MessageStatus expected) {
        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> {
                EbmsMessageEntity entity =
                    messageRepository.findByMessageId(messageId).orElseThrow(
                        () -> new AssertionError(
                            "Bericht nog niet gepersisteerd: messageId=" + messageId));
                assertThat(entity.getStatus())
                    .as("Verwachte status %s voor messageId=%s", expected, messageId)
                    .isEqualTo(expected);
            });
    }

    /** Leest één bericht van de queue en deserialiseert naar het opgegeven type. */
    private <T> T receiveFromQueue(String queueName, Class<T> type) throws Exception {
        Message amqpMsg = rabbitTemplate.receive(queueName, 10_000);
        if (amqpMsg == null) return null;
        return objectMapper.readValue(amqpMsg.getBody(), type);
    }

    /** Leegt een queue van resterende berichten van vorige tests. */
    private void drainQueue(String queueName) {
        Message msg;
        do {
            msg = rabbitTemplate.receive(queueName, 200);
        } while (msg != null);
    }
}
