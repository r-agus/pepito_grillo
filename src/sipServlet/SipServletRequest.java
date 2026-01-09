package sipServlet;

import mensajesSIP.InviteMessage;

public class SipServletRequest implements SipServletRequestInterface {

    private String callerURI;
    private String calleeURI;
    private InviteMessage inviteMessage;
    
    // State to store the decision
    private boolean responseSent = false;
    private int responseStatusCode = -1;
    
    private boolean proxyActionTaken = false;
    private String proxyToURI = null;

    public SipServletRequest(InviteMessage inviteMessage) {
        this.inviteMessage = inviteMessage;
        this.callerURI = inviteMessage.getFromName();
        this.calleeURI = inviteMessage.getToName();
    }

    @Override
    public String getCallerURI() {
        return callerURI;
    }

    @Override
    public String getCalleeURI() {
        return calleeURI;
    }

    @Override
    public SipServletResponseInterface createResponse(int statuscode) {
        return new SipServletResponse(this, statuscode);
    }

    @Override
    public ProxyInterface getProxy() {
        return new ProxyImpl(this);
    }
    
    // Methods for the container to check the result
    public boolean isResponseSent() {
        return responseSent;
    }

    public int getResponseStatusCode() {
        return responseStatusCode;
    }

    public boolean isProxyActionTaken() {
        return proxyActionTaken;
    }

    public String getProxyToURI() {
        return proxyToURI;
    }

    // Methods for the API implementations to update state
    protected void setResponse(int statusCode) {
        this.responseSent = true;
        this.responseStatusCode = statusCode;
    }

    protected void setProxyTo(String uri) {
        this.proxyActionTaken = true;
        this.proxyToURI = uri;
    }
}
