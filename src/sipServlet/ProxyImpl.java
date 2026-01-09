package sipServlet;

public class ProxyImpl implements ProxyInterface {

    private SipServletRequest request;

    public ProxyImpl(SipServletRequest request) {
        this.request = request;
    }

    @Override
    public void proxyTo(String uri) {
        request.setProxyTo(uri);
    }
}
