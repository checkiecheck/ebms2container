package nl.logius.ebms.orchestrator.soap;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.phase.Phase;

import javax.xml.namespace.QName;
import java.util.Set;

/**
 * Declareert de ebXML 2.0 SOAP-headerblokken (OASIS ebMS_v2_0, namespace
 * {@code msg-header-2_0.xsd}) die met {@code mustUnderstand="1"} kunnen voorkomen als "begrepen"
 * bij CXF: {@code MessageHeader}/{@code AckRequested}/{@code Acknowledgment}/{@code ErrorList}
 * (versturen we zelf, zie {@link SoapHelper}) plus {@code SyncReply}/{@code TraceHeaderList}/
 * {@code Via}/{@code MessageOrder}/{@code StatusRequest}/{@code StatusResponse} (versturen we
 * zelf niet, maar een spec-conform partnersysteem kan dit meesturen).
 *
 * <p>Zonder deze declaratie wijst CXF's ingebouwde {@code MustUnderstandInterceptor} ÉLK
 * inkomend ebMS2-bericht af (SOAP Fault "MustUnderstand headers ... are not understood")
 * vóórdat het de applicatielogica ({@link EbmsMessageProvider}) ooit bereikt — de daadwerkelijke
 * parsing van deze headers gebeurt gewoon zoals altijd in {@link SoapHelper}/
 * {@code EbmsMessageProvider}; dit is puur de CXF-declaratie die dat inhoudelijk toestaat.
 */
public class EbxmlMustUnderstandInterceptor extends AbstractSoapInterceptor {

    private static final Set<QName> UNDERSTOOD_HEADERS = Set.of(
        new QName(SoapHelper.EBXML_MSG_NS, "MessageHeader"),
        new QName(SoapHelper.EBXML_MSG_NS, "AckRequested"),
        new QName(SoapHelper.EBXML_MSG_NS, "Acknowledgment"),
        new QName(SoapHelper.EBXML_MSG_NS, "ErrorList"),
        // Defensief: overige ebXML 2.0 headerblokken (OASIS ebMS_v2_0 §3) die wijzelf niet
        // versturen, maar die een ander (spec-conform) partnersysteem kan meesturen met
        // mustUnderstand="1". Niet gedeclareerd = CXF wijst het HELE bericht af, ook al gebruiken
        // wij deze velden niet. Digikoppeling-profielen gebruiken deze modules niet, maar
        // declareren kost niets en voorkomt een onterechte harde afwijzing.
        new QName(SoapHelper.EBXML_MSG_NS, "SyncReply"),
        new QName(SoapHelper.EBXML_MSG_NS, "TraceHeaderList"),
        new QName(SoapHelper.EBXML_MSG_NS, "Via"),
        new QName(SoapHelper.EBXML_MSG_NS, "MessageOrder"),
        new QName(SoapHelper.EBXML_MSG_NS, "StatusRequest"),
        new QName(SoapHelper.EBXML_MSG_NS, "StatusResponse")
    );

    public EbxmlMustUnderstandInterceptor() {
        super(Phase.READ);
    }

    @Override
    public Set<QName> getUnderstoodHeaders() {
        return UNDERSTOOD_HEADERS;
    }

    @Override
    public void handleMessage(SoapMessage message) throws Fault {
        // No-op: enige doel is getUnderstoodHeaders(); daadwerkelijke parsing blijft in
        // SoapHelper.parseMessageHeader()/EbmsMessageProvider zoals voorheen.
    }

    @Override
    public void handleFault(SoapMessage message) {
        // No-op
    }
}
