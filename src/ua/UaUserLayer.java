package ua;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import common.FindMyIPv4;
import common.TerminalLauncher;
import mensajesSIP.BusyHereMessage;
import mensajesSIP.ByeMessage;
import mensajesSIP.InviteMessage;
import mensajesSIP.NotFoundMessage;
import mensajesSIP.RegisterMessage;
import mensajesSIP.ACKMessage;
import mensajesSIP.OKMessage;
import mensajesSIP.SDPMessage;
import mensajesSIP.ServiceUnavailableMessage;
import mensajesSIP.SIPMessage;

public class UaUserLayer {
    private enum State { UNREGISTERED, REGISTERING, REGISTERED }
    private volatile State state = State.UNREGISTERED;

    public static final ArrayList<Integer> RTPFLOWS = new ArrayList<Integer>(
            Arrays.asList(new Integer[] { 96, 97, 98 }));

    private UaTransactionLayer transactionLayer;

    private String myAddress;
    private int rtpPort;
    private int listenPort;
    private final String sipUser;
    private final String sipUserUri;
    private final String sipUserName;
    private final String sipUserDomain;
    private final int registerExpires;
    private final String proxyAddress;

    private RegisterMessage lastRegisterMessage;
    private volatile boolean registerResponseReceived = false;
    private Thread registerRetryThread;
    
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> registrationTimeout, registrationRenewal;

    private boolean DEBUG = false;
    private static final boolean TEST_MODE = Boolean.getBoolean("testMode");
    
    private Runnable renewRegister = () -> {
        registerResponseReceived = false;
        try {
            startRegisterRetryLoop();
        } catch (Exception e) {
            System.err.println("Failed to restart REGISTER retry loop: " + e.getMessage());
        }
    };

    private Runnable registerExpired = () -> {
        this.state = State.UNREGISTERED;
        System.err.println("Registration expired.");
    };

    private InviteMessage lastInvite;
    private int globalCSeq = 1;

    private Process vitextClient = null;
    private Process vitextServer = null;
    private boolean amICaller = false;
    private volatile boolean shouldExit = false;

    public UaUserLayer(String sipUser, int listenPort, String proxyAddress, int proxyPort, int resendTime)
            throws SocketException, UnknownHostException {
        this.transactionLayer = new UaTransactionLayer(listenPort, proxyAddress, proxyPort, this);
        this.listenPort = listenPort;
        this.rtpPort = listenPort + 1;
        this.proxyAddress = proxyAddress;
        this.myAddress = resolveLocalAddress(proxyAddress, proxyPort);
        this.sipUser = sipUser;
        this.sipUserUri = normalizeSipUri(sipUser);
        this.sipUserName = extractUser(this.sipUserUri);
        this.sipUserDomain = extractDomain(this.sipUserUri);
        this.registerExpires = resendTime;
    }

    private String resolveLocalAddress(String proxyAddress, int proxyPort) throws SocketException, UnknownHostException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName(proxyAddress), proxyPort);
            InetAddress localAddress = socket.getLocalAddress();
            if (localAddress instanceof Inet4Address
                    && !localAddress.isLoopbackAddress()
                    && !localAddress.isAnyLocalAddress()) {
                return localAddress.getHostAddress();
            }
        }
        return FindMyIPv4.findMyIPv4Address().getHostAddress();
    }

    public void setDebug(boolean debug) { this.DEBUG = debug; }
    public boolean isDebug() { return DEBUG; }

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
        registerMessage.setcSeqNumber(String.valueOf(globalCSeq++));
        registerMessage.setcSeqStr("REGISTER");
        registerMessage.setContact(buildContactUri());
        registerMessage.setExpires(registerExpires);
        registerMessage.setContentLength(0);

        this.lastRegisterMessage = registerMessage;
        this.registerResponseReceived = false;

        startRegisterRetryLoop();
    }

    private synchronized void startRegisterRetryLoop() {
        if (registerRetryThread != null && registerRetryThread.isAlive()) {
            return;
        }
        
        registerRetryThread = new Thread(() -> {
            if (DEBUG) System.out.println("[DEBUG] Starting REGISTER retry loop...");
            this.state = State.REGISTERING;
            while (!registerResponseReceived) {
                try {
                    transactionLayer.register(lastRegisterMessage);
                    lastRegisterMessage.incrementCSeq();
                    Thread.sleep(2000);
                    if (registerResponseReceived) {
                        break;
                    }
                    if (lastRegisterMessage != null) {
                        System.out.println("No REGISTER response received, resending...");
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

    private void onRegister() {
        this.state = State.REGISTERED;

        // Schedule the REGISTER renewal before it expires
        if (registrationRenewal != null && !registrationRenewal.isDone()) {
            registrationRenewal.cancel(false);
        }
        long delaySeconds = (long) (registerExpires * 0.9);
        registrationRenewal = scheduler.schedule(renewRegister, delaySeconds, TimeUnit.MILLISECONDS);
        if (DEBUG) System.out.println("[DEBUG] Scheduled REGISTER renewal in " + delaySeconds + " ms.");

        if (registrationTimeout != null && !registrationTimeout.isDone()) {
            registrationTimeout.cancel(false);
        }
        registrationTimeout = scheduler.schedule(registerExpired, registerExpires, TimeUnit.MILLISECONDS);
        if (DEBUG) System.out.println("[DEBUG] Registration timeout reset. Will expire in " + registerExpires + " ms.");
    }

    public void onRegisterResponse(SIPMessage sipMessage) {
        registerResponseReceived = true;
        this.state = State.REGISTERED;
        if (registerRetryThread != null) {
            registerRetryThread.interrupt();
        }
        if (DEBUG) System.out.println("[DEBUG] Received response for REGISTER: " + sipMessage.getClass().getSimpleName());
        onRegister();
    }

    public void onRegisterNotFoundResponse(SIPMessage sipMessage) {
        registerResponseReceived = true;
        if (registerRetryThread != null) {
            registerRetryThread.interrupt();
        }
        shouldExit = true;
        terminate("Received 404 Not Found for REGISTER");
    }

    public void onInviteOKResponse(SIPMessage sipMessage) {
        if (DEBUG) System.out.println("[DEBUG] Received OK response for INVITE");
        OKMessage ok = (OKMessage) sipMessage;

        // If the OK carries Record-Route this means loose routing is active
        String recordRoute = ok.getRecordRoute();
        if (recordRoute != null) {
            // save recordRoute for in-dialog requests (BYE)
            if (this.lastInvite != null) {
                this.lastInvite.setRecordRoute(recordRoute);
            }
        }
        
        // Launch Vitext Client with SDP from OK
        if (ok.getSdp() != null) {
            try {
                runVitextClient(ok.getSdp());
            } catch (IOException e) {
                System.err.println("Failed to launch vitext client: " + e.getMessage());
            }
        } else {
            System.err.println("Received OK without SDP, cannot launch vitext client.");
        }

        // Build ACK and send either via proxy (loose routing) or directly to contact (end-to-end)
        try {
            ACKMessage ack = new ACKMessage();
            ack.setDestination(ok.getToUri());
            ack.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
            if (recordRoute != null) {
                ack.setRoute(recordRoute);
            }
            ack.setMaxForwards(70);
            ack.setToName(ok.getToName());
            ack.setToUri(ok.getToUri());
            ack.setFromName(ok.getFromName());
            ack.setFromUri(ok.getFromUri());
            ack.setCallId(ok.getCallId());
            ack.setcSeqNumber(ok.getcSeqNumber());
            ack.setcSeqStr("ACK");

            if (recordRoute != null) {
                // send via proxy so proxy will forward along the recorded route
                transactionLayer.sendMessageToProxy(ack);
            } else if (ok.getContact() != null) {
                // send directly to contact
                String contact = ok.getContact();
                if (contact.startsWith("sip:")) {
                    contact = contact.substring(4);
                }
                String[] parts = contact.split(":");
                String addr = parts[0];
                int port = 5060; // Default SIP port
                
                if (parts.length > 1) {
                    try {
                        port = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid port in contact: " + parts[1] + ", using default 5060");
                    }
                }
                // Handle user@host
                if (addr.contains("@")) {
                    addr = addr.substring(addr.indexOf("@") + 1);
                }
                transactionLayer.sendMessageToAddress(ack, addr, port);
            } else {
                // fallback: send to proxy
                transactionLayer.sendMessageToProxy(ack);
            }
        } catch (IOException e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void onInviteNotFoundResponse(NotFoundMessage sipMessage) {
        System.err.println("Could not contact: " + sipMessage.getToName() + " (not found).");
        stopVitextClient();
    }

    public void onInviteBusyHereResponse(BusyHereMessage sipMessage) {
        System.err.println("Could not contact: " + sipMessage.getToName() + " (busy).");
        stopVitextClient();
    }

    public void onInviteServiceUnavailableResponse(ServiceUnavailableMessage sipMessage) {
        System.err.println("Could not contact: " + sipMessage.getToName() + " (busy)."); // using same (busy) message for test compatibility
        stopVitextClient();
    }

    public void onInviteReceived(InviteMessage inviteMessage) throws IOException {
        amICaller = false;
        System.out.println("Received INVITE from " + inviteMessage.getFromName());
        if (DEBUG) {
            System.out.println("IN_DEBUG_VIAS " + inviteMessage.getVias());
        }
        
        // Auto-answer
        SDPMessage sdpMessage = new SDPMessage();
        sdpMessage.setIp(this.myAddress);
        sdpMessage.setPort(this.rtpPort);
        sdpMessage.setOptions(RTPFLOWS);
        transactionLayer.answerCall(sdpMessage, getContactUri());
        runVitextServer(sdpMessage);
        this.lastInvite = inviteMessage;
    }

    public void onByeReceived(ByeMessage byeMessage){
        stopVitextClient();
        stopVitextServer();
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
            case UNREGISTERED:
            case REGISTERING:
                break;
            case REGISTERED:
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
        if (line.toLowerCase().startsWith("invite")) {
            if (state == State.REGISTERED) {
                commandInvite(line);
            } else {
                System.err.println("Cannot INVITE while not registered");
            }
        } else if (line.toLowerCase().equals("answer")) {
            SDPMessage sdpMessage = new SDPMessage();
            sdpMessage.setIp(this.myAddress);
            sdpMessage.setPort(this.rtpPort);
            sdpMessage.setOptions(RTPFLOWS);
            transactionLayer.answerCall(sdpMessage, getContactUri());
            runVitextServer(sdpMessage);
        } else if (line.toLowerCase().equals("bye")) {
            if (lastInvite != null) {
                try {
                    ByeMessage byeMessage;
                    if (amICaller) {
                        byeMessage = lastInvite.createByeMessageFromCaller();
                    } else {
                        byeMessage = lastInvite.createByeMessageFromCallee();
                    }
                    if (DEBUG) System.out.println("DEBUG_BYE " + byeMessage.toStringMessage());
                    System.out.println("Sending BYE...");
                    transactionLayer.sendBye(byeMessage);
                    stopVitextClient();
                    if (vitextServer != null) vitextServer.destroy();
                } catch (IOException e) {
                    System.err.println("Failed to send BYE: " + e.getMessage());
                }
            } else {
                 System.out.println("No active call to hang up.");
            }
        } else if (line.toLowerCase().equals("exit")) {
            shouldExit = true;
            terminate();
        }        
        else {
            System.out.println("Bad command");
        }
    }

    private void commandInvite(String line) throws IOException {
        stopVitextServer();
        stopVitextClient();

        String to;
        
        try {
            to = line.split(" ")[1];
        } catch (Exception e) {
            System.err.println("Usage: INVITE <sip_user>");
            return;
        }

        System.out.println("Inviting " + to + "...");

        String callId = UUID.randomUUID().toString();

        SDPMessage sdpMessage = new SDPMessage();
        sdpMessage.setIp(this.myAddress);
        sdpMessage.setPort(this.rtpPort);
        sdpMessage.setOptions(RTPFLOWS);

        InviteMessage inviteMessage = new InviteMessage();
        inviteMessage.setDestination("sip:" + to + "@SMA");
        inviteMessage.setVias(new ArrayList<String>(Arrays.asList(this.myAddress + ":" + this.listenPort)));
        inviteMessage.setMaxForwards(70);
        inviteMessage.setToName(to);
        inviteMessage.setToUri("sip:" + to + "@SMA");
        inviteMessage.setFromName(sipUserName);
        inviteMessage.setFromUri(sipUserUri);
        inviteMessage.setCallId(callId);
        inviteMessage.setcSeqNumber(String.valueOf(globalCSeq++));
        inviteMessage.setcSeqStr("INVITE");
        inviteMessage.setContact(myAddress + ":" + listenPort);
        inviteMessage.setContentType("application/sdp");
        inviteMessage.setContentLength(sdpMessage.toStringMessage().getBytes().length);
        inviteMessage.setSdp(sdpMessage);
        this.lastInvite = inviteMessage;
        this.amICaller = true;
        
        if (DEBUG) System.out.println("DEBUG_INVITE_CSEQ " + inviteMessage.getcSeqNumber());

        transactionLayer.call(inviteMessage);
    }

    private void runVitextClient(SDPMessage sdp) throws IOException {
        if (TEST_MODE) {
            System.out.println("[TEST_MODE] Skipping Vitext Client launch.");
            return;
        }
        String multicastIp = sdp.getIp();
        int port = sdp.getPort();

        vitextClient = TerminalLauncher.startInTerminal(
            Arrays.asList(
                "vitext/vitextclient",
                "-p", String.valueOf(port),
                multicastIp
            )
        );

        new Thread(() -> {
            try {
                vitextClient.waitFor();
                this.state = State.REGISTERED;
                if (lastInvite != null) {
                    try {
                        ByeMessage byeMessage = lastInvite.createByeMessageFromCaller();
                        if (DEBUG) {
                            System.out.println("[DEBUG] Sending BYE after vitext client exit.");
                            System.out.println("[DEBUG] BYE Message: " + byeMessage.toString());
                        }
                        transactionLayer.sendBye(byeMessage);
                    } catch (IOException e) {
                        System.err.println("Failed to send BYE: " + e.getMessage());
                    }
                } else {
                    System.out.println("No active call to send BYE for.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "VitextClient-Watcher").start();
    }

    private void stopVitextClient() {
        if (vitextClient != null) {
            vitextClient.destroy();
        }
    }

    private void runVitextServer(SDPMessage sdp) throws IOException {
        if (TEST_MODE) {
            System.out.println("[TEST_MODE] Skipping Vitext Server launch.");
            return;
        }
        
        String multicastIp = sdp.getIp();
        int port = sdp.getPort();
        
        vitextServer = TerminalLauncher.startInTerminal(
            Arrays.asList(
                "vitext/vitextserver",
                "-r", "2",
                "-p", String.valueOf(port),
                "vitext/1.vtx",
                multicastIp
            )
        );

        new Thread(() -> {
            try {
                vitextServer.waitFor();
                try {
                    ByeMessage byeMessage = lastInvite.createByeMessageFromCallee();
                    if (DEBUG) {
                        System.out.println("[DEBUG] Sending BYE after vitext server exit.");
                        System.out.println("[DEBUG] BYE Message: " + byeMessage.toString());
                    }
                    transactionLayer.sendBye(byeMessage);
                } catch (IOException e) {
                    System.err.println("Failed to send BYE: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "VitextServer-Watcher").start();
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

    public String getContactUri() {
        return extractUser(sipUserUri) + "@" + myAddress + ":" + listenPort;
    }

    private String buildContactUri() {
        return getContactUri();
    }

    private void terminate(String reason) {
        System.err.println("Terminating UA: " + reason);
        terminate();
    }

    private void terminate() {
        if (registrationTimeout != null && !registrationTimeout.isDone()) {
            registrationTimeout.cancel(false);
        }
        if (registrationRenewal != null && !registrationRenewal.isDone()) {
            registrationRenewal.cancel(false);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        transactionLayer.terminate();
        stopVitextClient();
        stopVitextServer();
    }
}
