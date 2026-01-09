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
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.SIPMessage;

public class UaTransactionLayer {
    private enum State { IDLE, CALLING, RINGING, IN_CALL }
    private State state = State.IDLE;
    private InviteMessage currentInvite;

    private UaUserLayer userLayer;
    private UaTransportLayer transportLayer;

    private final static int CALLING_TIMEOUT_SECONDS = 10;
    private Runnable callingTimeout = () -> {
        try {
            if (state == State.RINGING && currentInvite != null) {
                System.out.println("Call timeout (10s), sending 408 Request Timeout");
                RequestTimeoutMessage response = currentInvite.createRequestTimeoutResponse();
                try {
                    transportLayer.sendToProxy(response);
                } catch (IOException e) {
                    System.err.println("Failed to send 408: " + e.getMessage());
                }
            } else if (state == State.CALLING) {
                System.out.println("Call timeout (10s). No response.");
                // For Client Transaction, we simulate receiving a Timeout or just reset.
            }
            state = State.IDLE;
            currentInvite = null;
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
                    response = inviteMessage.createRingingResponse();
                    state = State.RINGING;
                    this.currentInvite = inviteMessage;
                    callingTimeoutFuture = executorService.schedule(callingTimeout, CALLING_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                    
                    // Send 180 Ringing and notify user
                    transportLayer.sendToProxy(response);
                    userLayer.onInviteReceived(inviteMessage);
                    return;
                case CALLING:
                case RINGING:
                case IN_CALL:
                    if (userLayer.isDebug() || Boolean.getBoolean("debug")) System.err.println("Busy state " + state + ", sending 486 Busy Here");
                    // Important: User logs expect "(busy)" or "Busy" to be part of normal output for verification
                    response = inviteMessage.createBusyHereResponse();
                    transportLayer.sendToProxy(response);
                    return;
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
                if (callingTimeoutFuture != null) callingTimeoutFuture.cancel(false);
                state = State.IN_CALL;
                userLayer.onInviteOKResponse(sipMessage);
            } else if (sipMessage instanceof NotFoundMessage) {
                if (callingTimeoutFuture != null) callingTimeoutFuture.cancel(false);
                userLayer.onInviteNotFoundResponse((NotFoundMessage) sipMessage);
                state = State.IDLE;
            } else if (sipMessage instanceof BusyHereMessage) {
                if (callingTimeoutFuture != null) callingTimeoutFuture.cancel(false);
                userLayer.onInviteBusyHereResponse((BusyHereMessage) sipMessage);
                state = State.IDLE;
            } else if (sipMessage instanceof RingingMessage) {
                System.out.println("Remote is ringing (180).");
            } else if (sipMessage instanceof RequestTimeoutMessage) {
                if (callingTimeoutFuture != null) callingTimeoutFuture.cancel(false);
                System.out.println("Call timed out (408).");
                state = State.IDLE;
            }
        } else if (sipMessage instanceof ByeMessage) {
            state = State.IDLE;
            userLayer.onByeReceived((ByeMessage) sipMessage);
            if (callingTimeoutFuture != null) callingTimeoutFuture.cancel(false);
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
        state = State.CALLING;
        this.currentInvite = inviteMessage;
        callingTimeoutFuture = executorService.schedule(callingTimeout, CALLING_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        sendMessage(inviteMessage);
    }
    
    public void answerCall(SDPMessage sdp, String contact) throws IOException {
        System.out.println("answerCall called. State: " + state);
        if (state == State.RINGING && currentInvite != null) {
            OKMessage response = currentInvite.createOKResponse();
            response.setContact(contact);
            response.setSdp(sdp);
            response.setContentLength(sdp.toStringMessage().getBytes().length);
            state = State.IN_CALL;
            System.out.println("Sending 200 OK Response...");
            sendMessage(response);
            System.out.println("200 OK Response sent.");
        } else {
            System.err.println("Cannot answer call: State is " + state);
        }
    }

    public void register(RegisterMessage registerMessage) throws IOException {
        sendMessage(registerMessage);
    }

    public void sendBye(ByeMessage byeMessage) throws IOException {
        state = State.IDLE;
        sendMessage(byeMessage);
    }

    // Send arbitrary message to proxy
    public void sendMessageToProxy(SIPMessage message) throws IOException {
        transportLayer.sendToProxy(message);
    }

    // Send arbitrary message directly to an IP:port
    public void sendMessageToAddress(SIPMessage message, String address, int port) throws IOException {
        transportLayer.send(message, address, port);
    }

    public void terminate() {
        if (callingTimeoutFuture != null && !callingTimeoutFuture.isDone()) {
            callingTimeoutFuture.cancel(false);
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        transportLayer.terminate();
    }
}
