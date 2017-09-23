package de.pangeule.teiresias;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import de.pangeule.teiresias.proto.DataBody;
import de.pangeule.teiresias.proto.ImHere;
import de.pangeule.teiresias.proto.MessageType;
import de.pangeule.teiresias.proto.TeiresiasMessage;
import okio.ByteString;

/**
 * Teiresias peer-to-peer messaging. Use {@link #init init method} for
 * initializing and instantiating.
 * 
 * @author crd
 *
 */
public class Teiresias {

	private static final int TEIRESIAS_PORT = 7530;
	// it is technically possible to send 64kb; yet this is discouraged
	private static final int MAX_PACKET_SIZE = 1024 * 32;
	private static final String BROADCAST_MASK = "255.255.255.255";
	private static InetAddress _broadcastAddress;
	private static final String LOCALHOST = "127.0.0.1";
	private static final TeiresiasLogger LOGGER = new TeiresiasLogger();
	private static final int MAX_KNOWN_UUID_SET_SIZE = 300;
	private static InetAddress LOCALHOST_ADDRESS;

	private static Teiresias _instance;

	static boolean DEBUG_MODE = false;

	private final DatagramSocket _serverSocket;
	private final DatagramSocket _broadcastSocket;
	private TeiresiasClient _client;

	private boolean _localMaster = true;
	private final InetAddress _myAddress;
	private final List<String> _targetAppNameList;
	private final Set<Integer> _localPortsToPropagateTo = new HashSet<>();
	private final Deque<String> _knownUUIDs = new ArrayDeque<>();
	private final Map<String, MessageDelegate> _messageDelegateMap;
	private Set<InetAddress> _peersAlive = new HashSet<>();

	private Teiresias(Map<String, MessageDelegate> messageDelegateMap, List<String> appNameList, InetAddress ownAddress)
			throws SocketException {
		this._messageDelegateMap = messageDelegateMap;
		this._myAddress = ownAddress;
		this._targetAppNameList = appNameList;
		this._serverSocket = new DatagramSocket();
		this._broadcastSocket = new DatagramSocket();
		this._broadcastSocket.setBroadcast(true);
		this.startClient();
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				Teiresias.this.sendLastWill();
			}
		});
	}

	// API --------------------------------------------------------------

	/**
	 * See {@link #init(Map, List)}. This is a convenience method for targeting
	 * a single app.
	 * 
	 * @param delegate
	 *            Delegate for data message handling.
	 * @param targetApp
	 *            Target app for sending and receiving.
	 * @return Teiresias instance of Teiresias
	 */
	public static Teiresias init(MessageDelegate delegate, String targetApp) {
		Map<String, MessageDelegate> delegateMap = new HashMap<>();
		delegateMap.put(targetApp, delegate);
		return init(delegateMap, Collections.singletonList(targetApp));
	}

	/**
	 * This method is used to initialize and obtain an instance of Teiresis.
	 * 
	 * @param delegates
	 *            Map (appName to delegate) of delegates to be called if a
	 *            message for a certain app arrives. Note that Teiresias will
	 *            only handle one message at once. It is the caller's
	 *            responsibility to queue execution on worker threads if
	 *            simultaneous processing is needed.
	 * @param targetAppNameList
	 *            Name of the owning app that is used to identify the message
	 *            target.
	 * @return Teiresias - an instance of Teiresias
	 */
	public static Teiresias init(Map<String, MessageDelegate> delegates, List<String> targetAppNameList) {
		if (_instance != null) {
			throw new IllegalStateException("Teiresias cannot be initialized twice.");
		}
		try {
			InetAddress ownAddress = InetAddress.getLocalHost();
			LOCALHOST_ADDRESS = InetAddress.getByName(LOCALHOST);
			_instance = new Teiresias(delegates, targetAppNameList, ownAddress);
		} catch (SocketException | UnknownHostException e) {
			throw new IllegalStateException("Network connection could not be established. " + e.getMessage());
		}
		_instance.start();
		registerJMXBean();
		return _instance;
	}

	/**
	 * Used to obtain an instance of Teiresias. Note that this method will throw
	 * if Teiresias has not been initialized before.
	 * 
	 * @return Teiresias - an instance of Teiresias
	 */
	public static Teiresias getInstance() {
		if (_instance == null) {
			throw new IllegalStateException("You need to initialize Teiresias before obtaining an instance.");
		}
		return _instance;
	}

	/**
	 * Send the provided content to all known peers.
	 * 
	 * @param content
	 *            Message content.
	 * @param targetAppList
	 *            List of apps that are to receive the message.
	 */
	public void publish(byte[] content, List<String> targetAppList) {
		TeiresiasMessage dataMessage = this.getDataMessage(content, targetAppList);
		LOGGER.log(Level.INFO, "Publishing message to {0} peers.", this._peersAlive.size());
		for (InetAddress receiver : this._peersAlive) {
			this.sendMessage(dataMessage, receiver);
		}
		for (Integer port : this._localPortsToPropagateTo) {
			this.propagateToPort(dataMessage, port);
		}
		if (!this._localMaster) {
			this.propagateToPort(dataMessage, TEIRESIAS_PORT);
		}
	}

	/**
	 * Send the provided content to all known peers.
	 * 
	 * @param content
	 *            Message content.
	 * @param targetApp
	 *            The app that is to receive the message.
	 */
	public void publish(byte[] content, String targetApp) {
		this.publish(content, Collections.singletonList(targetApp));
	}

	/**
	 * Send the provided content to all instances of the owning apps running on
	 * known peers.
	 * 
	 * @param content
	 *            Message content.
	 */
	public void publish(byte[] content) {
		this.publish(content, this._targetAppNameList);
	}

	/**
	 * Sends the provided content to an instance of the owning apps running on
	 * the given receiver.
	 * 
	 * @param content
	 *            Message content
	 * @param receiver
	 *            Message receiver
	 */
	public void sendMessage(byte[] content, InetAddress receiver) {
		this.sendMessage(content, receiver, this._targetAppNameList);
	}

	/**
	 * Sends the provided content to an instance of the specified target app
	 * running on the given receiver.
	 * 
	 * @param content
	 *            Message content
	 * @param receiver
	 *            Message receiver
	 * @param targetApps
	 *            The app that is to handle the message.
	 */

	public void sendMessage(byte[] content, InetAddress receiver, String targetApps) {
		this.sendMessage(this.getDataMessage(content, Collections.singletonList(targetApps)), receiver);
	}

	/**
	 * Sends the provided content to an instance of the specified target apps
	 * running on the given receiver.
	 * 
	 * @param content
	 *            Message content
	 * @param receiver
	 *            Message receiver
	 * @param targetApps
	 *            The apps that are to handle the message.
	 */

	public void sendMessage(byte[] content, InetAddress receiver, List<String> targetApps) {
		this.sendMessage(this.getDataMessage(content, targetApps), receiver);
	}

	/**
	 * Will shut down this Teiresias instance eventually.
	 */
	public void shutdown() {
		this._client.interrupt();
	}

	/**
	 * 
	 * Callback for message processing. You might want to use this for queuing
	 * execution on your own thread pool. See {@link Teiresias#init Teiresias's
	 * init} method.
	 *
	 */
	public interface MessageDelegate {

		void onMessage(byte[] content, InetAddress sender);
	}

	// PUBLIC YET NOT DOCUMENTED
	// -----------------------------------------------------------------
	public static void setDebugMode(boolean debugMode) {
		DEBUG_MODE = debugMode;
	}

	public static void setBroadcastAddress(InetAddress address) {
		_broadcastAddress = address;
	}

	// INTERNAL
	// -----------------------------------------------------------------

	// package private for jmx access
	int getNumberOfPeers() {
		return _peersAlive.size();
	}

	// package private for jmx access
	boolean isLocalMaster() {
		return this._localMaster;
	}

	// package private for jmx access
	List<String> getTargetApps() {
		return new ArrayList<>(this._targetAppNameList);
	}

	private static void registerJMXBean() {
		try {
			MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
			ObjectName objectName = new ObjectName("de.pangeule.teiresias:type=TeiresiasManagement");
			TeiresiasManagement teiresiasManagement = new TeiresiasManagement();
			mbs.registerMBean(teiresiasManagement, objectName);
		} catch (MalformedObjectNameException | InstanceAlreadyExistsException | MBeanRegistrationException
				| NotCompliantMBeanException e) {
			LOGGER.log(Level.WARNING, "JMX could not be initialized: " + e.getMessage());
		}
	}

	private TeiresiasMessage getDataMessage(byte[] content, List<String> targetApp) {
		List<InetAddress> knownReceivers = new ArrayList<>();
		knownReceivers.add(this._myAddress);
		TeiresiasMessage message = new TeiresiasMessage.Builder().type(MessageType.DATA)
				.data(new DataBody.Builder().UUID(UUID.randomUUID().toString())
						.knownReceivers(this.getHostAddresses(knownReceivers)).targetApp(targetApp)
						.content(ByteString.of(content)).build())
				.build();
		return message;
	}

	private List<String> getHostAddresses(List<InetAddress> knownReceivers) {
		List<String> addresses = new ArrayList<>();
		for (InetAddress address : knownReceivers) {
			addresses.add(address.getHostAddress());
		}
		return addresses;
	}

	private void start() {
		if (this._localMaster) {
			this.sendWhosAlive();
		}
	}

	private void reinitClient() {
		// this._client.interrupt();
		LOGGER.log(Level.INFO, "Sleeping five seconds.");
		try {
			Thread.sleep(5000L);
		} catch (InterruptedException e) {
		}
		LOGGER.log(Level.INFO, "Restarting client.");
		this.startClient();
		this.start();
	}

	private void startClient() {
		this._client = new TeiresiasClient();
		this._client.start();
	}

	private void sendPing(InetAddress receiver) {
		this.sendEmptyMessage(MessageType.PING, receiver);
	}

	private void sendPong(InetAddress receiver) {
		this.sendEmptyMessage(MessageType.PONG, receiver);
	}

	private void sendImAlive(InetAddress receiver) {
		this.sendEmptyMessage(MessageType.IM_ALIVE, receiver);
	}

	private void sendLastWill() {
		if (this._localMaster && !this._localPortsToPropagateTo.isEmpty()) {
			LOGGER.log(Level.INFO, "Sending last will.");
			TeiresiasMessage message = new TeiresiasMessage.Builder().type(MessageType.IM_DEAD).build();
			this.send(this.getPacketToSend(message, this._myAddress, this._localPortsToPropagateTo.iterator().next()));
		}
	}

	private void sendImHere(int port) {
		TeiresiasMessage message = new TeiresiasMessage.Builder().type(MessageType.IM_HERE)
				.imHere(new ImHere.Builder().port(port).build()).build();
		this.sendMessage(message, this._myAddress);
	}

	private void sendWhosAlive() {
		TeiresiasMessage message = new TeiresiasMessage.Builder().type(MessageType.WHOS_ALIVE).build();
		this.sendBroadcast(message);
	}

	private void sendEmptyMessage(MessageType type, InetAddress receiver) {
		TeiresiasMessage message = new TeiresiasMessage.Builder().type(type).build();
		this.sendMessage(message, receiver);
	}

	private void sendBroadcast(TeiresiasMessage message) {
		try {
			InetAddress broadcastAddress = _broadcastAddress != null ? _broadcastAddress
					: InetAddress.getByName(BROADCAST_MASK);
			this._broadcastSocket.send(this.getPacketToSend(message, broadcastAddress));
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "An exception occured while sending broadcast. " + e.getMessage());
		}
	}

	private void sendMessage(TeiresiasMessage message, InetAddress receiver) {
		if (message.data != null) {
			this.addKnownUUID(message.data.UUID);
		}
		this.send(this.getPacketToSend(message, receiver));
	}

	private void propagateToPort(TeiresiasMessage message, int port) {
		this.send(this.getPacketToSend(message, LOCALHOST_ADDRESS, port));
	}

	private void addKnownUUID(String uuid) {
		this._knownUUIDs.add(uuid);
		if (this._knownUUIDs.size() > MAX_KNOWN_UUID_SET_SIZE) {
			this._knownUUIDs.removeFirst();
		}
	}

	private DatagramPacket getPacketToSend(TeiresiasMessage message, InetAddress receiver, int port) {
		byte[] encodedMessage = TeiresiasMessage.ADAPTER.encode(message);
		LOGGER.log(Level.INFO, "Sending Teiresias message of type " + message.type.name() + " to port {0}: "
				+ ByteString.of(encodedMessage).toAsciiLowercase(), port);
		return new DatagramPacket(encodedMessage, encodedMessage.length, receiver, port);
	}

	private DatagramPacket getPacketToSend(TeiresiasMessage message, InetAddress receiver) {
		return this.getPacketToSend(message, receiver, TEIRESIAS_PORT);
	}

	private void send(DatagramPacket packet) {
		try {
			this._serverSocket.send(packet);
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "An exception occured while sending packet. " + e.getMessage());
		}
	}

	private class TeiresiasClient extends Thread {

		private Integer _localSlavePort;

		private DatagramSocket _clientSocket;

		private final ScheduledExecutorService _aliveKeeper;

		private final ExecutorService _workerThreadPool;

		private final ExecutorService _callbackExecutor;

		private static final long INITIAL_KEEP_ALIVE_DELAY = 5L;

		private static final long KEEP_ALIVE_PERIOD = 10L;

		private static final int NUMBER_WORKER_THREADS = 3;

		private int disconnectCounter = 0;

		private TeiresiasClient() {
			try {
				this._clientSocket = new DatagramSocket(TEIRESIAS_PORT);
				Teiresias.this._localMaster = true;
			} catch (SocketException e) {
				if (DEBUG_MODE) {
					LOGGER.log(Level.SEVERE, "An exception occured while binding: " + e.getMessage());
				}
				this.initializeSlave();
			}
			this.setDaemon(true);
			this._aliveKeeper = Executors.newSingleThreadScheduledExecutor();
			this._workerThreadPool = Executors.newFixedThreadPool(NUMBER_WORKER_THREADS);
			this._callbackExecutor = Executors.newSingleThreadExecutor();
		}

		private void initializeSlave() {
			Teiresias.this._localMaster = false;
			for (int i = 0; i < 3; i++) {
				int port = (int) (Math.random() * 10000);
				LOGGER.log(Level.INFO, "Try to initialize Teiresias slave on port {0}", port);
				try {
					this._clientSocket = new DatagramSocket(port);
					Teiresias.this.sendImHere(port);
					this._localSlavePort = port;
					break;
				} catch (SocketException e) {
					LOGGER.log(Level.WARNING, "Try {0} of 3 failed.", i);
				}
			}
			if (this._localSlavePort == null) {
				throw new IllegalStateException("It was not possible to create a Teiresias client.");
			}
			LOGGER.log(Level.INFO, "Initialized slave on port " + this._localSlavePort);
		}

		@Override
		public void interrupt() {
			this._aliveKeeper.shutdown();
			this._workerThreadPool.shutdown();
			this._callbackExecutor.shutdown();
			super.interrupt();
		}

		@Override
		public void run() {
			if (Teiresias.this._localMaster) {
				this._aliveKeeper.scheduleAtFixedRate(new Runnable() {
					@Override
					public void run() {
						TeiresiasClient.this.keepAlive();
					}
				}, INITIAL_KEEP_ALIVE_DELAY, KEEP_ALIVE_PERIOD, TimeUnit.SECONDS);
			}
			while (!Thread.interrupted()) {
				final DatagramPacket packet = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
				try {
					this._clientSocket.receive(packet);
				} catch (IOException e) {
					LOGGER.log(Level.SEVERE, "An exception occured while receiving packet. " + e.getMessage());
				}
				try {
					this._workerThreadPool.execute(new Runnable() {
						@Override
						public void run() {
							TeiresiasClient.this.handlePacket(packet);
						}
					});
				} catch (RejectedExecutionException ree) {
					LOGGER.log(Level.WARNING, "Packet handling was rejected.");
				}
			}
			LOGGER.log(Level.INFO, "Shutting down client.");
		}

		private void handlePacket(DatagramPacket packet) {
			InetAddress address = packet.getAddress();
			int port = packet.getPort();
			int len = packet.getLength();
			byte[] data = packet.getData();

			LOGGER.log(Level.INFO, String.format("Received packet from %s:%d of length %d", address, port, len));
			byte[] content = new byte[len];
			System.arraycopy(data, 0, content, 0, len);
			TeiresiasMessage message = null;
			try {
				message = TeiresiasMessage.ADAPTER.decode(content);
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE,
						"Received message could not be parsed! " + ByteString.of(content).toAsciiLowercase());
				return;
			}
			this.handleMessage(message, address);
		}

		private void handleMessage(TeiresiasMessage message, InetAddress sender) {
			LOGGER.log(Level.INFO, "Handling message of type " + message.type.name());
			switch (message.type) {
			case WHOS_ALIVE:
				this.handleWhosAlive(sender);
				break;
			case IM_ALIVE:
				this.handleImAlive(sender);
				break;
			case PING:
				this.handlePing(sender);
				break;
			case PONG:
				this.handlePong(sender);
				break;
			case IM_HERE:
				this.handleImHere(sender, message.imHere);
				break;
			case IM_DEAD:
				this.handleImDead(sender);
				break;
			case DATA:
				this.handleDataMessage(message.data, sender);
				break;
			default:
				break;
			}
		}

		private void handleImDead(InetAddress sender) {
			if (!Teiresias.this._myAddress.equals(sender) && !LOCALHOST.equals(sender.getHostAddress())) {
				LOGGER.log(Level.WARNING,
						"Security alert: received request for local propagation from foreign machine: "
								+ sender.getHostAddress());
				return;
			}
			Teiresias.this.reinitClient();
		}

		private void handleImHere(InetAddress sender, ImHere imHere) {
			if (!Teiresias.this._myAddress.equals(sender) && !LOCALHOST.equals(sender.getHostAddress())) {
				LOGGER.log(Level.WARNING,
						"Security alert: received request for local propagation from foreign machine: "
								+ sender.getHostAddress());
				return;
			}
			LOGGER.log(Level.INFO, "Discovered peer on local machine. Will propagate.");
			Teiresias.this._localPortsToPropagateTo.add(imHere.port);
		}

		private synchronized void keepAlive() {
			this.disconnectCounter++;
			if (Teiresias.this._peersAlive.size() > 0) {
				Set<InetAddress> knownPeers = Teiresias.this._peersAlive;
				Teiresias.this._peersAlive = new HashSet<>();
				for (InetAddress peer : knownPeers) {
					Teiresias.this.sendPing(peer);
				}
			}
			if (this.disconnectCounter % 6 == 0) {
				this.disconnectCounter = 0;
				Teiresias.this.sendWhosAlive();
			}
		}

		private synchronized void handleImAlive(InetAddress sender) {
			this.addPeer(sender);
		}

		private synchronized void handlePong(InetAddress sender) {
			this.addPeer(sender);
		}

		private synchronized void handlePing(InetAddress sender) {
			Teiresias.this.sendPong(sender);
			this.addPeer(sender);
		}

		private synchronized void handleWhosAlive(InetAddress sender) {
			if (!Teiresias.this._myAddress.equals(sender)) {
				Teiresias.this.sendImAlive(sender);
				this.addPeer(sender);
			}
		}

		private void addPeer(InetAddress sender) {
			if (!sender.equals(Teiresias.this._myAddress) && !LOCALHOST.equals(sender.getHostAddress())) {
				Teiresias.this._peersAlive.add(sender);
			}
		}

		private synchronized void handleDataMessage(DataBody body, InetAddress sender) {
			if (!Teiresias.this._knownUUIDs.contains(body.UUID)) {
				Teiresias.this.addKnownUUID(body.UUID);
				this.handleDataMessageContent(body.content, body.targetApp, sender);
			} else {
				LOGGER.log(Level.INFO, "Ignoring message since UUID {0} is already known.", body.UUID);
			}
			if (Teiresias.this._localMaster) {
				// this needs to be done independently from knownUUIDs since
				// otherwise it would not be possible for local masters to send
				// responses to their clients
				this.propagateMessage(body);
			}
		}

		private synchronized void propagateMessage(DataBody body) {
			List<InetAddress> knownReceivers = this.getKnownReceivers(body.knownReceivers);
			List<String> knownReceiversAsReceived = new ArrayList<>(body.knownReceivers);
			LOGGER.log(Level.INFO, "Message had {0} receivers up to now.", knownReceiversAsReceived.size());
			LOGGER.log(Level.INFO, "Reduced to {0} receivers.", knownReceivers.size());
			knownReceiversAsReceived.add(Teiresias.this._myAddress.getHostAddress());
			knownReceivers.add(Teiresias.this._myAddress);
			TeiresiasMessage message = new TeiresiasMessage.Builder().type(MessageType.DATA)
					.data(new DataBody.Builder().UUID(body.UUID).content(body.content)
							.knownReceivers(knownReceiversAsReceived).targetApp(body.targetApp).build())
					.build();
			this.sendToPeersAlive(message, knownReceivers);
			if (!body.knownReceivers.contains(Teiresias.this._myAddress)) {
				this.sendToLocalPeers(message);
			}
		}

		private List<InetAddress> getKnownReceivers(List<String> addresses) {
			List<InetAddress> knownReceivers = new ArrayList<>();
			for (String address : addresses) {
				try {
					InetAddress receiver = InetAddress.getByName(address);
					knownReceivers.add(receiver);
				} catch (UnknownHostException e) {
					// skip
				}
			}
			return knownReceivers;
		}

		private void sendToLocalPeers(TeiresiasMessage message) {
			for (Integer port : Teiresias.this._localPortsToPropagateTo) {
				LOGGER.log(Level.INFO, "Propagating message to local port " + port);
				Teiresias.this.propagateToPort(message, port);
			}
		}

		private void handleDataMessageContent(final ByteString content, List<String> targetApps,
				final InetAddress sender) {
			if (targetApps == null) {
				return;
			}
			for (String app : targetApps) {
				final MessageDelegate delegate = Teiresias.this._messageDelegateMap.get(app);
				if (delegate != null) {
					try {
						this._callbackExecutor.execute(new Runnable() {
							@Override
							public void run() {
								delegate.onMessage(content.toByteArray(), sender);
							}
						});
					} catch (RejectedExecutionException ree) {
						LOGGER.log(Level.WARNING, "Data message handling was rejected.");
					}
				}
			}
		}

		private synchronized void sendToPeersAlive(TeiresiasMessage message, List<InetAddress> excludeReceivers) {
			for (InetAddress ipAddress : Teiresias.this._peersAlive) {
				if (ipAddress != null && !excludeReceivers.contains(ipAddress)) {
					Teiresias.this.sendMessage(message, ipAddress);
				}
			}
		}
	}
}
