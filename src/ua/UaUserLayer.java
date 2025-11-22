package ua;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;
import java.util.UUID;

import common.FindMyIPv4;
import mensajesSIP.InviteMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.SIPMessage;

public class UaUserLayer {
    private static final int IDLE = 0;
    private int state = IDLE;

    public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(
            Arrays.asList(new Integer[] { 96, 97, 98 }));

    private UaTransactionLayer transactionLayer;

    private String myAddress = FindMyIPv4.findMyIPv4Address().getHostAddress();
    private int rtpPort;
    private int listenPort;
    private final String sipUserUri;
    private final String sipUserName;
    private final String sipUserDomain;
    private final int registerExpires;
    private final String proxyAddress;

    private RegisterMessage lastRegisterMessage;
    private volatile boolean registerResponseReceived = false;
    private Thread registerRetryThread;

    private Process vitextClient = null;
    private Process vitextServer = null;

    private volatile boolean shouldExit = false;

    public UaUserLayer(String sipUser, int listenPort, String proxyAddress, int proxyPort, int resendTime)
            throws SocketException, UnknownHostException {
        this.transactionLayer = new UaTransactionLayer(listenPort, proxyAddress, proxyPort, this);
        this.listenPort = listenPort;
        this.rtpPort = listenPort + 1;
        this.proxyAddress = proxyAddress;
        this.sipUserUri = normalizeSipUri(sipUser);
        this.sipUserName = extractUser(this.sipUserUri);
        this.sipUserDomain = extractDomain(this.sipUserUri);
        this.registerExpires = resendTime;
    }

    public void registerWithProxy() throws IOException {
        RegisterMessage registerMessage = new RegisterMessage();
        registerMessage.setDestination("sip:" + sipUserDomain);
        registerMessage.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
        registerMessage.setMaxForwards(70);
        registerMessage.setToName(sipUserName);
        registerMessage.setToUri(sipUserUri);
        registerMessage.setFromName(sipUserName);
        registerMessage.setFromUri(sipUserUri);
        registerMessage.setCallId(UUID.randomUUID().toString());
        registerMessage.setcSeqNumber("1");
        registerMessage.setcSeqStr("REGISTER");
        registerMessage.setContact(buildContactUri());
        registerMessage.setExpires(registerExpires);
        registerMessage.setContentLength(0);

        this.lastRegisterMessage = registerMessage;
        this.registerResponseReceived = false;

        transactionLayer.register(registerMessage);
        System.out.println("REGISTER sent for " + sipUserUri + " (Expires=" + registerExpires + ")");
        startRegisterRetryLoop();
    }

    private synchronized void startRegisterRetryLoop() {
        if (registerRetryThread != null && registerRetryThread.isAlive()) {
            return;
        }

        registerRetryThread = new Thread(() -> {
            while (!registerResponseReceived) {
                try {
                    Thread.sleep(2000);
                    if (registerResponseReceived) {
                        break;
                    }
                    if (lastRegisterMessage != null) {
                        System.out.println("No REGISTER response received, resending...");
                        transactionLayer.register(lastRegisterMessage);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    System.err.println("Failed to resend REGISTER: " + e.getMessage());
                }
            }
        });

        registerRetryThread.setDaemon(true);
        registerRetryThread.start();
    }

    public void onRegisterResponse(SIPMessage sipMessage) {
        registerResponseReceived = true;
        if (registerRetryThread != null) {
            registerRetryThread.interrupt();
        }
        System.out.println("Received response for REGISTER: " + sipMessage.getClass().getSimpleName());
    }

    public void onNotFoundResponse(SIPMessage sipMessage) {
        registerResponseReceived = true;
        if (registerRetryThread != null) {
            registerRetryThread.interrupt();
        }
        shouldExit = true;
        terminate("Received 404 Not Found for REGISTER");
    }

    public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
        System.out.println("Received INVITE from " + inviteMessage.getFromName());
        runVitextServer();
    }

    public void startListeningNetwork() {
        transactionLayer.startListeningNetwork();
    }

    public void startListeningKeyboard() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (!shouldExit) {
                prompt();
                String line = scanner.nextLine();
                if (!line.isEmpty()) {
                    command(line);
                }
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }

    private void prompt() {
        System.out.println("");
        switch (state) {
            case IDLE:
                promptIdle();
                break;
            default:
                throw new IllegalStateException("Unexpected state: " + state);
        }
        System.out.print("> ");
    }

    private void promptIdle() {
        System.out.println("INVITE xxx");
    }

    private void command(String line) throws IOException {
        if (line.startsWith("INVITE")) {
            commandInvite(line);
        } else {
            System.out.println("Bad command");
        }
    }

    private void commandInvite(String line) throws IOException {
        stopVitextServer();
        stopVitextClient();

        System.out.println("Inviting...");

        runVitextClient();

        String callId = UUID.randomUUID().toString();

        SDPMessage sdpMessage = new SDPMessage();
        sdpMessage.setIp(this.myAddress);
        sdpMessage.setPort(this.rtpPort);
        sdpMessage.setOptions(RTPFLOWS);

        InviteMessage inviteMessage = new InviteMessage();
        inviteMessage.setDestination("sip:bob@SMA");
        inviteMessage.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
        inviteMessage.setMaxForwards(70);
        inviteMessage.setToName("Bob");
        inviteMessage.setToUri("sip:bob@SMA");
        inviteMessage.setFromName("Alice");
        inviteMessage.setFromUri("sip:alice@SMA");
        inviteMessage.setCallId(callId);
        inviteMessage.setcSeqNumber("1");
        inviteMessage.setcSeqStr("INVITE");
        inviteMessage.setContact(myAddress + ":" + listenPort);
        inviteMessage.setContentType("application/sdp");
        inviteMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);
        inviteMessage.setSdp(sdpMessage);

        transactionLayer.call(inviteMessage);
    }

    private void runVitextClient() throws IOException {
        vitextClient = Runtime.getRuntime().exec("xterm -e vitext/vitextclient -p 5000 239.1.2.3");
    }

    private void stopVitextClient() {
        if (vitextClient != null) {
            vitextClient.destroy();
        }
    }

    private void runVitextServer() throws IOException {
        vitextServer = Runtime.getRuntime()
                .exec("xterm -iconic -e vitext/vitextserver -r 10 -p 5000 vitext/1.vtx 239.1.2.3");
    }

    private void stopVitextServer() {
        if (vitextServer != null) {
            vitextServer.destroy();
        }
    }

    private String normalizeSipUri(String sipUser) {
        if (sipUser == null || sipUser.trim().isEmpty()) {
            throw new IllegalArgumentException("sip_user must not be empty");
        }
        String trimmed = sipUser.trim();
        String normalized = trimmed.startsWith("sip:") ? trimmed : "sip:" + trimmed;
        if (!normalized.contains("@")) {
            normalized = normalized + "@" + proxyAddress;
        }
        return normalized;
    }

    private String extractUser(String sipUri) {
        String bare = stripScheme(sipUri);
        int atIdx = bare.indexOf('@');
        return atIdx >= 0 ? bare.substring(0, atIdx) : bare;
    }

    private String extractDomain(String sipUri) {
        String bare = stripScheme(sipUri);
        int atIdx = bare.indexOf('@');
        if (atIdx >= 0 && atIdx < bare.length() - 1) {
            return bare.substring(atIdx + 1);
        }
        return proxyAddress;
    }

    private String stripScheme(String sipUri) {
        return sipUri.startsWith("sip:") ? sipUri.substring(4) : sipUri;
    }

    private String buildContactUri() {
        return extractUser(sipUserUri) + "@" + myAddress + ":" + listenPort;
    }

    private void terminate(String reason) {
        System.err.println("Terminating UA: " + reason);
        terminate();
    }

    private void terminate() {
        transactionLayer.terminate();
        stopVitextClient();
        stopVitextServer();
    }
}
