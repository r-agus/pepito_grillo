package sipServlet;

public class SipServletResponse implements SipServletResponseInterface {

    private SipServletRequest request;
    private int statusCode;

    public SipServletResponse(SipServletRequest request, int statusCode) {
        this.request = request;
        this.statusCode = statusCode;
    }

    @Override
    public void send() {
        request.setResponse(statusCode);
    }
}
