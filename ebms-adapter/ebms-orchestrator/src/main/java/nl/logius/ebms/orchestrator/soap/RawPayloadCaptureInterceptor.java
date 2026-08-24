package nl.logius.ebms.orchestrator.soap;

import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.helpers.IOUtils;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.io.CachedOutputStream;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Legt de ONBEWERKTE (rauwe) HTTP request-body vast vóórdat SAAJ/CXF deze parseert naar een
 * {@link jakarta.xml.soap.SOAPMessage}.
 *
 * <p>Nodig omdat {@code SOAPMessage.writeTo()} (gebruikt in {@code SoapHelper.soapToString()})
 * het bericht opnieuw serialiseert vanuit het reeds geparseerde SAAJ-object. Die herserialisatie
 * kan de XML-infoset subtiel wijzigen t.o.v. de bytes die de verzendende partij daadwerkelijk
 * stuurde, wat bij XML-DSig verificatie tot een digest mismatch leidt (Exclusive C14N beschermt
 * hier niet tegen, want het gaat om daadwerkelijke infoset-verschillen, niet alleen om
 * canonicalisatie-neutrale formattering).
 *
 * <p>Draait in {@link Phase#RECEIVE} — het allereerste punt in de inbound interceptor-chain,
 * vóór transport/SAAJ de stream consumeert — en cachet de bytes via {@link CachedOutputStream}
 * zodat de stream daarna nog volledig leesbaar is voor de SOAP-parser (geen
 * "stream already consumed"-probleem).
 */
@Slf4j
public class RawPayloadCaptureInterceptor extends AbstractPhaseInterceptor<Message> {

    /** Exchange-property key waaronder de rauwe XML-payload wordt opgeslagen. */
    public static final String RAW_XML_PAYLOAD = "ebms.raw.xml.payload";

    public RawPayloadCaptureInterceptor() {
        super(Phase.RECEIVE);
    }

    @Override
    public void handleMessage(Message message) throws Fault {
        InputStream in = message.getContent(InputStream.class);
        if (in == null) {
            return;
        }

        CachedOutputStream cos = new CachedOutputStream();
        try {
            IOUtils.copy(in, cos);
            in.close();
            cos.flush();
        } catch (Exception e) {
            log.warn("[RAW-CAPTURE] Kon inbound stream niet cachen - normale SAAJ-verwerking "
                + "gaat door (fallback naar soapToString()): {}", e.getMessage());
            return;
        }

        try {
            // Content-Type charset respecteren i.p.v. altijd UTF-8 aan te nemen
            String encoding = (String) message.get(org.apache.cxf.message.Message.ENCODING);
            String charsetName = (encoding != null && !encoding.isBlank())
                ? encoding : StandardCharsets.UTF_8.name();
            String rawXml = IOUtils.toString(cos.getInputStream(), charsetName);
            message.getExchange().put(RAW_XML_PAYLOAD, rawXml);
        } catch (Exception e) {
            log.warn("[RAW-CAPTURE] Kon rauwe payload niet als string vastleggen "
                + "(fallback naar soapToString()): {}", e.getMessage());
        }

        try {
            // Stream vervangen door een verse, volledig leesbare kopie voor de SOAP-parser
            message.setContent(InputStream.class, cos.getInputStream());
        } catch (Exception e) {
            log.warn("[RAW-CAPTURE] Kon vervangen InputStream niet instellen: {}", e.getMessage());
        }
    }
}
