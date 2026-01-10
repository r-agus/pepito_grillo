import proxy.ProxyUserLayer;

public class Proxy {
	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("Usage: java Proxy <listenPort> [<loose-routing> <debug>]");
			return;
		}
		
		int listenPort = Integer.parseInt(args[0]);
		boolean loose = false;
		if (args.length >= 2) {
		    loose = "true".equalsIgnoreCase(args[1]) || "loose".equalsIgnoreCase(args[1]);
		}
		
		boolean debug = false;
		if (args.length >= 3) {
		    debug = "true".equalsIgnoreCase(args[2]);
		} else {
             debug = Boolean.getBoolean("debug");
        }
        
        // Hidden argument for testing force loose routing if needed
        boolean force = false;
        if (args.length >= 4) {
            force = "true".equalsIgnoreCase(args[3]);
        }

		if (debug) System.out.println("Proxy launching with args: " + String.join(", ", args));

		ProxyUserLayer userLayer = new ProxyUserLayer(listenPort, loose, force);
		userLayer.setDebug(debug);
		userLayer.startListening();
	}
}

