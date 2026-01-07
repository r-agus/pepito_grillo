package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.RequestTimeoutMessage;
import mensajesSIP.RingingMessage;
import mensajesSIP.SIPException;
import mensajesSIP.SIPMessage;
import mensajesSIP.TryingMessage;

public class ProxyUserLayer {
    private class Registration {
        String user;
        String ip;
        int port;
        long expiresAt; // Millis     
        
        Registration(String user, String ip, int port, long expiresAt) {
            this.user = user;
            this.ip = ip;
            this.port = port;
            this.expiresAt = expiresAt;
        }

        @Override
        public String toString() {
            return "Registration{" +
                    "user='" + user + '\'' +
                    ", ip='" + ip + '\'' +
                    ", port=" + port +
                    ", expiresAt=" + expiresAt +
                    '}';
        }
    }
    
    private class Call {
        Registration caller;
        Registration callee;
        String callId;
        InviteMessage inviteMessage;
        
        Call(Registration caller, Registration callee, String callId, InviteMessage inviteMessage) {
            this.caller = caller;
            this.callee = callee;
            this.callId = callId;
            this.inviteMessage = inviteMessage;
        }
    }
    
    // Changed to Map to support multiple concurrent calls
    private final Map<String, Call> activeCalls = new ConcurrentHashMap<>();

    private final ProxyTransactionLayer transactionLayer;
    private final Set<String> allowedUsers = Set.of("alice", "bob", "charlie");
    private final Map<String, Registration> registeredUsers = new HashMap<>(); 

    private boolean DEBUG = false;

    public ProxyUserLayer(int listenPort) throws SocketException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
    }

    public void setDebug(boolean debug) { this.DEBUG = debug; }

    public void onRegisterReceived(RegisterMessage registerMessage) throws IOException, SIPException {
        String fromName = registerMessage.getFromName().toLowerCase();
        String address = registerMessage.getVias().get(0).split(":")[0];
        int port = Integer.parseInt(registerMessage.getVias().get(0).split(":")[1]);

        SIPMessage response;

        if (!allowedUsers.contains(fromName)) {
            System.err.println("User " + fromName + " is not allowed to register.");
            response = registerMessage.createNotFoundResponse();
        } else {
            response = registerMessage.createOKResponse();
            Registration registration = new Registration(fromName, address, port,
                    System.currentTimeMillis() + registerMessage.getExpires());
            registeredUsers.put(fromName, registration);
        }
        if (DEBUG) System.out.println("[DEBUG] Registered users: " + registeredUsers.toString());
        transactionLayer.echoRegisterResponse(response, address, port);
    }

    public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
        String fromName = inviteMessage.getFromName().toLowerCase();
        String toName = inviteMessage.getToName().toLowerCase();
        String callId = inviteMessage.getCallId();
        
        // Check if both users are registered
        boolean usersOk = areUsersRegistered(Arrays.asList(fromName, toName));

        ArrayList<String> vias = inviteMessage.getVias();
        String origin = vias.get(0);
        String[] originParts = origin.split(":");
        String originAddress = originParts[0];
        int originPort = Integer.parseInt(originParts[1]);
        
        if (!usersOk) {
            // Send 404 to caller
            SIPMessage notFound = inviteMessage.createNotFoundResponse();
            transactionLayer.sendResponse(notFound, originAddress, originPort);
            
            if (DEBUG) {
                if (!isUserRegisterd(fromName)) System.err.println("Caller " + fromName + " is not registered.");
                if (!isUserRegisterd(toName)) System.err.println("Callee " + toName + " is not registered.");
            }
            return;
        }

        // Store active call
        activeCalls.put(callId, new Call(registeredUsers.get(fromName), registeredUsers.get(toName), callId, inviteMessage));

        SIPMessage trying = inviteMessage.createTryingResponse();
        transactionLayer.sendResponse(trying, originAddress, originPort);

        // Forward INVITE to callee
        Registration calleeReg = registeredUsers.get(toName);
        transactionLayer.forwardInvite(inviteMessage, calleeReg.ip, calleeReg.port);
    }

    private void onInviteError(SIPMessage message, String callId) {
        Call call = activeCalls.get(callId);
        if (call != null) {
            InviteMessage inviteMessage = call.inviteMessage;
            ArrayList<String> vias = inviteMessage.getVias();
            String origin = vias.get(0);
            String[] originParts = origin.split(":");
            String originAddress = originParts[0];
            int originPort = Integer.parseInt(originParts[1]);

            try {
                SIPMessage response;
                if (message instanceof BusyHereMessage) {
                    response = inviteMessage.createBusyHereResponse();
                } else {
                    response = inviteMessage.createNotFoundResponse();
                }
                transactionLayer.sendResponse(response, originAddress, originPort);
            } catch (IOException e) {
                System.err.println("Failed to send response to caller: " + e.getMessage());
                e.printStackTrace();
            }
            activeCalls.remove(callId);
        }
    }

    public void onInviteNotFoundReceived(NotFoundMessage sipMessage) {
        onInviteError(sipMessage, sipMessage.getCallId());
    }

    public void onInviteBusyHereReceived(BusyHereMessage sipMessage) {
        onInviteError(sipMessage, sipMessage.getCallId());
    }

    public void onTryingReceived(TryingMessage msg) {
        forwardResponseToCaller(msg, msg.getCallId());
    }

    public void onRingingReceived(RingingMessage msg) {
        forwardResponseToCaller(msg, msg.getCallId());
    }

    public void onOKReceived(OKMessage msg) {
        System.out.println("ProxyUserLayer: onOKReceived called.");
        forwardResponseToCaller(msg, msg.getCallId());
    }

    public void onRequestTimeoutReceived(RequestTimeoutMessage msg) {
        forwardResponseToCaller(msg, msg.getCallId());
        activeCalls.remove(msg.getCallId());
    }

    private void forwardResponseToCaller(SIPMessage msg, String callId) {
        Call call = activeCalls.get(callId);
        if (call != null) {
            try {
                System.out.println("Forwarding response to " + call.caller.user + " at " + call.caller.port);
                transactionLayer.sendResponse(msg, call.caller.ip, call.caller.port);
            } catch (IOException e) {
                System.err.println("Failed to forward response: " + e.getMessage());
            }
        } else {
             System.err.println("Cannot forward response: Call ID " + callId + " not found.");
        }
    }

    public void onByeReceived(ByeMessage sipMessage) {
        String callId = sipMessage.getCallId();
        Call call = activeCalls.get(callId);
        
        if (call != null) {
            String fromName = sipMessage.getFromName().toLowerCase();
            if (call.caller.user.equals(fromName)) {
                // Caller ended the call
                forwardBye(sipMessage, call.callee);
                System.out.println("Caller " + fromName + " ended the call.");
            } else if (call.callee.user.equals(fromName)) {
                // Callee ended the call
                forwardBye(sipMessage, call.caller);
                System.out.println("Callee " + fromName + " ended the call.");
            } else {
                System.err.println("Received BYE from unknown user: " + fromName);
            }
            activeCalls.remove(callId);
        } else {
            System.err.println("Received BYE but there is no active call for ID: " + callId);
        }
    }
    
    private void forwardBye(ByeMessage byeMessage, Registration target) {
        try {
            transactionLayer.sendResponse(byeMessage, target.ip, target.port);
        } catch (IOException e) {
            System.err.println("Failed to forward BYE: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean areUsersRegistered(List<String> users) {
        for (String user : users) {
             // Debug print
             if (DEBUG) System.out.println("Checking registration for: " + user + " -> " + isUserRegisterd(user));
            if (!isUserRegisterd(user)) return false;
        }
        return true; 
    }

    private boolean isUserRegisterd(String user) {
        Registration registration = registeredUsers.get(user.toLowerCase());
        return registration != null && registration.expiresAt >= System.currentTimeMillis();
    }

    public void startListening() {
        transactionLayer.startListening();
    }
}
