package proxy;

import java.io.IOException;
import java.net.SocketException;

import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPException;
import mensajesSIP.SIPMessage;

public class ProxyTransactionLayer {
    private static final int IDLE = 0;
    private int state = IDLE;

    private ProxyUserLayer userLayer;
    private ProxyTransportLayer transportLayer;

    public ProxyTransactionLayer(int listenPort, ProxyUserLayer userLayer) throws SocketException {
        this.userLayer = userLayer;
        this.transportLayer = new ProxyTransportLayer(listenPort, this);
    }

    public void onMessageReceived(SIPMessage sipMessage) throws IOException, SIPException {
        if (sipMessage instanceof RegisterMessage registerMessage) {
            userLayer.onRegisterReceived(registerMessage);
        } else if (sipMessage instanceof InviteMessage) {
            InviteMessage inviteMessage = (InviteMessage) sipMessage;
            switch (state) {
            case IDLE:
                userLayer.onInviteReceived(inviteMessage);
                break;
            default:
                System.err.println("Unexpected message at state " + state + ", throwing away");
                System.err.println("Message: " + sipMessage);
                break;
            }
        } else {
            System.err.println("Unexpected message (not instance of InviteMessage), throwing away");
            System.err.println("Message: " + sipMessage);
        }
    }

    public void echoRegisterResponse(SIPMessage registerResponse, String address, int port) throws IOException, SIPException {
        if (registerResponse instanceof OKMessage || registerResponse instanceof NotFoundMessage)
           transportLayer.send(registerResponse, address, port);
        else
            throw new SIPException("Incorrect response to register");
    }

    public void echoInvite(InviteMessage inviteMessage, String address, int port) throws IOException {
        transportLayer.send(inviteMessage, address, port);
    }

    public void startListening() {
        transportLayer.startListening();
    }
}
