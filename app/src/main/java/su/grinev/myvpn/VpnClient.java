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
import java.time.Instant;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int SOCKET_READ_TIMEOUT_MS = 30_000;
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
    private final Object outputLock = new Object();
    private volatile State state;
    private volatile boolean hasError = false;
    private int timeout = 0;
    private volatile DataOutputStream serverOutputStream;
    private volatile DataInputStream serverInputStream;
    private volatile SSLSocket socket;
    private volatile Socket rawSocket;
    public volatile String assignedIp;
    public volatile byte[] assignedIpBytes;

    // Pre-allocated for send hot path (single-threaded: TunHandler reader thread only)
    private final VpnForwardPacketRequestDto sendForwardDto = new VpnForwardPacketRequestDto();
    private final RequestDto<VpnForwardPacketRequestDto> sendRequestDto = new RequestDto<>();
    private final Packet<RequestDto<?>> sendPacketDto = new Packet<>();

    // Pre-allocated for PONG response (single-threaded: VpnClientWorker thread only)
    private final ResponseDto<Void> pongResponseDto = new ResponseDto<>();
    private final Packet<ResponseDto<?>> pongPacketDto = new Packet<>();

    // Pre-allocated for LOGIN (single-threaded: VpnClientWorker thread only)
    private final VpnLoginRequestDto loginDto = new VpnLoginRequestDto();
    private final RequestDto<VpnLoginRequestDto> loginRequestDto = new RequestDto<>();
    private final Packet<RequestDto<?>> loginPacketDto = new Packet<>();

    // Pre-allocated read buffer and ByteBuffer view (single-threaded: VpnClientWorker thread only)
    private final byte[] readBuffer = new byte[MAX_PACKET_SIZE];
    private final ByteBuffer readByteBuffer = ByteBuffer.wrap(readBuffer);

    // Upload batching (mirrors the server/desktop pattern): the TUN reader hands pooled buffers
    // off here (ownership transfer); a dedicated writer thread drains a batch, packs all frames
    // into one buffer, does a single write + flush, then releases every buffer back to the pool
    // (dispose by the new owner). Coalescing many IP packets into one TLS write lifts upload
    // throughput vs one record/flush per packet.
    private static final int MAX_SEND_BATCH = 32;
    private static final int SEND_QUEUE_CAPACITY = 512;
    private final BlockingQueue<ByteBuffer> sendQueue = new ArrayBlockingQueue<>(SEND_QUEUE_CAPACITY);
    private final byte[] sendBatchScratch = new byte[MAX_SEND_BATCH * (MAX_MTU + 32)];
    private final Consumer<ByteBuffer> bufferReleaser;
    private final ExecutorService sendExecutor;

    private static final Instant FIXED_TIMESTAMP = Instant.now();

    public VpnClient(
            String serverAddress,
            int serverPort,
            String jwt,
            Consumer<ByteBuffer> onClientPacket,
            Consumer<VpnIpResponseDto> onIpAssigned,
            PoolFactory poolFactory,
            Consumer<State> onStateChange,
            Consumer<Socket> socketProtector,
            Consumer<ByteBuffer> bufferReleaser) throws IOException, InterruptedException {
        this.jwt = jwt;
        this.bufferReleaser = bufferReleaser;
        this.onClientPacketHandler = onClientPacket;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.state = DISCONNECTED;
        this.onIpAssigned = onIpAssigned;
        this.socketProtector = socketProtector;
        this.codec = Codec.messagePack(poolFactory, BUFFER_SIZE, Binder.ClassNameMode.SIMPLE_NAME);
        this.onStateChange = onStateChange;

        this.keepAliveManager = new KeepAliveManager(outputLock, codec, this::onKeepAliveFailed);

        sendRequestDto.setCommand(FORWARD_PACKET);
        sendRequestDto.setData(sendForwardDto);
        sendPacketDto.setVer("0.1");
        sendPacketDto.setPayload(sendRequestDto);

        pongResponseDto.setStatus(OK);
        pongPacketDto.setVer("0.1");
        pongPacketDto.setPayload(pongResponseDto);

        loginRequestDto.setCommand(Command.LOGIN);
        loginRequestDto.setData(loginDto);
        loginPacketDto.setVer("0.2");   // negotiate protocol v0.2 with the server at login
        loginPacketDto.setPayload(loginRequestDto);

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

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
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

        sendExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VpnSendWriter");
            t.setDaemon(false);
            return t;
        });
        sendExecutor.execute(this::runSendWriter);
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
        return state;
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
            if (timeout == 1 || timeout % 5 == 0) {
                DebugLog.log("Reconnect in " + (TIMEOUT - timeout) + "s...");
            }
            return;
        }

        if (getState() == CONNECTING) {
            DataOutputStream outStream;
            DataInputStream inStream;
            SSLSocket sslSocket;

            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                DebugLog.log("Connecting to " + serverAddress + ":" + serverPort);

                rawSocket = new Socket();
                socketProtector.accept(rawSocket);
                rawSocket.setTcpNoDelay(true);
                rawSocket.setKeepAlive(true);
                rawSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                rawSocket.connect(new InetSocketAddress(serverAddress, serverPort), CONNECT_TIMEOUT_MS);

                sslSocket = (SSLSocket) factory.createSocket(rawSocket, serverAddress, serverPort, true);
                sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
                sslSocket.setEnabledCipherSuites(new String[]{ "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384"});
                sslSocket.setUseClientMode(true);
                sslSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                sslSocket.startHandshake();
                DebugLog.log("TLS handshake complete");

                outStream = new DataOutputStream(sslSocket.getOutputStream());
                inStream = new DataInputStream(sslSocket.getInputStream());

                socket = sslSocket;
                serverOutputStream = outStream;
                serverInputStream = inStream;

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
                    loginDto.setJwt(jwt);
                    loginPacketDto.setTimestamp(FIXED_TIMESTAMP);
                    synchronized (outputLock) {
                        codec.serialize(loginPacketDto, serverOutputStream);
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
                        DebugLog.log("[AUTH] serverOutputStream=" + (serverOutputStream != null ? "OK" : "NULL")
                                + ", serverInputStream=" + (serverInputStream != null ? "OK" : "NULL")
                                + ", socket=" + (socket != null ? (socket.isClosed() ? "CLOSED" : "OK") : "NULL"));
                        if (serverOutputStream == null) {
                            DebugLog.log("[AUTH] serverOutputStream is NULL, cannot start KeepAlive!");
                            break;
                        }
                        keepAliveManager.start(serverOutputStream);
                        DebugLog.log("[AUTH] KeepAlive started, entering LIVE loop. State=" + getState());
                    } else {
                        DebugLog.log("[AUTH] Auth failed: " + responseDto.getStatus().name());
                        setError(true);
                        setState(SHUTDOWN);
                        return;
                    }
                }

                case LIVE -> {
                    if (serverInputStream == null) {
                        DebugLog.log("[LIVE] serverInputStream is NULL, disconnecting");
                        setState(DISCONNECTED);
                        break;
                    }

                    int len;
                    try {
                        len = readFrame();
                    } catch (SocketTimeoutException e) {
                        // Socket timeout is benign in LIVE state — KeepAlive handles
                        // dead connection detection. During sleep, no data is expected.
                        continue;
                    }
                    keepAliveManager.onPacketReceived();

                    // v0.2 fast path: minimal FORWARD frame { 0:"0.2", 1:"FWD", 2:<bin> } —
                    // extract the IP packet straight from readBuffer, no full deserialization.
                    if (ForwardV2Codec.isForward(readBuffer, len)) {
                        int off = ForwardV2Codec.packetOffset(readBuffer);
                        int binLen = ForwardV2Codec.packetLength(readBuffer);
                        if (off < 0 || binLen > MAX_MTU) {
                            throw new IOException("Invalid packet length");
                        }
                        readByteBuffer.limit(off + binLen).position(off);
                        onClientPacketHandler.accept(readByteBuffer);
                        continue;
                    }

                    readByteBuffer.position(0).limit(len);
                    Packet<?> packet = codec.deserialize(readByteBuffer, Packet.class);
                    if (packet.getPayload() instanceof ResponseDto<?>) {
                        keepAliveManager.onPongReceived();
                        continue;
                    }

                    RequestDto<VpnForwardPacketRequestDto> requestDto = (RequestDto<VpnForwardPacketRequestDto>) packet.getPayload();

                    if (requestDto.getCommand() == PING) {
                        pongResponseDto.setRequestId(requestDto.getSeq());
                        pongPacketDto.setTimestamp(FIXED_TIMESTAMP);
                        synchronized (outputLock) {
                            codec.serialize(pongPacketDto, serverOutputStream);
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

    // Read one length-prefixed frame into readBuffer (length header re-written at [0..4)); returns length.
    private int readFrame() throws IOException {
        int packetSize = serverInputStream.readInt();
        if (packetSize <= 4 || packetSize > MAX_PACKET_SIZE) { throw new IOException("Invalid packet size: " + packetSize); }
        readBuffer[0] = (byte) (packetSize >> 24);
        readBuffer[1] = (byte) (packetSize >> 16);
        readBuffer[2] = (byte) (packetSize >> 8);
        readBuffer[3] = (byte) packetSize;
        serverInputStream.readFully(readBuffer, 4, packetSize - 4);
        return packetSize;
    }

    private <T> T readPacket(Class<T> tClass) throws IOException {
        int packetSize = readFrame();
        readByteBuffer.position(0).limit(packetSize);
        return codec.deserialize(readByteBuffer, tClass);
    }

    private void handleError() {
        DebugLog.log("handleError: " + Thread.currentThread().getName());
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

    // Called from the TUN reader thread; takes ownership of {@code packet}. It is queued for the
    // send writer (which releases it after sending) or released here if the queue is full.
    public void sendToServer(ByteBuffer packet) {
        if (!sendQueue.offer(packet)) {
            bufferReleaser.accept(packet);   // queue full — drop (inner TCP retransmits)
        }
    }

    // Dedicated writer: drain a batch, pack all frames into one buffer, single write + flush
    // (Conscrypt buffers TLS writes, so the per-batch flush is what pushes them out), then release
    // every buffer back to the pool.
    private void runSendWriter() {
        List<ByteBuffer> batch = new ArrayList<>(MAX_SEND_BATCH);
        while (getState() != SHUTDOWN) {
            ByteBuffer first;
            try {
                first = sendQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            batch.add(first);
            sendQueue.drainTo(batch, MAX_SEND_BATCH - 1);
            try {
                writeBatch(batch);
            } catch (RuntimeException | IOException ex) {
                DebugLog.log("Send batch error: " + ex.getClass().getName() + ": " + ex.getMessage());
                handleError();
            } finally {
                for (int i = 0; i < batch.size(); i++) {
                    bufferReleaser.accept(batch.get(i));
                }
                batch.clear();
            }
        }
        ByteBuffer leftover;
        while ((leftover = sendQueue.poll()) != null) {
            bufferReleaser.accept(leftover);   // release anything left on shutdown
        }
    }

    private void writeBatch(List<ByteBuffer> batch) throws IOException {
        DataOutputStream out = serverOutputStream;
        if (out == null) {
            return;   // not connected — buffers released by the caller's finally
        }
        int pos = 0;
        for (int i = 0; i < batch.size(); i++) {
            pos += ForwardV2Codec.encode(sendBatchScratch, pos, batch.get(i));
        }
        synchronized (outputLock) {
            out.write(sendBatchScratch, 0, pos);
            out.flush();
        }
    }

    public void stop() {
        DebugLog.log("Stopping VPN client");
        keepAliveManager.destroy();
        setState(SHUTDOWN);
        sendExecutor.shutdownNow();   // interrupt the send writer (it drains+releases on exit)
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
        if (serverOutputStream != null && getState() == LIVE) {
            keepAliveManager.start(serverOutputStream);
            DebugLog.log("KeepAlive resumed after sleep");
        }
    }

    public void reprotectSocket() {
        if (rawSocket != null && !rawSocket.isClosed()) {
            socketProtector.accept(rawSocket);
            DebugLog.log("Raw socket re-protected (post-tunnel)");
        }
    }

    public boolean isSocketConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void closeConnection() {
        DebugLog.log("[CLOSE] closeConnection called from " + Thread.currentThread().getName());

        // Close the socket FIRST, outside of outputLock. Socket.close() is thread-safe and
        // is the only way to unblock a writer thread stuck in codec.serialize(...) while
        // holding outputLock — if we waited for the lock here we'd deadlock against it.
        SSLSocket localSocket = socket;
        if (localSocket != null) {
            try {
                localSocket.close();
            } catch (IOException ignored) {}
        }
        Socket localRawSocket = rawSocket;
        if (localRawSocket != null) {
            try {
                localRawSocket.close();
            } catch (IOException ignored) {}
        }

        synchronized (outputLock) {
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

            socket = null;
            rawSocket = null;
        }
    }
}
