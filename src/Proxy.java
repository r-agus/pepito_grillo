import proxy.ProxyUserLayer;

public class Proxy {
	private static final boolean DEBUG = false; // Boolean.getBoolean("debug");
	public static void main(String[] args) throws Exception {
		if (DEBUG) System.out.println("Proxy launching with args: " + String.join(", ", args));
		int listenPort = Integer.parseInt(args[0]);
		ProxyUserLayer userLayer = new ProxyUserLayer(listenPort);
		userLayer.setDebug(DEBUG);
		userLayer.startListening();
	}
}
