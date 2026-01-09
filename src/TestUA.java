import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TestUA {

    private static final String CLASSPATH = "out/production/sma:lib/*";
    private static final String ALICE_URI = "sip:alice@domain.com";
    private static final String BOB_URI = "sip:bob@domain.com";
    private static final String CHARLIE_URI = "sip:charlie@domain.com"; 
    private static final String EVIL_URI = "sip:evil@domain.com";

    private static int basePort = 40000;
    
    // Track created processes for cleanup
    private static final List<SIPProcess> createdProcesses = new ArrayList<>();

    private static int nextPort() {
        // Increment by 100 to leave space and avoid immediate reuse issues
        int p = basePort;
        basePort += 100; 
        return p;
    }

    private static final String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    public static void main(String[] args) {
        System.out.println("=================");
        System.out.println("Running SIP Tests");
        System.out.println("=================");

        int failures = 0;

        // Cleanup before starting
        cleanup();

        // --- Basic Registration & Errors ---
        failures += runTest("Retransmission (REGISTER)", TestUA::testRegisterRetransmission);
        failures += runTest("200 OK Registration", TestUA::testRegisterSuccess);
        failures += runTest("404 Not Found Registration", TestUA::testRegisterNotFound);
        failures += runTest("Duplicate Registration (Update IP)", TestUA::testDuplicateRegistration);

        // --- INVITE & Call Flow ---
        failures += runTest("INVITE 404 (Not Registered)", TestUA::testInviteNotRegistered);
        failures += runTest("UA Busy 486", TestUA::testUABusy); 
        failures += runTest("Successful Call (180, 200 OK)", TestUA::testCallSuccess);
        failures += runTest("Invite Timeout (408)", TestUA::testInviteTimeout);
        failures += runTest("Sequential Calls (State persistence)", TestUA::testSequentialCalls);
        failures += runTest("Proxy Restart State Clear", TestUA::testProxyRestartState);
        failures += runTest("Via Header Modification", TestUA::testViaHeaders);
        failures += runTest("Header Inversion on BYE (Callee Hangup)", TestUA::testByeHeaderInversion);
        failures += runTest("Max-Forwards Processing (Proxy drops 0)", TestUA::testMaxForwards);
        
        // This test requires analyzing two parallel INVITES or logs for CSeq increments
        failures += runTest("CSeq Monotonicity", TestUA::testCSeqIncrements);
        
        // --- Vitext & Routing ---
        failures += runTest("Vitext Launch (m=video)", TestUA::testVitextLaunch);
        failures += runTest("Loose Routing (Record-Route/Route)", TestUA::testLooseRouting);
        failures += runTest("Servlet Blocking (403->503)", TestUA::testServletBlocking);
        
        System.out.println("==========================================");
        if (failures > 0) {
            System.out.println("Tests Complete: " + failures + " FAILED.");
            System.exit(1);
        } else {
            System.out.println("Tests Complete: ALL PASSED.");
            System.exit(0);
        }
    }
    
    // --- Helper for Raw UDP ---
    private static void sendRawUDP(String message, int port) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        byte[] buf = message.getBytes();
        InetAddress address = InetAddress.getByName("127.0.0.1");
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        socket.close();
    }
    
    private static int runTest(String name, TestRunnable test) {
        System.out.println("\n>>> Executing Test: " + name + "...");
        try {
            cleanup();
            test.run();
            System.out.println("[PASS] " + name);
            return 0;
        } catch (Throwable e) {
            System.err.println("[FAIL] " + name + ": " + e.getMessage());
            // e.printStackTrace(); 
            return 1;
        } finally {
            cleanup();
        }
    }

    interface TestRunnable {
        void run() throws Exception;
    }

    // --- Test Implementations ---

    private static void testRegisterRetransmission() throws Exception {
        int proxyPort = nextPort();
        int uaPort = nextPort();
        
        SIPProcess ua = new SIPProcess("UA", "UA", ALICE_URI, String.valueOf(uaPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua.start();
        Thread.sleep(5000);
        
        int retransmissions = countOccurrences(ua.getOutput(), "No REGISTER response received");
        // Requirement: retransmits every 2s. In 5s, expect ~2.
        if (retransmissions < 1) throw new RuntimeException("Expected retransmissions, found " + retransmissions);
        
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(3000);
        
        int countBefore = countOccurrences(ua.getOutput(), "No REGISTER response received");
        Thread.sleep(2000);
        int countAfter = countOccurrences(ua.getOutput(), "No REGISTER response received");
        
        if (countAfter > countBefore) throw new RuntimeException("Retransmissions continued after Proxy started.");
        
        ua.stop();
        proxy.stop();
    }

    private static void testRegisterSuccess() throws Exception {
        int proxyPort = nextPort();
        int uaPort = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        SIPProcess ua = new SIPProcess("UA", "UA", ALICE_URI, String.valueOf(uaPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua.enableDebug(); // Enabled debug to verify registration logs
        ua.start();
        Thread.sleep(2000);
        if (!ua.getOutput().contains("Received response for REGISTER") && !ua.getOutput().contains("200 OK")) { 
             throw new RuntimeException("UA did not register (200 OK missing).");
        }
    }
    
    private static void testRegisterNotFound() throws Exception {
        int proxyPort = nextPort();
        int uaPort = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        SIPProcess ua = new SIPProcess("UA", "UA", EVIL_URI, String.valueOf(uaPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua.start();
        Thread.sleep(2000);
        if (!ua.getOutput().contains("Received 404 Not Found")) {
             throw new RuntimeException("Expected 404 error.");
        }
    }
    
    private static void testDuplicateRegistration() throws Exception {
        int proxyPort = nextPort();
        int ua1Port = nextPort();
        int ua2Port = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.enableDebug();
        proxy.start();
        Thread.sleep(500);
        
        SIPProcess ua1 = new SIPProcess("UA1", "UA", ALICE_URI, String.valueOf(ua1Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua1.start();
        Thread.sleep(2000);
        if (!proxy.getOutput().contains(String.valueOf(ua1Port))) throw new RuntimeException("Proxy didn't register UA1.");
        
        SIPProcess ua2 = new SIPProcess("UA2", "UA", ALICE_URI, String.valueOf(ua2Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua2.start(); 
        Thread.sleep(2000);
        
        if (!proxy.getOutput().contains(String.valueOf(ua2Port))) {
             throw new RuntimeException("Proxy didn't update registration to UA2.");
        }
    }
    
    private static void testInviteNotRegistered() throws Exception {
        int proxyPort = nextPort();
        int uaPort = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        
        SIPProcess ua = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(uaPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua.start(); // Alice registers
        Thread.sleep(2000); 
        
        ua.sendInput("INVITE Bob"); // Bob not reg
        Thread.sleep(1000);
        
        if (!ua.getOutput().contains("Received 404") && !ua.getOutput().contains("not found")) {
             throw new RuntimeException("Alice did not receive 404 for missing Bob.");
        }
    }
    
    private static void testUABusy() throws Exception {
        int proxyPort = nextPort();
        int bobPort = nextPort();
        int alicePort = nextPort();
        int charliePort = nextPort();

        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.enableDebug();
        proxy.start();
        Thread.sleep(500);
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(bobPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        bob.enableDebug();
        bob.start();
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(alicePort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.enableDebug();
        alice.start();
        SIPProcess charlie = new SIPProcess("Charlie", "UA", CHARLIE_URI, String.valueOf(charliePort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        charlie.enableDebug();
        charlie.start();
        
        Thread.sleep(3000); 
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(500); // Bob accepts
        
        if (!alice.getOutput().contains("OK")) {
            System.out.println("___ Proxy Log ___");
            System.out.println(proxy.getOutput());
            System.out.println("___ ALICE LOG ___");
            System.out.println(alice.getOutput());
            throw new RuntimeException("Alice did not establish call with Bob (no 200 OK).");
        }
        
        // Now Bob is busy
        charlie.sendInput("INVITE Bob");
        
        // Wait longer for timeout/response
        Thread.sleep(500);
        
        String charlieOutput = charlie.getOutput();
        if (!charlieOutput.contains("busy")) {
             System.out.println("___ ALICE LOG ___");
             System.out.println(alice.getOutput());
             System.out.println("___ BOB LOG ___");
             System.out.println(bob.getOutput());
             System.out.println("___ CHARLIE LOG ___");
             System.out.println(charlieOutput);
             throw new RuntimeException("Charlie did not receive 486 Busy. Output: " + charlieOutput);
        }
    }

    private static void testCallSuccess() throws Exception {
        int proxyPort = nextPort();
        int ua1Port = nextPort();
        int ua2Port = nextPort();

        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(ua2Port), "127.0.0.1", String.valueOf(proxyPort), "5000");
        bob.enableDebug();
        bob.start();
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(ua1Port), "127.0.0.1", String.valueOf(proxyPort), "5000");
        alice.enableDebug();
        alice.start();
        
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(3000); 
        
        if (!bob.getOutput().contains("Received INVITE")) throw new RuntimeException("Bob missed INVITE.");
        // Bob auto-answers (180 then 200)
        
        if (!alice.getOutput().contains("Received OK response")) { // 180 or 200
             throw new RuntimeException("Alice missed 200 OK.");
        }
    }
    
    private static void testInviteTimeout() throws Exception {
        int proxyPort = nextPort();
        int ua1Port = nextPort();
        int ua2Port = nextPort();

        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        
        // Bob registers then dies
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(ua2Port), "127.0.0.1", String.valueOf(proxyPort), "20000");
        bob.start();
        Thread.sleep(2000); 
        bob.stop();
        Thread.sleep(1000);

        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(ua1Port), "127.0.0.1", String.valueOf(proxyPort), "5000");
        alice.start();
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        
        System.out.println("  Waiting 12s for timeout...");
        Thread.sleep(12000); 
        
        if (!alice.getOutput().contains("Call timeout") && !alice.getOutput().contains("408")) {
             throw new RuntimeException("Alice did not timeout (408).");
        }
    }
    
    private static void testSequentialCalls() throws Exception {
        int proxyPort = nextPort();
        int ua1Port = nextPort();
        int ua2Port = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(ua2Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        bob.start();
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(ua1Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.start();
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        alice.sendInput("bye");
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        
        if (alice.getOutput().contains("486 Busy")) {
             throw new RuntimeException("Second call failed (Bob didn't reset state).");
        }
    }

    private static void testProxyRestartState() throws Exception {
        int proxyPort = nextPort();
        int uaPort = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(uaPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.start(); 
        Thread.sleep(2000);
        
        proxy.stop();
        Thread.sleep(1000);
        proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.enableDebug();
        proxy.start();
        Thread.sleep(1000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        
        // Proxy lost state, doesn't know Alice is registered
        Thread.sleep(1000);

        String proxyLog = proxy.getOutput();
        String aliceLog = alice.getOutput();

        if (proxyLog.contains("Caller alice is not registered")) return; // PASS (Proxy 404 path)
        if (aliceLog.contains("Cannot INVITE while not registered")) return; // PASS (Client detected state loss/timeout)
        if (aliceLog.contains("404")) return; // PASS
        
        if (!aliceLog.contains("404") && !aliceLog.contains("Cannot INVITE")) { 
             throw new RuntimeException("Alice shouldn't be able to call successfully (or fail silently) after proxy restart. Output: " + aliceLog);
        }
    }

    private static void testViaHeaders() throws Exception {
        int proxyPort = nextPort();
        int alicePort = nextPort();
        int bobPort = nextPort();
        
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.enableDebug();
        proxy.start();
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(bobPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        bob.enableDebug();
        bob.start();
        
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(alicePort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.start();
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        
        // Check Bob's output for Via Headers
        // Look for log "IN_DEBUG_VIAS [via1, via2]"
        String bobLog = bob.getOutput();
        
        // Check for Proxy Port in log
        String debugLine = "";
        String[] lines = bobLog.split("\n");
        for (String l : lines) {
            if (l.contains("IN_DEBUG_VIAS")) {
                debugLine = l;
                break;
            }
        }
        
        if (debugLine.isEmpty()) {
            throw new RuntimeException("Bob missed Vias log.");
        }
        
        // Ensure Proxy added its trace
        if (!debugLine.contains(String.valueOf(proxyPort))) {
             throw new RuntimeException("Via headers do not contain Proxy trace (" + proxyPort + "). Line: " + debugLine);
        }
    }

    private static void testByeHeaderInversion() throws Exception {
        // Requirement: "Cuando la llamada se cuelga en el llamado se invierten el To y el From"
        int proxyPort = nextPort();
        int alicePort = nextPort();
        int bobPort = nextPort();
        
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(bobPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        bob.enableDebug(); // Bob will hang up
        bob.start();
        
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(alicePort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.start();
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        
        // Bob (Callee) hangs up
        bob.sendInput("bye");
        Thread.sleep(1000);
        
        // Bob's log should contain "BYE" and "To: <Alice>" and "From: <Bob>"
        String bobLog = bob.getOutput();
        if (!bobLog.contains("DEBUG_BYE")) {
             throw new RuntimeException("Debug BYE log missing");
        }
        
        boolean hasFromBob = bobLog.contains("From:") && (bobLog.contains(BOB_URI) || bobLog.toLowerCase().contains("sip:bob"));
        boolean hasToAlice = bobLog.contains("To:") && (bobLog.contains(ALICE_URI) || bobLog.toLowerCase().contains("sip:alice"));
        
        if (!hasFromBob || !hasToAlice) {
             throw new RuntimeException("BYE headers missing expected URIs (From=Bob, To=Alice). Log: " + bobLog);
        }
    }

    private static void testMaxForwards() throws Exception {
        int proxyPort = nextPort();
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        Thread.sleep(500);
        
        // Raw INVITE with Max-Forwards: 0
        String invite = "INVITE sip:bob@domain.com SIP/2.0\r\n" +
                        "Via: SIP/2.0/UDP 127.0.0.1:55555\r\n" +
                        "From: sip:alice@domain.com\r\n" +
                        "To: sip:bob@domain.com\r\n" +
                        "Call-ID: testmaxfw\r\n" +
                        "CSeq: 1 INVITE\r\n" +
                        "Max-Forwards: 0\r\n" +
                        "Content-Length: 0\r\n\r\n";
                        
        sendRawUDP(invite, proxyPort);
        Thread.sleep(500);
        
        String log = proxy.getOutput();
        // If it tried to forward, it would define Bob is not found.
        if (log.contains("forward")) {
             throw new RuntimeException("Proxy forwarded message with Max-Forwards: 0");
        }
    }

    private static void testCSeqIncrements() throws Exception {
       // Requirement: "No hay 2 invocaciones de métodos SIP con el mismo CSeq"
       // Verify sequential calls have increasing CSeq
        int proxyPort = nextPort();
        int uaPort = nextPort();
        
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        SIPProcess ua = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(uaPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        ua.enableDebug();
        ua.start();
        Thread.sleep(1000);

        ua.sendInput("INVITE Bob");
        Thread.sleep(500);
        ua.sendInput("INVITE Bob");
        Thread.sleep(500);
        
        String log = ua.getOutput();
        List<Integer> cseqs = new ArrayList<>();
        // Look for DEBUG_INVITE_CSEQ <n>
        String[] lines = log.split("\n");
        for (String l : lines) {
            if (l.contains("DEBUG_INVITE_CSEQ")) {
                try {
                    String[] parts = l.split(" ");
                    String num = parts[parts.length-1].trim();
                    cseqs.add(Integer.parseInt(num));
                } catch(Exception e) {}
            }
        }
        
        if (cseqs.size() < 2) throw new RuntimeException("Not enough INVITES sent to verify CSeq. Found: " + cseqs.size());
        
        for (int i = 0; i < cseqs.size() - 1; i++) {
            if (cseqs.get(i) >= cseqs.get(i+1)) {
                throw new RuntimeException("CSeq did not increment. " + cseqs.get(i) + " -> " + cseqs.get(i+1));
            }
        }
    }

    private static void testVitextLaunch() throws Exception {
        int proxyPort = nextPort();
        int ua1Port = nextPort();
        int ua2Port = nextPort();
        
        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.start();
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(ua2Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        bob.enableDebug();
        bob.start();
        
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(ua1Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.enableDebug();
        alice.start();
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        
        // Check logs for Vitext launch
        String aliceLog = alice.getOutput();
        String bobLog = bob.getOutput();
        
        // Alice (Caller) should launch client OR skip in test mode
        if (!aliceLog.contains("vitextclient") && !aliceLog.contains("VitextClient") && !aliceLog.contains("Skipping Vitext Client")) {
             throw new RuntimeException("Alice did not launch vitextclient. Log:\n" + aliceLog);
        }
        
        // Bob (Callee) should launch server OR skip in test mode
        if (!bobLog.contains("vitextserver") && !bobLog.contains("VitextServer") && !bobLog.contains("Skipping Vitext Server")) {
             throw new RuntimeException("Bob did not launch vitextserver. Log:\n" + bobLog);
        }
        
        // Check for m=video
        if (!bobLog.contains("m=video") && !aliceLog.contains("m=video")) {
            System.out.println("___ BOB LOG ___");
            System.out.println(bobLog);
            System.out.println("___ END BOB LOG ___");
            throw new RuntimeException("SDP did not contain m=video.");
        }
    }
    
    private static void testLooseRouting() throws Exception {
        int proxyPort = nextPort();
        int ua1Port = nextPort();
        int ua2Port = nextPort();

        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort), "true", "true");
        proxy.enableDebug();
        proxy.start();
        
        SIPProcess bob = new SIPProcess("Bob", "UA", BOB_URI, String.valueOf(ua2Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        bob.start();
        
        SIPProcess alice = new SIPProcess("Alice", "UA", ALICE_URI, String.valueOf(ua1Port), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.start();
        Thread.sleep(2000);
        
        alice.sendInput("INVITE Bob");
        Thread.sleep(2000);
        
        String proxyLog = proxy.getOutput();
        
        if (!proxyLog.contains("Record-Route")) {
             System.out.println("___ PROXY LOG (LooseRouting INVITE) ___");
             System.out.println(proxyLog);
             throw new RuntimeException("Proxy did not add Record-Route (or log it).");
        }
        
        // Verify BYE has Route header
        alice.sendInput("bye");
        Thread.sleep(2000);
        
        proxyLog = proxy.getOutput();
        // Since Proxy logs headers, check there
        if (!proxyLog.contains("route=")) { 
             System.out.println("___ PROXY LOG (LooseRouting BYE) ___");
             System.out.println(proxyLog);
             throw new RuntimeException("Proxy did not see Route header in BYE/ACK. Log: " + proxyLog);
        }
    }

    private static void cleanup() {
        for (SIPProcess p : createdProcesses) {
            p.stop();
        }
        createdProcesses.clear();
        try {
            Thread.sleep(200);
        } catch (Exception e) {}
    }

    private static int countOccurrences(String str, String substr) {
        int lastIndex = 0;
        int count = 0;
        while (lastIndex != -1) {
            lastIndex = str.indexOf(substr, lastIndex);
            if (lastIndex != -1) {
                count++;
                lastIndex += substr.length();
            }
        }
        return count;
    }

    static class SIPProcess {
        String mainClass;
        String[] args;
        Process process;
        Thread outputGobbler;
        BufferedWriter docWriter;
        StringBuilder runOutput = new StringBuilder();
        String name;
        boolean debug = false;

        public SIPProcess(String name, String mainClass, String... args) {
            this.name = name;
            this.mainClass = mainClass;
            this.args = args;
            synchronized(createdProcesses) {
                createdProcesses.add(this);
            }
        }
        
        public void enableDebug() {
             this.debug = true;
        }
        
        public boolean isAlive() {
            return process != null && process.isAlive();
        }

        public void start() throws IOException {
            List<String> command = new ArrayList<>();
            command.add(javaBin);
            if (debug || mainClass.equals("UA") || mainClass.equals("Proxy") || name.contains("UA")) { 
                 command.add("-Ddebug=" + debug); 
                 command.add("-DtestMode=true"); 
            }
            command.add("-cp");
            command.add(CLASSPATH);
            command.add(mainClass);
            for (String arg : args) command.add(arg);

            // Use ProcessBuilder to verify current directory
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            
            docWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            outputGobbler = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized(runOutput) {
                            runOutput.append("[" + name + "] " + line).append("\n");
                        }
                    }
                } catch (IOException e) {
                }
            });
            outputGobbler.start();
        }
        
        public void sendInput(String input) throws IOException {
            if (process != null && process.isAlive()) {
                docWriter.write(input);
                docWriter.newLine();
                docWriter.flush();
            }
        }

        public void stop() {
            if (process != null) {
                process.destroy(); // SIGTERM
                try {
                     process.waitFor(200, TimeUnit.MILLISECONDS);
                } catch (Exception e){}
                if (process.isAlive()) process.destroyForcibly(); // SIGKILL
            }
        }
        
        public String getOutput() {
            synchronized(runOutput) {
                return runOutput.toString();
            }
        }
    }

    private static void testServletBlocking() throws Exception {
        int proxyPort = nextPort();
        int marioPort = nextPort();
        int alicePort = nextPort();
        String marioName = "sip:mario@it.uc3m.es";
        String aliceName = "sip:alice@domain.com";

        SIPProcess proxy = new SIPProcess("Proxy", "Proxy", String.valueOf(proxyPort));
        proxy.enableDebug();
        proxy.start();
        Thread.sleep(1000);

        // Mario registers (time restriction servlet)
        SIPProcess mario = new SIPProcess("Mario", "UA", marioName, String.valueOf(marioPort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        mario.enableDebug();
        mario.start();
        Thread.sleep(1000);

        // Check for debug log "Received response for REGISTER" (200 OK)
        if (!mario.getOutput().contains("Received response for REGISTER")) {
            System.out.println("___ MARIO LOG (Register Fail) ___");
            System.out.println(mario.getOutput());
            System.out.println("___ PROXY LOG (Register Fail) ___");
            System.out.println(proxy.getOutput());
            throw new RuntimeException("Mario failed to register.");
        }

        // Alice registers
        SIPProcess alice = new SIPProcess("Alice", "UA", aliceName, String.valueOf(alicePort), "127.0.0.1", String.valueOf(proxyPort), "3000");
        alice.enableDebug();
        alice.start();
        Thread.sleep(1000); // register
        
        if (!alice.getOutput().contains("Received response for REGISTER")) {
             throw new RuntimeException("Alice failed to register.");
        }

        // Mario calls Alice
        // Since it is NOT 10:00-11:00, this should be BLOCKED by Servlet (403).
        // Mario should receive 503 Service Unavailable.
        System.out.println("Mario calling Alice (expecting BLOCK)...");
        mario.sendInput("INVITE alice");
        Thread.sleep(1000);

        String marioOut = mario.getOutput();
        String proxyOut = proxy.getOutput();

        // UaUserLayer prints "Could not contact: ... (service unavailable)." on 503/ServiceUnavailable
        if (!marioOut.contains("(service unavailable)")) {
             System.out.println("___ MARIO LOG ___");
             System.out.println(marioOut);
            throw new RuntimeException("Mario did not receive 503 Service Unavailable as expected.");
        }
        
        // The servlet prints "TimeRestrictedServlet: Outgoing call rejected..."
        if (!proxyOut.contains("TimeRestrictedServlet: Outgoing call rejected")) {
             System.out.println("___ PROXY LOG ___");
             System.out.println(proxyOut);
             throw new RuntimeException("Proxy/Servlet did not log rejection message.");
        }
    }
}
