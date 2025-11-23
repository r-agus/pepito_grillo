package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;

import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPException;
import mensajesSIP.SIPMessage;

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

    private Optional<String> currentCallId = Optional.empty(); // Use Optional to represent absence of a call

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
            return;
        }

        if (currentCallId.isPresent() && !currentCallId.get().equals(callId)) {
            // Send 503 service unavailable to caller
            SIPMessage serviceUnavailable = inviteMessage.createServiceUnavailableResponse();
            transactionLayer.sendResponse(serviceUnavailable, originAddress, originPort);
            return;
        }

        currentCallId = Optional.of(callId);

        SIPMessage trying = inviteMessage.createTryingResponse();
        transactionLayer.sendResponse(trying, originAddress, originPort);

        // Forward INVITE to callee
        Registration calleeReg = registeredUsers.get(toName);
        transactionLayer.forwardInvite(inviteMessage, calleeReg.ip, calleeReg.port);
    }

    public void onByeReceived() {
        this.currentCallId = Optional.empty();
    }

    private boolean areUsersRegistered(List<String> users) {
        for (String user : users) {
            if (!isUserRegisterd(user)) return false;
        }
        return true; // All users are registered
    }

    private boolean isUserRegisterd(String user) {
        Registration registration = registeredUsers.get(user.toLowerCase());
        return registration != null && registration.expiresAt >= System.currentTimeMillis();
    }

    public void startListening() {
        transactionLayer.startListening();
    }
}
