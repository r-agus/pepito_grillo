package sipServlet;

public class RedirectServlet implements SIPServletInterface {

    @Override
    public void doInvite(SipServletRequestInterface req) {
        System.out.println("RedirectServlet: Redirecting call to Charlie...");
        req.getProxy().proxyTo("sip:charlie@domain.com");
    }
}
