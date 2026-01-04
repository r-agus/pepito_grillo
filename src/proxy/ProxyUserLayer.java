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
import java.io.InputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPException;
import mensajesSIP.SIPMessage;
import mensajesSIP.TryingMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.OKMessage;

import sipServlet.SIPServletInterface;
import sipServlet.SipServletRequest;
import sipServlet.Users;
import sipServlet.User;
import sipServlet.UsersServletReader;

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
    private final Set<String> allowedUsers = Set.of("alice", "bob", "mario", "boss");
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

    private boolean DEBUG = false;
    
    private final Map<String, String> userServlets = new HashMap<>();

    private void loadUserServlets() {
        try (InputStream xml = UsersServletReader.class.getResourceAsStream("users.xml")) {
            if (xml == null) {
                System.err.println("users.xml not found");
                return;
            }
            JAXBContext jaxbContext = JAXBContext.newInstance(Users.class);
            Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
            Users users = (Users) jaxbUnmarshaller.unmarshal(xml);
            for (User user : users.getListUsers()) {
                String id = user.getId();
                String username = extractUserFromUri(id);
                if (username != null) {
                    userServlets.put(username.toLowerCase(), user.getServletClass().getName());
                }
            }
            System.out.println("Loaded servlets: " + userServlets);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private String extractUserFromUri(String uri) {
        if (uri.startsWith("sip:")) {
            int atIndex = uri.indexOf('@');
            if (atIndex > 4) {
                return uri.substring(4, atIndex);
            }
        }
        return null;
    }

    public ProxyUserLayer(int listenPort) throws SocketException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
        loadUserServlets();
    }

    public void setDebug(boolean debug) { this.DEBUG = debug; }

    private SIPMessage createResponse(InviteMessage invite, int statusCode) {
        switch (statusCode) {
            case 100: return invite.createTryingResponse();
            case 200: return invite.createOKResponse();
            case 404: return invite.createNotFoundResponse();
            case 486: return invite.createBusyHereResponse();
            case 503: return invite.createServiceUnavailableResponse();
            default: return null;
        }
    }

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
        
        // Servlet Logic
        String servletClassName = userServlets.get(toName);
        if (servletClassName == null) {
            servletClassName = userServlets.get(fromName);
        }

        if (servletClassName != null) {
            try {
                Class<?> clazz = Class.forName(servletClassName);
                SIPServletInterface servlet = (SIPServletInterface) clazz.getDeclaredConstructor().newInstance();
                SipServletRequest request = new SipServletRequest(inviteMessage);
                servlet.doInvite(request);

                ArrayList<String> vias = inviteMessage.getVias();
                String origin = vias.get(0);
                String[] originParts = origin.split(":");
                String originAddress = originParts[0];
                int originPort = Integer.parseInt(originParts[1]);

                if (request.isResponseSent()) {
                    int statusCode = request.getResponseStatusCode();
                    SIPMessage response = createResponse(inviteMessage, statusCode);
                    if (response != null) {
                        transactionLayer.sendResponse(response, originAddress, originPort);
                    } else {
                        System.err.println("Unsupported status code from servlet: " + statusCode);
                    }
                    return;
                } else if (request.isProxyActionTaken()) {
                    String proxyUri = request.getProxyToURI();
                    String targetUser = extractUserFromUri(proxyUri);
                    if (targetUser == null) targetUser = proxyUri; 
                    
                    Registration targetReg = registeredUsers.get(targetUser.toLowerCase());
                    if (targetReg != null) {
                        SIPMessage trying = inviteMessage.createTryingResponse();
                        transactionLayer.sendResponse(trying, originAddress, originPort);
                        
                        transactionLayer.forwardInvite(inviteMessage, targetReg.ip, targetReg.port);
                        currentCall = Optional.of(new Call(registeredUsers.get(fromName), targetReg, callId, inviteMessage));
                    } else {
                        SIPMessage notFound = inviteMessage.createNotFoundResponse();
                        transactionLayer.sendResponse(notFound, originAddress, originPort);
                    }
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
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

        currentCall = Optional.of(new Call(registeredUsers.get(fromName), registeredUsers.get(toName), callId, inviteMessage));

        SIPMessage trying = inviteMessage.createTryingResponse();
        transactionLayer.sendResponse(trying, originAddress, originPort);

        // Forward INVITE to callee
        Registration calleeReg = registeredUsers.get(toName);
        transactionLayer.forwardInvite(inviteMessage, calleeReg.ip, calleeReg.port);
    }

    private void onInviteError(SIPMessage message, String callId) {
        if (currentCall.isPresent() && currentCall.get().callId.equals(callId)) {
            Call call = currentCall.get();
            InviteMessage inviteMessage = call.inviteMessage;
            ArrayList<String> vias = inviteMessage.getVias();
            String origin = vias.get(0);
            String[] originParts = origin.split(":");
            String originAddress = originParts[0];
            int originPort = Integer.parseInt(originParts[1]);

            try {
                SIPMessage notFoundResponse = inviteMessage.createNotFoundResponse();
                transactionLayer.sendResponse(notFoundResponse, originAddress, originPort);
            } catch (IOException e) {
                System.err.println("Failed to send NOT FOUND response to caller: " + e.getMessage());
                e.printStackTrace();
            }
            currentCall = Optional.empty();
        }
    }

    public void onInviteNotFoundReceived(NotFoundMessage sipMessage) {
        String callId = sipMessage.getCallId();
        onInviteError(sipMessage, callId);
    }

    public void onInviteBusyHereReceived(BusyHereMessage sipMessage) {
        String callId = sipMessage.getCallId();
        onInviteError(sipMessage, callId);
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
            transactionLayer.sendResponse(byeMessage, callerAddress, callerPort);
        } catch (IOException e) {
            System.err.println("Failed to forward BYE to caller: " + e.getMessage());
            e.printStackTrace();
        }
        
        this.currentCall = Optional.empty();
    }

    private void callerEndedCall(ByeMessage byeMessage) {
        // The byeMessage comes from the caller, forward it to the callee
        if (!currentCall.isPresent()) return; // No active call
        Call call = currentCall.get();
        Registration calleeReg = call.callee;
        try {
            transactionLayer.sendResponse(byeMessage, calleeReg.ip, calleeReg.port);
        } catch (IOException e) {
            System.err.println("Failed to forward BYE to callee: " + e.getMessage());
            e.printStackTrace();
        }
        this.currentCall = Optional.empty();
    }

    public void onByeReceived(ByeMessage sipMessage) {
        String fromName = sipMessage.getFromName().toLowerCase();
        if (currentCall.isPresent()) {
            Call call = currentCall.get();
            if (call.caller.user.equals(fromName)) {
                // Caller ended the call
                callerEndedCall(sipMessage);
                System.out.println("Caller " + fromName + " ended the call.");
            } else if (call.callee.user.equals(fromName)) {
                // Callee ended the call
                System.out.println("Callee " + fromName + " ended the call.");
                calleeEndedCall(sipMessage);
            } else {
                System.err.println("Received BYE from unknown user: " + fromName);
            }
        } else {
            System.err.println("Received BYE but there is no active call.");
        }
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
