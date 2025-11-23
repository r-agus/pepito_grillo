package ua;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPMessage;

public class UaTransactionLayer {
    private enum State { IDLE, CALLING, RINGING, IN_CALL }
    private State state = State.IDLE;

    private UaUserLayer userLayer;
    private UaTransportLayer transportLayer;

    private final static int CALLING_TIMEOUT_SECONDS = 30;
    private Runnable callingTimeout = () -> {
        try {
            System.out.println("Call timeout reached, going back to IDLE");
            state = State.IDLE;
        } catch (Exception e) {
            System.err.println("Error in calling timeout: " + e.getMessage());
            e.printStackTrace();
        }
    };

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> callingTimeoutFuture;

    public UaTransactionLayer(int listenPort, String proxyAddress, int proxyPort, UaUserLayer userLayer)
            throws SocketException {
        this.userLayer = userLayer;
        this.transportLayer = new UaTransportLayer(listenPort, proxyAddress, proxyPort, this);
    }

    public void onMessageReceived(SIPMessage sipMessage) throws IOException {
        if (sipMessage instanceof InviteMessage) {
            InviteMessage inviteMessage = (InviteMessage) sipMessage;
            SIPMessage response;
            switch (state) {
                case IDLE:
                    response = inviteMessage.createTryingResponse();
                    state = State.RINGING;
                    callingTimeoutFuture = executorService.schedule(callingTimeout, CALLING_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    userLayer.onInviteReceived(inviteMessage);
                    break;
                case CALLING:
                case RINGING:
                case IN_CALL:
                    System.err.println("Busy state " + state + ", sending 486 Busy Here");
                    response = inviteMessage.createBusyHereResponse();
                    break;
                default:
                    response = inviteMessage.createNotFoundResponse();
                    System.err.println("Unexpected message at state " + state + ", throwing away");
                    System.err.println("Message: " + sipMessage);
                    break;
                }
                transportLayer.sendToProxy(response);
        } else if (isResponseToRegister(sipMessage)) {
            if (sipMessage instanceof OKMessage) {
                userLayer.onRegisterResponse(sipMessage);
            } else if (sipMessage instanceof NotFoundMessage) {
                userLayer.onRegisterNotFoundResponse(sipMessage);
            }
        } else if (isResponseToInvite(sipMessage)) {
            if (sipMessage instanceof OKMessage) {
                state = State.IN_CALL;
                userLayer.onInviteOKResponse(sipMessage);
            } else if (sipMessage instanceof NotFoundMessage) {
                userLayer.onInviteNotFoundResponse((NotFoundMessage) sipMessage);
                state = State.IDLE;
            } else if (sipMessage instanceof BusyHereMessage) {
                userLayer.onInviteBusyHereResponse((BusyHereMessage) sipMessage);
                state = State.IDLE;
            }
        } else if (sipMessage instanceof ByeMessage) {
            System.out.println("UA received BYE message");
            state = State.IDLE;
        } else {
            System.err.println("Unexpected message (not instance of InviteMessage or REGISTER response), throwing away");
            System.err.println("Message: " + sipMessage);
        }
    }

    private void sendMessage(SIPMessage message) throws IOException {
        transportLayer.sendToProxy(message);
    }

    private boolean isResponseToRegister(SIPMessage sipMessage) {
        String cSeqStr = sipMessage.getcSeqStr();
        return cSeqStr != null && "REGISTER".equalsIgnoreCase(cSeqStr);
    }

    private boolean isResponseToInvite(SIPMessage sipMessage) {
        String cSeqStr = sipMessage.getcSeqStr();
        return cSeqStr != null && "INVITE".equalsIgnoreCase(cSeqStr);
    }

    public void startListeningNetwork() {
        transportLayer.startListening();
    }

    public void call(InviteMessage inviteMessage) throws IOException {
        sendMessage(inviteMessage);
    }

    public void register(RegisterMessage registerMessage) throws IOException {
        sendMessage(registerMessage);
    }

    public void sendBye(ByeMessage byeMessage) throws IOException {
        sendMessage(byeMessage);
    }

    public void terminate() {
        transportLayer.terminate();
    }
}
