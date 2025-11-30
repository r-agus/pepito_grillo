package proxy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

import mensajesSIP.SIPMessage;

public class ProxyTransportLayer {
	private static final int BUFSIZE = 4 * 1024;

	private int listenPort;
	private DatagramSocket socket;
	private ProxyTransactionLayer transactionLayer;
	
	/**
	 * Memory structure to store a SIP message along with its destination address and port.
	 */
	private class MessageMemory {
		SIPMessage sipMessage;
		String address;
		int port;

		MessageMemory(SIPMessage sipMessage, String address, int port) {
			this.sipMessage = sipMessage;
			this.address = address;
			this.port = port;
		}
	}

	/**
	 * Handles a small in-memory database to store SIP messages along with their destination addresses and ports.
	 */
	private class MemoryOrchestrator {
		private final int MAX_MEMORY_SIZE = 100;
		private final List<MessageMemory> memory;

		private final Object lock = new Object();

		MemoryOrchestrator() {
			this.memory = new ArrayList<>();
		}

		public void storeMessage(SIPMessage sipMessage, String address, int port) {
			synchronized (lock) {
				if (memory.size() >= MAX_MEMORY_SIZE) {
					memory.remove(0); // Remove oldest message
				}
				memory.add(new MessageMemory(sipMessage, address, port));
			}
		}

		public MessageMemory retrieveMessage(SIPMessage sipMessage) {
			synchronized (lock) {
				for (MessageMemory msgMem : memory) {
					if (msgMem.sipMessage.equals(sipMessage)) {
						return msgMem;
					}
				}
				return null; // Not found
			}
		}
	}

	private final MemoryOrchestrator memoryOrchestrator;

	public ProxyTransportLayer(int listenPort, ProxyTransactionLayer transactionLayer) throws SocketException {
		this.transactionLayer = transactionLayer;
		this.listenPort = listenPort;
		this.socket = new DatagramSocket(listenPort);
		this.memoryOrchestrator = new MemoryOrchestrator();
	}

	public void send(SIPMessage sipMessage, String address, int port) throws IOException {
		send(sipMessage.toStringMessage().getBytes(), address, port);
	}

	private void send(byte[] bytes, String address, int port) throws IOException {
		InetAddress inetAddress = InetAddress.getByName(address);
		DatagramPacket packet = new DatagramPacket(bytes, bytes.length, inetAddress, port);
		socket.send(packet);
	}

	public String getSenderIP(SIPMessage sipMessage) {
		MessageMemory msgMem = memoryOrchestrator.retrieveMessage(sipMessage);
		if (msgMem != null) {
			return msgMem.address;
		}
		return null;
	}

	public int getSenderPort(SIPMessage sipMessage) {
		MessageMemory msgMem = memoryOrchestrator.retrieveMessage(sipMessage);
		if (msgMem != null) {
			return msgMem.port;
		}
		return -1;
	}

	public void startListening() {
		System.out.println("Listening at " + listenPort + "...");
		while (true) {
			try {
				byte[] buf = new byte[BUFSIZE];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				socket.receive(packet);
				String msg = new String(packet.getData());
				SIPMessage sipMessage = SIPMessage.parseMessage(msg);
				this.memoryOrchestrator.storeMessage(sipMessage, packet.getAddress().getHostAddress(), packet.getPort());
				transactionLayer.onMessageReceived(sipMessage);
			} catch (Exception e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
			}
		}
	}

}
