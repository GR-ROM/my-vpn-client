package su.grinev.myvpn;

import static su.grinev.model.Command.DISCONNECT;
import static su.grinev.model.Command.FORWARD_PACKET;
import static su.grinev.model.Command.PING;
import static su.grinev.model.Status.OK;
import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.NetUtils.ipv4ToIntBytes;
import static su.grinev.myvpn.State.AWAITING_LOGIN_RESPONSE;
import static su.grinev.myvpn.State.CONNECTED;
import static su.grinev.myvpn.State.CONNECTING;
import static su.grinev.myvpn.State.DISCONNECTED;
import static su.grinev.myvpn.State.ERROR;
import static su.grinev.myvpn.State.LIVE;
import static su.grinev.myvpn.State.LOGIN;
import static su.grinev.myvpn.State.SHUTDOWN;
import static su.grinev.myvpn.State.WAITING;
import static su.grinev.myvpn.TunHandler.MAX_MTU;

import android.annotation.SuppressLint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import su.grinev.Binder;
import su.grinev.Codec;
import su.grinev.model.Command;
import su.grinev.model.Packet;
import su.grinev.model.RequestDto;
import su.grinev.model.ResponseDto;
import su.grinev.model.VpnForwardPacketRequestDto;
import su.grinev.model.VpnIpResponseDto;
import su.grinev.model.VpnLoginRequestDto;
import su.grinev.myvpn.keepalive.KeepAliveManager;
import su.grinev.pool.PoolFactory;

public class VpnClient {
    public static final int BUFFER_SIZE = 2048;
    private static final int MAX_PACKET_SIZE = 65536;
    private static final int TIMEOUT = 10;
    private final String serverAddress;
    private final int serverPort;
    private final String jwt;
    private final Codec codec;
    private final SSLContext sslContext;
    private final Consumer<ByteBuffer> onClientPacketHandler;
    private final ExecutorService executor;
    private final Consumer<VpnIpResponseDto> onIpAssigned;
    private final Consumer<State> onStateChange;
    private final Consumer<java.net.Socket> socketProtector;
    private final KeepAliveManager keepAliveManager;
    private final Set<State> reconnectableStates = Set.of(DISCONNECTED, WAITING);
    private final Object stateLock = new Object();
    private volatile State state;
    private volatile boolean hasError = false;
    private int timeout = 0;
    private final Object connectionLock = new Object();
    private DataOutputStream serverOutputStream;
    private DataInputStream serverInputStream;
    private SSLSocket socket;
    private Socket rawSocket;
    public volatile String assignedIp;
    public volatile byte[] assignedIpBytes;

    // Pre-allocated for send hot path (single-threaded: TunHandler reader thread only)
    private final VpnForwardPacketRequestDto sendForwardDto = new VpnForwardPacketRequestDto();
    private final RequestDto<VpnForwardPacketRequestDto> sendRequestDto = new RequestDto<>();
    private final Packet<RequestDto<?>> sendPacketDto = new Packet<>();

    // Pre-allocated read buffer (single-threaded: VpnClientWorker thread only)
    private final byte[] readBuffer = new byte[MAX_PACKET_SIZE];

    public VpnClient(
            String serverAddress,
            int serverPort,
            String jwt,
            Consumer<ByteBuffer> onClientPacket,
            Consumer<VpnIpResponseDto> onIpAssigned,
            PoolFactory poolFactory,
            Consumer<State> onStateChange,
            Consumer<java.net.Socket> socketProtector) throws IOException, InterruptedException {
        this.jwt = jwt;
        this.onClientPacketHandler = onClientPacket;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.state = DISCONNECTED;
        this.onIpAssigned = onIpAssigned;
        this.socketProtector = socketProtector;
        this.codec = Codec.messagePack(poolFactory, BUFFER_SIZE, Binder.ClassNameMode.SIMPLE_NAME);
        this.onStateChange = onStateChange;

        this.keepAliveManager = new KeepAliveManager(this, codec, this::onKeepAliveFailed);

        // Wire up pre-allocated send wrappers
        sendRequestDto.setCommand(FORWARD_PACKET);
        sendRequestDto.setData(sendForwardDto);
        sendPacketDto.setVer("0.1");
        sendPacketDto.setPayload(sendRequestDto);

        try {
            sslContext = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        @SuppressLint("CustomX509TrustManager") TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
        };

        try {
            sslContext.init(null, trustAllCerts, new SecureRandom());
        } catch (KeyManagementException e) {
            throw new RuntimeException(e);
        }

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VpnClientWorker");
            t.setDaemon(false);
            return t;
        });

        CompletableFuture.runAsync(this::runWorkerLoop, executor);
    }

    private void runWorkerLoop() {
        while (getState() != SHUTDOWN) {
            try {
                run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                setState(SHUTDOWN);
                DebugLog.log("VPN client is shutdown");
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public State getState() {
        synchronized (stateLock) {
            return state;
        }
    }

    private void setState(State newState) {
        synchronized (stateLock) {
            state = newState;
        }
        onStateChange.accept(newState);
    }

    private void setError(boolean error) {
        synchronized (stateLock) {
            hasError = error;
        }
    }

    private void run() throws InterruptedException, NoSuchAlgorithmException, KeyManagementException {
        boolean errorFlag;

        synchronized (stateLock) {
            errorFlag = hasError;
            if (errorFlag) {
                if (reconnectableStates.contains(state)) {
                    setState(WAITING);
                }
            } else {
                if (reconnectableStates.contains(state)) {
                    setState(CONNECTING);
                }
            }
        }

        if (getState() == WAITING) {
            boolean timeoutReset = false;
            synchronized (stateLock) {
                if (timeout++ >= TIMEOUT) {
                    timeout = 0;
                    hasError = false;
                    state = DISCONNECTED;
                    timeoutReset = true;
                }
            }
            if (timeoutReset) {
                DebugLog.log("Retry timeout reset");
                onStateChange.accept(DISCONNECTED);
                return;
            }
            synchronized (this) {
                this.wait(1000);
            }
            DebugLog.log("Waiting " + timeout + "sec");
            return;
        }

        if (getState() == CONNECTING) {
            DataOutputStream outStream;
            DataInputStream inStream;
            SSLSocket sslSocket;

            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                DebugLog.log("Connecting to " + serverAddress + ":" + serverPort);

                // Create raw socket first and protect it BEFORE wrapping with SSL.
                // On Android 10, protect(SSLSocket) may not protect the underlying socket.
                rawSocket = new Socket();
                socketProtector.accept(rawSocket);
                DebugLog.log("Raw socket protected from VPN routing (pre-tunnel)");
                rawSocket.setTcpNoDelay(true);
                rawSocket.setKeepAlive(true);
                rawSocket.connect(new InetSocketAddress(serverAddress, serverPort));
                DebugLog.log("Raw socket connected");

                sslSocket = (SSLSocket) factory.createSocket(rawSocket, serverAddress, serverPort, true);
                sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
                sslSocket.setUseClientMode(true);
                sslSocket.setSoTimeout(30 * 1000);
                sslSocket.startHandshake();
                DebugLog.log("TLS handshake complete");

                outStream = new DataOutputStream(sslSocket.getOutputStream());
                inStream = new DataInputStream(sslSocket.getInputStream());

                synchronized (connectionLock) {
                    socket = sslSocket;
                    serverOutputStream = outStream;
                    serverInputStream = inStream;
                }

                setState(LOGIN);
                DebugLog.log("Connected");
                onStateChange.accept(CONNECTED);

                runProtocolLoop();
            } catch (Throwable e) {
                DebugLog.log("[ERROR] Connection error: " + e.getClass().getName() + ": " + e.getMessage()
                        + "\n" + android.util.Log.getStackTraceString(e));
                handleError();
            } finally {
                DebugLog.log("[FINALLY] entering finally block, state=" + getState());
                keepAliveManager.stop();
                closeConnection();
                DebugLog.log("Connection closed");
            }
        }
    }

    private void runProtocolLoop() throws IOException {
        DebugLog.log("[PROTO] runProtocolLoop entry, state=" + getState());
        if (serverInputStream == null || serverOutputStream == null) {
            DebugLog.log("[PROTO] streams null on entry, aborting");
            setError(true);
            setState(ERROR);
            return;
        }

        while (getState() != DISCONNECTED && getState() != SHUTDOWN) {
            switch (getState()) {
                case LOGIN -> {
                    RequestDto<VpnLoginRequestDto> requestDto = RequestDto.wrap(Command.LOGIN, VpnLoginRequestDto.builder().jwt(jwt).build());
                    Packet<RequestDto<?>> packet = Packet.ofRequest(requestDto);
                    synchronized (connectionLock) {
                        codec.serialize(packet, serverOutputStream);
                    }
                    DebugLog.log("Login request sent");
                    setState(AWAITING_LOGIN_RESPONSE);
                }
                case AWAITING_LOGIN_RESPONSE -> {
                    DebugLog.log("[AUTH] Reading login response...");
                    Packet<?> packet = readPacket(Packet.class);
                    DebugLog.log("[AUTH] Login response received");
                    ResponseDto<VpnIpResponseDto> responseDto = (ResponseDto<VpnIpResponseDto>) packet.getPayload();

                    if (responseDto.getStatus() == OK) {
                        DebugLog.log("[AUTH] Authenticated OK");
                        VpnIpResponseDto ipResponse = responseDto.getData();
                        if (ipResponse == null) {
                            DebugLog.log("[AUTH] No IP data in response");
                            setError(true);
                            setState(DISCONNECTED);
                            break;
                        }
                        assignedIp = intToIpv4(ipResponse.getIpAddress());
                        assignedIpBytes = ipv4ToIntBytes(assignedIp);
                        DebugLog.log("[AUTH] Virtual IP: " + assignedIp);

                        DebugLog.log("[AUTH] Setting state LIVE");
                        setState(LIVE);

                        DebugLog.log("[AUTH] Calling onIpAssigned (configureTun + TunHandler.start)...");
                        onIpAssigned.accept(ipResponse);
                        DebugLog.log("[AUTH] onIpAssigned returned");

                        DebugLog.log("[AUTH] State after onIpAssigned: " + getState());
                        synchronized (connectionLock) {
                            DebugLog.log("[AUTH] serverOutputStream=" + (serverOutputStream != null ? "OK" : "NULL")
                                    + ", serverInputStream=" + (serverInputStream != null ? "OK" : "NULL")
                                    + ", socket=" + (socket != null ? (socket.isClosed() ? "CLOSED" : "OK") : "NULL"));
                            if (serverOutputStream == null) {
                                DebugLog.log("[AUTH] serverOutputStream is NULL, cannot start KeepAlive!");
                                break;
                            }
                            keepAliveManager.start(serverOutputStream);
                        }
                        DebugLog.log("[AUTH] KeepAlive started, entering LIVE loop. State=" + getState());
                    } else {
                        DebugLog.log("[AUTH] Auth failed: " + responseDto.getStatus().name());
                        setError(true);
                        setState(SHUTDOWN);
                        return;
                    }
                }

                case LIVE -> {
                    synchronized (connectionLock) {
                        if (serverInputStream == null) {
                            DebugLog.log("[LIVE] serverInputStream is NULL, disconnecting");
                            setState(DISCONNECTED);
                            break;
                        }
                    }

                    Packet<?> packet;
                    try {
                        packet = readPacket(Packet.class);
                    } catch (SocketTimeoutException e) {
                        // Socket timeout is benign in LIVE state — KeepAlive handles
                        // dead connection detection. During sleep, no data is expected.
                        continue;
                    }
                    keepAliveManager.onPacketReceived();
                    if (packet.getPayload() instanceof ResponseDto<?>) {
                        keepAliveManager.onPongReceived();
                        continue;
                    }

                    RequestDto<VpnForwardPacketRequestDto> requestDto = (RequestDto<VpnForwardPacketRequestDto>) packet.getPayload();

                    if (requestDto.getCommand() == PING) {
                        Packet<ResponseDto<?>> response = Packet.ofResponse(ResponseDto.ofRequest(requestDto, OK));
                        synchronized (connectionLock) {
                            codec.serialize(response, serverOutputStream);
                        }
                        continue;
                    }

                    if (requestDto.getCommand() == FORWARD_PACKET && requestDto.getData() instanceof VpnForwardPacketRequestDto vpnForwardPacketRequestDto) {
                        ByteBuffer buf = vpnForwardPacketRequestDto.getPacket();
                        if (buf.remaining() > MAX_MTU) {
                            throw new IOException("Invalid packet length");
                        }
                        onClientPacketHandler.accept(buf);
                    } else if (requestDto.getCommand() == DISCONNECT) {
                        DebugLog.log("[LIVE] Server requested disconnect");
                        setState(DISCONNECTED);
                    }
                }

                default -> {
                    DebugLog.log("[PROTO] Unknown state: " + getState());
                    setState(DISCONNECTED);
                }
            }
        }
        DebugLog.log("[PROTO] Loop exited, state=" + getState());
    }

    private <T> T readPacket(Class<T> tClass) throws IOException {
        int packetSize = serverInputStream.readInt();
        if (packetSize <= 4 || packetSize > MAX_PACKET_SIZE) {
            throw new IOException("Invalid packet size: " + packetSize);
        }
        readBuffer[0] = (byte) (packetSize >> 24);
        readBuffer[1] = (byte) (packetSize >> 16);
        readBuffer[2] = (byte) (packetSize >> 8);
        readBuffer[3] = (byte) packetSize;
        serverInputStream.readFully(readBuffer, 4, packetSize - 4);
        return codec.deserialize(ByteBuffer.wrap(readBuffer, 0, packetSize), tClass);
    }

    private void handleError() {
        DebugLog.log("handleError called from: " + Thread.currentThread().getName()
                + "\n" + android.util.Log.getStackTraceString(new Throwable("handleError trace")));
        keepAliveManager.stop();
        synchronized (stateLock) {
            if (state == SHUTDOWN) return;
            state = DISCONNECTED;
            hasError = true;
        }
        onStateChange.accept(DISCONNECTED);
    }

    private void onKeepAliveFailed() {
        DebugLog.log("KeepAlive failed, triggering reconnection");
        synchronized (stateLock) {
            if (state == SHUTDOWN) return;
            hasError = true;
            state = DISCONNECTED;
        }
        onStateChange.accept(DISCONNECTED);
        closeConnection();
    }

    public void sendToServer(ByteBuffer packet) {
        if (getState() != LIVE) {
            return;
        }

        sendForwardDto.setPacket(packet);
        sendPacketDto.setTimestamp(Instant.now());

        try {
            synchronized (connectionLock) {
                codec.serialize(sendPacketDto, serverOutputStream);
            }
        } catch (RuntimeException | IOException ex) {
            DebugLog.log("Send error: " + ex.getClass().getName() + ": " + ex.getMessage()
                    + "\n" + android.util.Log.getStackTraceString(ex));
            handleError();
        }
    }

    public void stop() {
        DebugLog.log("Stopping VPN client");
        keepAliveManager.stop();
        setState(SHUTDOWN);
        closeConnection();

        executor.shutdown();
        try {
            if (!executor.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void pauseKeepAlive() {
        keepAliveManager.stop();
        DebugLog.log("KeepAlive paused for sleep");
    }

    public void resumeKeepAlive() {
        synchronized (connectionLock) {
            if (serverOutputStream != null && getState() == LIVE) {
                keepAliveManager.start(serverOutputStream);
                DebugLog.log("KeepAlive resumed after sleep");
            }
        }
    }

    public void reprotectSocket() {
        synchronized (connectionLock) {
            if (rawSocket != null && !rawSocket.isClosed()) {
                socketProtector.accept(rawSocket);
                DebugLog.log("Raw socket re-protected (post-tunnel)");
            }
        }
    }

    public boolean isSocketConnected() {
        synchronized (connectionLock) {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }
    }

    private void closeConnection() {
        DebugLog.log("[CLOSE] closeConnection called from " + Thread.currentThread().getName());
        synchronized (connectionLock) {
            if (serverOutputStream != null) {
                try {
                    serverOutputStream.close();
                } catch (IOException ignored) {}
                serverOutputStream = null;
            }

            if (serverInputStream != null) {
                try {
                    serverInputStream.close();
                } catch (IOException ignored) {}
                serverInputStream = null;
            }

            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {}
                socket = null;
            }
            rawSocket = null;
        }
    }
}
