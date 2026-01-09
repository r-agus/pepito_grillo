import proxy.ProxyUserLayer;

public class Proxy {
	private static final boolean DEBUG = Boolean.getBoolean("debug");
	public static void main(String[] args) throws Exception {
		if (DEBUG) System.out.println("Proxy launching with args: " + String.join(", ", args));
		if (args.length < 1) {
			System.err.println("Usage: java Proxy <listenPort> [loose]");
			return;
		}
		int listenPort = Integer.parseInt(args[0]);
		boolean loose = args.length > 1 && ("loose".equalsIgnoreCase(args[1]) || "true".equalsIgnoreCase(args[1]));
		boolean force = args.length > 2 && ("force".equalsIgnoreCase(args[2]) || "true".equalsIgnoreCase(args[2]));
		ProxyUserLayer userLayer = new ProxyUserLayer(listenPort, loose, force);
		userLayer.setDebug(DEBUG);
		userLayer.startListening();
	}
}
