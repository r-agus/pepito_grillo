import ua.UaUserLayer;

public class UA {
    public static void main(String[] args) throws Exception {
        if (args.length < UAHelp.minArgs || args.length > UAHelp.maxArgs) {
            UAHelp.print_help();
            return;
        }
        
        String sip_user = args[0];
        int listenPort = Integer.parseInt(args[1]);
        String proxyAddress = args[2];
        int proxyPort = Integer.parseInt(args[3]);
        
        boolean debug = false;
        if (args.length >= 5) {
             debug = Boolean.parseBoolean(args[4]);
        } else {
             debug = Boolean.getBoolean("debug"); // Fallback to system prop if not provided, for backward comp if needed, but per instructions we should look at arg
        }
        
        int registerExpires = 3000;
        if (args.length >= 6) {
            registerExpires = Integer.parseInt(args[5]);
        }

        if (debug) System.out.println("UA launching with args: " + String.join(", ", args));

        UaUserLayer userLayer = new UaUserLayer(sip_user, listenPort, proxyAddress, proxyPort, registerExpires);
        userLayer.setDebug(debug);

        new Thread() {
            @Override
            public void run() {
                userLayer.startListeningNetwork();
            }
        }.start();

        try {
            userLayer.registerWithProxy();
        } catch (Exception e) {
            System.err.println("Failed to send REGISTER: " + e.getMessage());
            e.printStackTrace();
        }

        userLayer.startListeningKeyboard();
    }

    private static class UAHelp {
        protected static int minArgs = 4;
        protected static int maxArgs = 6;
        
        protected static void print_help() {
            System.out.println("Usage: java UA <sip_user> <listen_port> <proxy_address> <proxy_port> [<debug> <register_seconds>]");
        }
    }
}

