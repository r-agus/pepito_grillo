package proxy;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SIPException;
import mensajesSIP.SIPMessage;

public class ProxyUserLayer {
    private final ProxyTransactionLayer transactionLayer;
    private final Set<String> allowedUsers = Set.of("alice", "bob");
    private final Set<String> registeredUsers = new HashSet<>(); // To store registered users by their fromName without duplicates


    public ProxyUserLayer(int listenPort) throws SocketException {
        this.transactionLayer = new ProxyTransactionLayer(listenPort, this);
    }

    public void onRegisterReceived(RegisterMessage registerMessage) throws IOException, SIPException {
        String fromName = registerMessage.getFromName().toLowerCase();
        String address = registerMessage.getVias().get(0).split(":")[0];
        int port = Integer.parseInt(registerMessage.getVias().get(0).split(":")[1]);

        SIPMessage response;

        if (registeredUsers.contains(fromName)) {
            // User is already registered. Ignore the REGISTER request.
            System.out.println("User " + fromName + " is already registered.");
            return;
        }

        if (!allowedUsers.contains(fromName)) {
            System.out.println("User " + fromName + " is not allowed to register.");
            response = registerMessage.createNotFoundResponse();
        } else {
            System.out.println("Received REGISTER Message from " + fromName);
            response = registerMessage.createOKResponse();
            registeredUsers.add(fromName);
        }

        transactionLayer.echoRegisterResponse(response, address, port);
    }

    public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
        System.out.println("Received INVITE from " + inviteMessage.getFromName());
        ArrayList<String> vias = inviteMessage.getVias();
        String origin = vias.get(0);
        String[] originParts = origin.split(":");
        String originAddress = originParts[0];
        int originPort = Integer.parseInt(originParts[1]);
        transactionLayer.echoInvite(inviteMessage, originAddress, originPort);
    }

    public void startListening() {
        transactionLayer.startListening();
    }
}
