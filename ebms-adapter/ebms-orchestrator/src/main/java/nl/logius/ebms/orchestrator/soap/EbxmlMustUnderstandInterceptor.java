package nl.logius.ebms.orchestrator.soap;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.phase.Phase;

import javax.xml.namespace.QName;
import java.util.Set;

/**
 * Declareert de ebXML 2.0 SOAP-headerblokken die altijd met {@code mustUnderstand="1"} verstuurd
 * worden ({@code MessageHeader}/{@code AckRequested}/{@code Acknowledgment}/{@code ErrorList},
 * zie {@link SoapHelper}) als "begrepen" bij CXF.
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
        new QName(SoapHelper.EBXML_MSG_NS, "ErrorList")
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
