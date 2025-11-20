import ua.UaUserLayer;

public class UA {
    public static void main(String[] args) throws Exception {
        if (args.length < UAHelp.mandatoryArgs || args.length > UAHelp.mandatoryArgs + UAHelp.optionalArgs) {
            UAHelp.print_help();
            return;
        }
        System.out.println("UA launching with args: " + String.join(", ", args));

        String sip_user = args[0];
        int listenPort = Integer.parseInt(args[1]);
        String proxyAddress = args[2];
        int proxyPort = Integer.parseInt(args[3]);
        int tiempoRegistro = args.length == 5 ? Integer.parseInt(args[4]) : 2000;

        UaUserLayer userLayer = new UaUserLayer(sip_user, listenPort, proxyAddress, proxyPort, tiempoRegistro);

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
        protected static int mandatoryArgs = 4;
        protected static int optionalArgs = 1;
        
        protected static void print_help() {
            System.out.println("Usage: java UA <sip_user> <listen_port> <proxy_address> <proxy_port> [<register_seconds>]");
        }
    }
}
