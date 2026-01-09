package proxy;

import common.FindMyIPv4;
import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mensajesSIP.ACKMessage;
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
    
    private final ProxyTransactionLayer transactionLayer;
    private final Set<String> allowedUsers = Set.of("alice", "bob");
    private final Map<String, Registration> registeredUsers = new HashMap<>(); // To store registered users by their fromName without duplicates

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
    private Optional<Call> currentCall = Optional.empty(); // Use Optional to represent absence of a call

    private final boolean looseRouting;
    private final String proxyAddress;
    private final int proxyPort;

    private boolean DEBUG = false;

    private final boolean forceRecordRoute;

    public ProxyUserLayer(int listenPort, boolean looseRouting, boolean forceRecordRoute) throws SocketException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
        this.looseRouting = looseRouting;
        this.forceRecordRoute = forceRecordRoute;
        this.proxyPort = listenPort;
        String addr = "127.0.0.1";
        try {
            addr = FindMyIPv4.findMyIPv4Address().getHostAddress();
        } catch (Exception e) {
            System.err.println("Could not determine local IP, using 127.0.0.1");
        }
        this.proxyAddress = addr;
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
        System.out.println("[PROXY] onInviteReceived from=" + fromName + " to=" + toName + " callId=" + callId + " usersRegistered=" + registeredUsers.keySet() + " loose=" + looseRouting + " force=" + forceRecordRoute);
        
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

        if (currentCall.isPresent() && !currentCall.get().callId.equals(callId)) {
            // Send 503 service unavailable to caller
            SIPMessage serviceUnavailable = inviteMessage.createServiceUnavailableResponse();
            transactionLayer.sendResponse(serviceUnavailable, originAddress, originPort);
            return;
        }

        if (looseRouting && (usersOk || forceRecordRoute)) {
            // add Record-Route so future in-dialog requests can be routed via this proxy
            String rr = proxyAddress + ":" + proxyPort;
            inviteMessage.setRecordRoute(rr);
            System.out.println("[PROXY] Added Record-Route: " + rr + " (usersOk=" + usersOk + ", force=" + forceRecordRoute + ")");
        }

        currentCall = Optional.of(new Call(registeredUsers.get(fromName), registeredUsers.get(toName), callId, inviteMessage));

        SIPMessage trying = inviteMessage.createTryingResponse();
        transactionLayer.sendResponse(trying, originAddress, originPort);

        // Forward INVITE to callee
        Registration calleeReg = registeredUsers.get(toName);
        inviteMessage.addVia(transactionLayer.getListeningAddress() + ":" + transactionLayer.getListeningPort());
        transactionLayer.forwardInvite(inviteMessage, calleeReg.ip, calleeReg.port);
    }

    public void onInviteOKReceived(OKMessage okMessage) {
        // Forward OK back to caller, and if loose routing is enabled add record-route so caller knows
        if (!currentCall.isPresent()) return;
        Call call = currentCall.get();
        ArrayList<String> vias = call.inviteMessage.getVias();
        String origin = vias.get(0);
        String[] originParts = origin.split(":");
        String callerAddress = originParts[0];
        int callerPort = Integer.parseInt(originParts[1]);

        if (looseRouting) {
            // ensure caller receives the record-route that was inserted in the INVITE
            okMessage.setRecordRoute(call.inviteMessage.getRecordRoute());
        }

        try {
            transactionLayer.sendResponse(okMessage, callerAddress, callerPort);
        } catch (IOException e) {
            System.err.println("Failed to forward OK to caller: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onAckReceived(ACKMessage ackMessage) {
        String fromName = ackMessage.getFromName().toLowerCase();
        if (!currentCall.isPresent()) return;
        Call call = currentCall.get();
        try {
            if (call.caller.user.equals(fromName)) {
                // ACK from caller -> forward to callee
                Registration calleeReg = call.callee;
                if (looseRouting) ackMessage.setRoute(null); // proxies remove Route content
                transactionLayer.sendResponse(ackMessage, calleeReg.ip, calleeReg.port);
            } else if (call.callee.user.equals(fromName)) {
                // ACK from callee -> forward to caller
                ArrayList<String> vias = call.inviteMessage.getVias();
                String origin = vias.get(0);
                String[] originParts = origin.split(":");
                String callerAddress = originParts[0];
                int callerPort = Integer.parseInt(originParts[1]);
                if (looseRouting) ackMessage.setRoute(null);
                transactionLayer.sendResponse(ackMessage, callerAddress, callerPort);
            } else {
                System.err.println("Received ACK from unknown user: " + fromName);
            }
        } catch (IOException e) {
            System.err.println("Failed to forward ACK: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void onInviteError(SIPMessage message, String callId) {
        if (currentCall.isPresent() && currentCall.get().callId.equals(callId)) {
            Call call = currentCall.get();
            InviteMessage inviteMessage = call.inviteMessage;
            ArrayList<String> vias = inviteMessage.getVias();
            
            // If the first Via is the Proxy itself, skip it to find the caller
            int viaIndex = 0;
            String origin = vias.get(viaIndex);

            // Check if origin corresponds to this Proxy
            String mySign = transactionLayer.getListeningAddress() + ":" + transactionLayer.getListeningPort();
            if (origin.equals(mySign) && vias.size() > 1) {
                 viaIndex = 1;
                 origin = vias.get(viaIndex);
            }

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
            this.currentCall = Optional.empty();
        }
    }

    public void onInviteNotFoundReceived(NotFoundMessage sipMessage) {
        onInviteError(sipMessage, sipMessage.getCallId());
    }

    public void onInviteBusyHereReceived(BusyHereMessage sipMessage) {
        onInviteError(sipMessage, sipMessage.getCallId());
    }

    private void calleeEndedCall(ByeMessage byeMessage) {
        // The byeMessage comes from the callee, forward it to the caller
        if (!currentCall.isPresent()) return; // No active call
        Call call = currentCall.get();
        InviteMessage inviteMessage = call.inviteMessage;
        ArrayList<String> vias = inviteMessage.getVias();
        String origin = vias.get(0);
        String[] originParts = origin.split(":");
        String callerAddress = originParts[0];
        int callerPort = Integer.parseInt(originParts[1]);
        try {
            if (looseRouting) byeMessage.setRoute(null);
            transactionLayer.sendResponse(byeMessage, callerAddress, callerPort);
        } catch (IOException e) {
            System.err.println("Failed to forward BYE to caller: " + e.getMessage());
            e.printStackTrace();
        }
        
        this.currentCall = Optional.empty();
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
        this.currentCall = Optional.empty();
    }

    private void callerEndedCall(ByeMessage byeMessage) {
        // The byeMessage comes from the caller, forward it to the callee
        if (!currentCall.isPresent()) return; // No active call
        Call call = currentCall.get();
        Registration calleeReg = call.callee;
        try {
            if (looseRouting) byeMessage.setRoute(null);
            transactionLayer.sendResponse(byeMessage, calleeReg.ip, calleeReg.port);
        } catch (IOException e) {
            System.err.println("Failed to forward BYE to callee: " + e.getMessage());
            e.printStackTrace();
        }
        this.currentCall = Optional.empty();
    }

    private void forwardResponseToCaller(SIPMessage msg, String callId) {
        Call call = currentCall.isPresent() ? currentCall.get() : null;
        if (call != null && call.callId.equals(callId)) {
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
        Call call = currentCall.isPresent() ? currentCall.get() : null;
        
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
            this.currentCall = Optional.empty();
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
