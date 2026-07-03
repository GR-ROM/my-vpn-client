package su.grinev.myvpn;

import static su.grinev.model.Command.DISCONNECT;
import static su.grinev.model.Command.FORWARD_PACKET;
import static su.grinev.model.Command.PING;
import static su.grinev.model.Command.REQUEST_LOGS;
import static su.grinev.model.Status.OK;
import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.NetUtils.ipv4ToIntBytes;
import static su.grinev.myvpn.State.AWAITING_HELLO_RESPONSE;
import static su.grinev.myvpn.State.AWAITING_LOGIN_RESPONSE;
import static su.grinev.myvpn.State.CONNECTED;
import static su.grinev.myvpn.State.CONNECTING;
import static su.grinev.myvpn.State.HELLO;
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
import java.time.LocalDate;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import su.grinev.Binder;
import su.grinev.Codec;
import su.grinev.model.Command;
import su.grinev.model.FlowAction;
import su.grinev.model.FlowControlRequestDto;
import su.grinev.model.HelloDto;
import su.grinev.model.Packet;
import su.grinev.model.PlatformType;
import su.grinev.model.RequestDto;
import su.grinev.model.ResponseDto;
import su.grinev.model.Status;
import su.grinev.model.CapabilityDto;
import su.grinev.model.VpnForwardPacketRequestDto;
import su.grinev.model.VpnIpResponseDto;
import su.grinev.model.VpnLoginRequestDto;
import su.grinev.model.FileType;
import su.grinev.model.FinalizeFileUploadDto;
import su.grinev.model.InitFileUploadDto;
import su.grinev.model.InitFileUploadResponseDto;
import su.grinev.model.RequestLogsDto;
import su.grinev.model.UploadFileChunkDto;
import su.grinev.myvpn.keepalive.KeepAliveManager;
import su.grinev.pool.PoolFactory;

public class VpnClient {
    public static final int BUFFER_SIZE = 2048;
    private static final int MAX_PACKET_SIZE = 65536;
    // Progressive reconnect backoff (server-unreachable case): start short — 200ms — so the first retry
    // right after a network-available event (the route may not be fully up yet → a transient
    // ENETUNREACH) recovers near-instantly; double up to a 60s cap. Reset on a successful connect or a
    // network-change event (forceReconnect). When there is no network link at all we don't back off —
    // we park and wake instantly on the next network event.
    private static final int MIN_BACKOFF_MS = 200;
    private static final int MAX_BACKOFF_MS = 60_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Cover SNI sent in the ClientHello so it looks like a browser visiting a popular,
     *  rarely-blocked site (defeats passive SNI filtering + adds the SNI extension to JA3).
     *  The server ignores SNI and serves its cert; the client is trust-all so the mismatch is fine. */
    private static final String COVER_SNI = "www.microsoft.com";
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
    // True when the device has a usable default network link. Lets us distinguish "server unreachable
    // but link is up" (→ progressive backoff) from "no network at all" (→ park, wake on event). May be
    // null (legacy: treated as always available). Supplied by VpnClientWrapper / DefaultNetworkMonitor.
    private final BooleanSupplier networkAvailable;
    private final String name;   // session label for logs (e.g. "s0"/"s1")
    private final KeepAliveManager keepAliveManager;
    private final Set<State> reconnectableStates = Set.of(DISCONNECTED, WAITING);
    private final Object stateLock = new Object();
    private final Object outputLock = new Object();
    private volatile State state;
    private volatile boolean hasError = false;
    private volatile int backoffMs = MIN_BACKOFF_MS;
    // Set by forceReconnect() (network-change event) to short-circuit the backoff/park: the next
    // WAITING tick reconnects immediately and resets the backoff. Cleared once consumed.
    private volatile boolean forceReconnectRequested = false;
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

    // Client app version reported in the pre-auth HELLO (server spec §18). MUST be >= the server's
    // MIN_CLIENT_VERSION (currently 1.0.4) or the server rejects the HELLO with OUTDATED.
    private static final String CLIENT_VERSION = BuildConfig.VERSION_NAME;

    // Pre-allocated for the pre-auth HELLO (single-threaded: VpnClientWorker thread only)
    private final HelloDto helloDto = new HelloDto();
    private final RequestDto<HelloDto> helloRequestDto = new RequestDto<>();
    private final Packet<RequestDto<?>> helloPacketDto = new Packet<>();
    // Set once the server acknowledges HELLO; gates sending the full capability contract at LOGIN.
    private volatile boolean helloAcked = false;

    // Pre-allocated for FLOW_CONTROL (sent from lifecycle callbacks; serialized under outputLock)
    private final FlowControlRequestDto flowControlDto = new FlowControlRequestDto();
    private final RequestDto<FlowControlRequestDto> flowControlRequestDto = new RequestDto<>();
    private final Packet<RequestDto<?>> flowControlPacketDto = new Packet<>();

    // Pre-allocated read buffer and ByteBuffer view (single-threaded: VpnClientWorker thread only)
    private final byte[] readBuffer = new byte[MAX_PACKET_SIZE];
    private final ByteBuffer readByteBuffer = ByteBuffer.wrap(readBuffer);

    // --- Server-initiated log upload (REQUEST_LOGS) ---
    private static final int UPLOAD_CHUNK_SIZE = 12 * 1024;            // frame stays under the 16 KiB wire limit
    private static final int UPLOAD_CODEC_BUFFER_SIZE = 32 * 1024;     // own codec: the shared one is sized for tiny control msgs
    private static final int UPLOAD_RESPONSE_TIMEOUT_MS = 30_000;
    private final Codec uploadCodec;
    // The read loop routes a ResponseDto whose requestId == pendingUploadSeq into uploadResponses; the
    // worker thread drains them. uploadSeq stays >= 1 so it never collides with the keepalive PONG
    // (PING seq = 0). Only one upload runs at a time (uploading guard).
    private final BlockingQueue<ResponseDto<?>> uploadResponses = new ArrayBlockingQueue<>(4);
    private final AtomicInteger uploadSeq = new AtomicInteger(0);
    private volatile int pendingUploadSeq = -1;
    private volatile boolean uploading = false;

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
            Consumer<ByteBuffer> bufferReleaser,
            BooleanSupplier networkAvailable,
            String name) throws IOException, InterruptedException {
        this.jwt = jwt;
        this.bufferReleaser = bufferReleaser;
        this.onClientPacketHandler = onClientPacket;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.state = DISCONNECTED;
        this.onIpAssigned = onIpAssigned;
        this.socketProtector = socketProtector;
        this.networkAvailable = networkAvailable;
        this.name = name;
        this.codec = Codec.messagePack(poolFactory, BUFFER_SIZE, Binder.ClassNameMode.SIMPLE_NAME);
        this.uploadCodec = Codec.messagePack(poolFactory, UPLOAD_CODEC_BUFFER_SIZE, Binder.ClassNameMode.SIMPLE_NAME);
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

        // Pre-auth HELLO: report app version + platform + supported LOGIN versions (server spec §18).
        helloDto.setVersion(CLIENT_VERSION);
        helloDto.setPlatformType(PlatformType.ANDROID);
        // Send the FULL capability contract in HELLO — the server reads HELLO caps to build the
        // session contract (and replies with only LOGIN versions for anti-fingerprint). LOGIN itself
        // carries no caps.
        helloDto.setCapabilities(ClientCapabilities.full());
        helloRequestDto.setCommand(Command.HELLO);
        helloRequestDto.setResponseRequired(true);
        helloRequestDto.setData(helloDto);
        helloPacketDto.setVer("0.2");
        helloPacketDto.setPayload(helloRequestDto);

        // FLOW_CONTROL: pause/resume the downlink without re-login (fire-and-forget).
        flowControlRequestDto.setCommand(Command.FLOW_CONTROL);
        flowControlRequestDto.setData(flowControlDto);
        flowControlPacketDto.setVer("0.2");
        flowControlPacketDto.setPayload(flowControlRequestDto);

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
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public State getState() {
        return state;
    }

    /** True when the device has a usable default network (legacy null supplier → always true). */
    private boolean isNetworkUp() {
        return networkAvailable == null || networkAvailable.getAsBoolean();
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
            if (isNetworkUp()) {
                // Link is up but the server is unreachable (it blinked, or the route isn't fully up yet
                // right after a network event) → progressive backoff starting at 200ms.
                int waitMs = backoffMs;
                DebugLog.log("[" + name + "] reconnect in " + waitMs + "ms (server unreachable)");
                synchronized (this) {
                    if (getState() == WAITING && !forceReconnectRequested && isNetworkUp()) {
                        this.wait(waitMs);
                    }
                }
                if (!forceReconnectRequested) {
                    backoffMs = ReconnectBackoff.next(backoffMs, MAX_BACKOFF_MS);
                }
            } else {
                // No network link at all → park; wake instantly on the next network event (forceReconnect).
                DebugLog.log("[" + name + "] no network — parking until it returns");
                synchronized (this) {
                    if (getState() == WAITING && !forceReconnectRequested && !isNetworkUp()) {
                        this.wait();
                    }
                }
            }
            synchronized (stateLock) {
                if (state == WAITING) {
                    forceReconnectRequested = false;
                    hasError = false;
                    state = DISCONNECTED;   // -> next loop sets CONNECTING and retries
                }
            }
            return;
        }

        if (getState() == CONNECTING) {
            DataOutputStream outStream;
            DataInputStream inStream;
            SSLSocket sslSocket;

            if (!isNetworkUp()) {
                // No usable network — don't hammer an unreachable default; defer to WAITING (which parks).
                DebugLog.log("[" + name + "] no network — deferring connect");
                handleError();
                return;
            }

            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                DebugLog.log("[" + name + "] Connecting to " + serverAddress + ":" + serverPort);

                // Connect over the system default network (protected from our own TUN). Multisession =
                // N such connections; the OS picks the best network (Wi-Fi/cellular) and a network change
                // triggers an immediate reconnect (see VpnClientWrapper). Use a LOCAL for setup: a
                // concurrent forceReconnect()/closeConnection() may null the rawSocket field mid-setup —
                // operating on the local avoids an NPE (a closed socket just throws on connect → retry).
                Socket rawSock = new Socket();
                rawSocket = rawSock;
                socketProtector.accept(rawSock);
                rawSock.setTcpNoDelay(true);
                rawSock.setKeepAlive(true);
                rawSock.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                rawSock.connect(new InetSocketAddress(serverAddress, serverPort), CONNECT_TIMEOUT_MS);

                sslSocket = (SSLSocket) factory.createSocket(rawSock, serverAddress, serverPort, true);
                sslSocket.setUseClientMode(true);
                // Browser-like ClientHello to pass DPI JA3 fingerprinting: let Conscrypt (BoringSSL —
                // the same engine Chrome-Android uses) offer its default cipher/protocol/GREASE set
                // instead of a tell-tale TLS1.3-only, 2-cipher list, and advertise ALPN h2/http1.1 like
                // a browser. The server selects http/1.1; the VPN protocol rides inside TLS regardless
                // of the negotiated ALPN value.
                SSLParameters sslParams = sslSocket.getSSLParameters();
                sslParams.setApplicationProtocols(new String[]{"h2", "http/1.1"});
                sslParams.setServerNames(List.of(new SNIHostName(COVER_SNI)));
                sslSocket.setSSLParameters(sslParams);
                sslSocket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                sslSocket.startHandshake();
                DebugLog.log("TLS handshake complete");

                outStream = new DataOutputStream(sslSocket.getOutputStream());
                inStream = new DataInputStream(sslSocket.getInputStream());

                socket = sslSocket;
                serverOutputStream = outStream;
                serverInputStream = inStream;

                helloAcked = false;
                setState(HELLO);
                onStateChange.accept(CONNECTED);

                runProtocolLoop();
            } catch (Throwable e) {
                DebugLog.log("[ERROR] Connection error: " + e.getClass().getName() + ": " + e.getMessage()
                        + "\n" + android.util.Log.getStackTraceString(e));
                handleError();
            } finally {
                keepAliveManager.stop();
                closeConnection();
            }
        }
    }

    private void runProtocolLoop() throws IOException {
        if (serverInputStream == null || serverOutputStream == null) {
            setError(true);
            setState(ERROR);
            return;
        }

        while (getState() != DISCONNECTED && getState() != SHUTDOWN) {
            switch (getState()) {
                case HELLO -> {
                    helloPacketDto.setTimestamp(FIXED_TIMESTAMP);
                    synchronized (outputLock) {
                        codec.serialize(helloPacketDto, serverOutputStream);
                    }
                    DebugLog.log("HELLO sent (version=" + CLIENT_VERSION + ", platform=ANDROID)");
                    setState(AWAITING_HELLO_RESPONSE);
                }
                case AWAITING_HELLO_RESPONSE -> {
                    try {
                        Packet<?> packet = readPacket(Packet.class);
                        ResponseDto<?> responseDto = (ResponseDto<?>) packet.getPayload();
                        if (responseDto.getStatus() == OK) {
                            helloAcked = true;
                            DebugLog.log("[HELLO] Acknowledged — proceeding to LOGIN with capabilities");
                            setState(LOGIN);
                        } else if (responseDto.getStatus() == Status.OUTDATED) {
                            DebugLog.log("[HELLO] Client OUTDATED (version " + CLIENT_VERSION
                                    + " below server minimum) — update required, not reconnecting");
                            setError(true);
                            setState(SHUTDOWN);
                            return;
                        } else {
                            DebugLog.log("[HELLO] Unexpected status " + responseDto.getStatus()
                                    + " — falling back to legacy LOGIN");
                            helloAcked = false;
                            setState(LOGIN);
                        }
                    } catch (SocketTimeoutException e) {
                        // Server didn't answer HELLO (e.g. an older node) — proceed with a legacy LOGIN.
                        DebugLog.log("[HELLO] No response (timeout) — falling back to legacy LOGIN");
                        helloAcked = false;
                        setState(LOGIN);
                    }
                }
                case LOGIN -> {
                    loginDto.setJwt(jwt);
                    // Capabilities travel in HELLO, not here — VpnLoginRequestDto carries only the JWT.
                    loginPacketDto.setTimestamp(FIXED_TIMESTAMP);
                    synchronized (outputLock) {
                        codec.serialize(loginPacketDto, serverOutputStream);
                    }
                    DebugLog.log("Login request sent" + (helloAcked ? " (with capabilities)" : " (legacy)"));
                    setState(AWAITING_LOGIN_RESPONSE);
                }
                case AWAITING_LOGIN_RESPONSE -> {
                    Packet<?> packet = readPacket(Packet.class);
                    ResponseDto<VpnIpResponseDto> responseDto = (ResponseDto<VpnIpResponseDto>) packet.getPayload();

                    if (responseDto.getStatus() == OK) {
                        DebugLog.log("[AUTH] Authenticated OK");
                        VpnIpResponseDto ipResponse = responseDto.getData();
                        if (ipResponse == null) {
                            setError(true);
                            setState(DISCONNECTED);
                            break;
                        }
                        DebugLog.log("[CAPS] server caps: " + describeCaps(ipResponse.getCapabilities()));
                        assignedIp = intToIpv4(ipResponse.getIpAddress());
                        assignedIpBytes = ipv4ToIntBytes(assignedIp);

                        backoffMs = MIN_BACKOFF_MS;   // connected → reset reconnect backoff
                        setState(LIVE);
                        onIpAssigned.accept(ipResponse);

                        if (serverOutputStream == null) {
                            break;
                        }
                        keepAliveManager.start(serverOutputStream);
                    } else {
                        DebugLog.log("[AUTH] Auth failed: " + responseDto.getStatus().name());
                        setError(true);
                        setState(SHUTDOWN);
                        return;
                    }
                }

                case LIVE -> {
                    if (serverInputStream == null) {
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

                    if (packet.getPayload() instanceof ResponseDto<?> responseDto) {
                        // A ResponseDto is either our keepalive PONG or a reply to an in-flight log-upload
                        // request — route by requestId so the upload worker gets exactly its responses.
                        if (pendingUploadSeq != -1 && responseDto.getRequestId() == pendingUploadSeq) {
                            uploadResponses.offer(responseDto);
                        } else {
                            keepAliveManager.onPongReceived();
                        }
                        continue;
                    }

                    RequestDto<?> requestDto = (RequestDto<?>) packet.getPayload();

                    if (requestDto.getCommand() == PING) {
                        pongResponseDto.setRequestId(requestDto.getSeq());
                        pongPacketDto.setTimestamp(FIXED_TIMESTAMP);
                        synchronized (outputLock) {
                            codec.serialize(pongPacketDto, serverOutputStream);
                        }
                        continue;
                    }

                    if (requestDto.getCommand() == REQUEST_LOGS) {
                        String date = requestDto.getData() instanceof RequestLogsDto r ? r.getDate() : null;
                        startLogUpload(date);
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
                    setState(DISCONNECTED);
                }
            }
        }
    }

    // Read one length-prefixed frame into readBuffer (length header lands at [0..4)); returns length.
    private int readFrame() throws IOException {
        // readInt() would issue four single-byte read() calls, each taking Conscrypt's
        // stream lock — read the header straight into readBuffer and parse it there.
        serverInputStream.readFully(readBuffer, 0, 4);
        int packetSize = ((readBuffer[0] & 0xFF) << 24)
                | ((readBuffer[1] & 0xFF) << 16)
                | ((readBuffer[2] & 0xFF) << 8)
                | (readBuffer[3] & 0xFF);
        if (packetSize <= 4 || packetSize > MAX_PACKET_SIZE) { throw new IOException("Invalid packet size: " + packetSize); }
        serverInputStream.readFully(readBuffer, 4, packetSize - 4);
        return packetSize;
    }

    private <T> T readPacket(Class<T> tClass) throws IOException {
        int packetSize = readFrame();
        readByteBuffer.position(0).limit(packetSize);
        return codec.deserialize(readByteBuffer, tClass);
    }

    // ===================== Server-initiated log upload (REQUEST_LOGS) =====================

    private void startLogUpload(String date) {
        if (uploading) {
            DebugLog.log("[LOGS] upload already in progress — ignoring REQUEST_LOGS");
            return;
        }
        uploading = true;
        Thread t = new Thread(() -> {
            try {
                uploadLogFile(date);
            } finally {
                uploading = false;
            }
        }, "LogUpload");
        t.setDaemon(true);
        t.start();
    }

    private void uploadLogFile(String date) {
        File file;
        String displayName;
        try {
            LocalDate day = (date == null || date.isEmpty()) ? LocalDate.now() : LocalDate.parse(date);
            file = FileLogger.fileFor(day);
            displayName = "myvpn-" + day + ".log";
        } catch (Exception e) {
            DebugLog.log("[LOGS] bad date '" + date + "': " + e.getMessage());
            return;
        }
        if (!file.isFile() || file.length() == 0) {
            DebugLog.log("[LOGS] no log file for " + (date == null || date.isEmpty() ? "today" : date));
            return;
        }
        long size = file.length();
        DebugLog.log("[LOGS] REQUEST_LOGS → uploading " + displayName + " (" + size + " bytes)");
        try (FileInputStream fis = new FileInputStream(file)) {
            ResponseDto<?> init = uploadRequest(Command.INIT_FILE_UPLOAD,
                    InitFileUploadDto.builder().size(size).name(displayName).type(FileType.LOG).build());
            if (init == null || init.getStatus() != Status.OK || !(init.getData() instanceof InitFileUploadResponseDto idr)) {
                DebugLog.log("[LOGS] init rejected: " + (init == null ? "timeout" : init.getStatus()));
                return;
            }
            long uploadId = idr.getUploadId();
            byte[] buf = new byte[UPLOAD_CHUNK_SIZE];
            long offset = 0;
            long remaining = size;
            int n;
            // Read EXACTLY the size declared at INIT. The live daily log file keeps growing while we
            // upload (FileLogger appends, including these [LOGS] lines), so reading past `size` would
            // exceed the declared length and the server rejects the chunk with IO_ERROR.
            while (remaining > 0 && (n = fis.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                ResponseDto<?> chunk = uploadRequest(Command.UPLOAD_FILE_CHUNK, UploadFileChunkDto.builder()
                        .uploadId(uploadId).offset(offset).chunkData(ByteBuffer.wrap(buf, 0, n)).build());
                if (chunk == null || chunk.getStatus() != Status.OK) {
                    DebugLog.log("[LOGS] chunk failed at " + offset + ": " + (chunk == null ? "timeout" : chunk.getStatus()));
                    return;
                }
                offset += n;
                remaining -= n;
            }
            ResponseDto<?> fin = uploadRequest(Command.FINALIZE_FILE_UPLOAD,
                    FinalizeFileUploadDto.builder().uploadId(uploadId).build());
            if (fin == null || fin.getStatus() != Status.OK) {
                DebugLog.log("[LOGS] finalize failed: " + (fin == null ? "timeout" : fin.getStatus()));
                return;
            }
            DebugLog.log("[LOGS] upload complete: " + displayName + " (" + offset + " bytes)");
        } catch (Exception e) {
            DebugLog.log("[LOGS] upload error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Send one upload request over the live connection and wait (seq-correlated) for its ResponseDto. */
    private ResponseDto<?> uploadRequest(Command command, Object data) throws InterruptedException {
        DataOutputStream out = serverOutputStream;
        if (out == null || getState() != LIVE) {
            return null;
        }
        int seq = uploadSeq.incrementAndGet();
        uploadResponses.clear();
        pendingUploadSeq = seq;

        RequestDto<Object> request = new RequestDto<>();
        request.setSeq(seq);
        request.setCommand(command);
        request.setResponseRequired(true);
        request.setData(data);
        Packet<Object> envelope = new Packet<>();
        envelope.setVer("0.2");
        envelope.setTimestamp(FIXED_TIMESTAMP);
        envelope.setPayload(request);

        try {
            synchronized (outputLock) {
                uploadCodec.serialize(envelope, out);
            }
        } catch (Exception e) {
            DebugLog.log("[LOGS] send error: " + e.getMessage());
            pendingUploadSeq = -1;
            return null;
        }
        ResponseDto<?> resp = uploadResponses.poll(UPLOAD_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        pendingUploadSeq = -1;
        return resp;
    }

    /** Compact one-line render of a capability contract, e.g. "LOGIN[0.1,0.2] FORWARD_PACKET[0.1,0.2]". */
    private static String describeCaps(List<CapabilityDto> caps) {
        if (caps == null || caps.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (CapabilityDto c : caps) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c.getName()).append('[');
            List<CapabilityDto.Version> vs = c.getVersion();
            for (int i = 0; vs != null && i < vs.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(vs.get(i).major()).append('.').append(vs.get(i).minor());
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private void handleError() {
        keepAliveManager.stop();
        synchronized (stateLock) {
            if (state == SHUTDOWN) return;
            state = DISCONNECTED;
            hasError = true;
        }
        onStateChange.accept(DISCONNECTED);
    }

    private void onKeepAliveFailed() {
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
            releaseQuietly(packet);   // queue full — drop (inner TCP retransmits)
        }
    }

    /**
     * Release a pool buffer without ever throwing. Pool bookkeeping exceptions (e.g. the
     * "Double release detected" IllegalStateException when the counter goes transiently negative
     * during the disconnect burst) must not escape: on the TUN reader (a plain thread) they crash
     * the app, and inside the batch-release loops they'd abandon the remaining buffers.
     */
    private void releaseQuietly(ByteBuffer buffer) {
        try {
            bufferReleaser.accept(buffer);
        } catch (RuntimeException e) {
            DebugLog.log("[" + name + "] buffer release failed (ignored): " + e);
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
                    releaseQuietly(batch.get(i));
                }
                batch.clear();
            }
        }
        ByteBuffer leftover;
        while ((leftover = sendQueue.poll()) != null) {
            releaseQuietly(leftover);   // release anything left on shutdown
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
        keepAliveManager.destroy();
        setState(SHUTDOWN);
        synchronized (this) {
            this.notifyAll();   // wake a worker parked in WAITING so it sees SHUTDOWN and exits
        }
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

    // Send a FLOW_CONTROL request (protocol 0.2): pause (STOP) / resume (START) the server→client
    // downlink without re-login. Fire-and-forget; only valid while LIVE. Serialized under outputLock,
    // so it is safe to call from a lifecycle thread alongside the keepalive/worker writers.
    public void sendFlowControl(FlowAction action) {
        if (getState() != LIVE) {
            return;
        }
        DataOutputStream out = serverOutputStream;
        if (out == null) {
            return;
        }
        try {
            synchronized (outputLock) {
                flowControlDto.setAction(action);
                flowControlRequestDto.setResponseRequired(false);
                flowControlPacketDto.setTimestamp(FIXED_TIMESTAMP);
                codec.serialize(flowControlPacketDto, out);
                out.flush();
            }
        } catch (Throwable e) {
            // Never let a control-message hiccup propagate (it must not crash a lifecycle callback).
            DebugLog.log("FLOW_CONTROL send failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public void pauseKeepAlive() {
        keepAliveManager.stop();
    }

    public void resumeKeepAlive() {
        if (serverOutputStream != null && getState() == LIVE) {
            keepAliveManager.start(serverOutputStream);
        }
    }

    /**
     * Reconnect immediately, skipping any backoff/park. Called on a default-network change (the link
     * just appeared or switched) so recovery is instant instead of waiting for a socket timeout or the
     * progressive backoff. Resets the backoff, wakes a parked/back-off worker, and drops the current
     * connection so it re-establishes on the new network.
     */
    public void forceReconnect() {
        if (getState() == SHUTDOWN) {
            return;
        }
        forceReconnectRequested = true;
        backoffMs = MIN_BACKOFF_MS;
        synchronized (this) {
            this.notifyAll();    // wake a worker sleeping in WAITING (backoff wait or park)
        }
        closeConnection();       // if connected, drop it so the worker reconnects on the new network
    }

    public void reprotectSocket() {
        if (rawSocket != null && !rawSocket.isClosed()) {
            socketProtector.accept(rawSocket);
        }
    }

    public boolean isSocketConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void closeConnection() {
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
