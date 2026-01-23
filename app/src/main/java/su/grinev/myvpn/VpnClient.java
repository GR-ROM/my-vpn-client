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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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

import su.grinev.BsonMapper;
import su.grinev.model.Command;
import su.grinev.model.Packet;
import su.grinev.model.RequestDto;
import su.grinev.model.ResponseDto;
import su.grinev.model.VpnForwardPacketRequestDto;
import su.grinev.model.VpnIpResponseDto;
import su.grinev.model.VpnLoginRequestDto;
import su.grinev.myvpn.keepalive.KeepAliveManager;

public class VpnClient {
    private static final int TIMEOUT = 10;
    private final String serverAddress;
    private final int serverPort;
    private final String jwt;
    private final BsonMapper objectMapper;
    private final SSLContext sslContext;
    private final Consumer<byte[]> onClientPacketHandler;
    private final ExecutorService executor;
    private final Consumer<String> onIpAssigned;
    private final Consumer<State> onStateChange;
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
    public volatile String assignedIp;
    public volatile byte[] assignedIpBytes;

    public VpnClient(
            String serverAddress,
            int serverPort,
            String jwt,
            Consumer<byte[]> onClientPacket,
            Consumer<String> onIpAssigned,
            Consumer<State> onStateChange) throws IOException, InterruptedException {
        this.jwt = jwt;
        this.onClientPacketHandler = onClientPacket;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.state = DISCONNECTED;
        this.onIpAssigned = onIpAssigned;
        this.objectMapper = new BsonMapper(100, 1000, 4 * 1024, 64, null);
        this.onStateChange = onStateChange;

        // Initialize KeepAliveManager with callback for connection dead event
        this.keepAliveManager = new KeepAliveManager(this, objectMapper, this::onKeepAliveFailed);

        try {
            sslContext = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        TrustManager[] trustAllCerts = new TrustManager[]{
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
        onStateChange.accept(state);
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
            synchronized (stateLock) {
                if (timeout++ >= TIMEOUT) {
                    timeout = 0;
                    hasError = false;
                    state = DISCONNECTED;
                    DebugLog.log("Retry timeout reset");
                    return;
                }
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
                sslSocket = (SSLSocket) factory.createSocket();
                setupSocket(sslSocket);
                sslSocket.startHandshake();

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
            } catch (RuntimeException | IOException e) {
                DebugLog.log("Connection error: " + e.getMessage());
                handleError();
            } finally {
                keepAliveManager.stop();
                closeConnection();
                DebugLog.log("Connection closed");
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
                case LOGIN -> {
                    RequestDto<VpnLoginRequestDto> requestDto = RequestDto.wrap(Command.LOGIN, VpnLoginRequestDto.builder().jwt(jwt).build());
                    Packet<RequestDto<?>> packet = Packet.ofRequest(requestDto);
                    synchronized (connectionLock) {
                        objectMapper.serialize(packet, serverOutputStream);
                    }
                    DebugLog.log("Login request sent");
                    setState(AWAITING_LOGIN_RESPONSE);
                }
                case AWAITING_LOGIN_RESPONSE -> {
                    Packet<?> packet;
                    synchronized (connectionLock) {
                        packet = objectMapper.deserialize(serverInputStream, Packet.class);
                    }
                    ResponseDto<VpnIpResponseDto> responseDto = (ResponseDto<VpnIpResponseDto>) packet.getPayload();

                    if (responseDto.getStatus() == OK) {
                        DebugLog.log("Authenticated");
                        VpnIpResponseDto ipResponse = responseDto.getData();
                        if (ipResponse == null) {
                            DebugLog.log("No IP data in response");
                            setError(true);
                            setState(DISCONNECTED);
                            break;
                        }
                        assignedIp = intToIpv4(ipResponse.getIpAddress());
                        assignedIpBytes = ipv4ToIntBytes(assignedIp);
                        setState(LIVE);
                        DebugLog.log("Virtual IP: " + assignedIp);
                        onIpAssigned.accept(assignedIp);

                        synchronized (connectionLock) {
                            keepAliveManager.start(serverOutputStream);
                        }
                    } else {
                        DebugLog.log("Auth failed: " + responseDto.getStatus().name());
                        setError(true);
                        setState(SHUTDOWN);
                        return;
                    }
                }

                case LIVE -> {
                    DataInputStream inStream;
                    synchronized (connectionLock) {
                        inStream = serverInputStream;
                    }
                    if (inStream == null) {
                        setState(DISCONNECTED);
                        break;
                    }

                    Packet<?> packet = objectMapper.deserialize(inStream, Packet.class);
                    keepAliveManager.onPacketReceived();
                    if (packet.getPayload() instanceof ResponseDto<?>) {
                        keepAliveManager.onPongReceived();
                        continue;
                    }

                    RequestDto<VpnForwardPacketRequestDto> requestDto = (RequestDto<VpnForwardPacketRequestDto>) packet.getPayload();

                    if (requestDto.getCommand() == PING) {
                        Packet<ResponseDto<?>> response = Packet.ofResponse(ResponseDto.ofRequest(requestDto, OK));
                        synchronized (connectionLock) {
                            objectMapper.serialize(response, serverOutputStream);
                        }
                        continue;
                    }

                    if (requestDto.getCommand() == FORWARD_PACKET && requestDto.getData() instanceof VpnForwardPacketRequestDto vpnForwardPacketRequestDto) {
                        if (vpnForwardPacketRequestDto.getPacket().length > MAX_MTU) {
                            throw new IOException("Invalid packet length");
                        }
                        onClientPacketHandler.accept(vpnForwardPacketRequestDto.getPacket());
                    } else if (requestDto.getCommand() == DISCONNECT) {
                        DebugLog.log("Server requested disconnect");
                        setState(DISCONNECTED);
                    }
                }

                default -> {
                    DebugLog.log("Unknown state: " + getState());
                    setState(DISCONNECTED);
                }
            }
        }
    }

    private void setupSocket(SSLSocket socket) throws IOException {
        socket.setTcpNoDelay(false);
        socket.connect(new InetSocketAddress(serverAddress, serverPort));
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.setUseClientMode(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(30 * 1000);
    }

    private void handleError() {
        keepAliveManager.stop();
        synchronized (stateLock) {
            state = DISCONNECTED;
            hasError = true;
        }
    }

    private void onKeepAliveFailed() {
        DebugLog.log("KeepAlive failed, triggering reconnection");
        synchronized (stateLock) {
            hasError = true;
            state = DISCONNECTED;
        }
        onStateChange.accept(DISCONNECTED);
        closeConnection();
    }

    public void sendToServer(byte[] packet) {
        if (getState() != LIVE) {
            return;
        }

        RequestDto<VpnForwardPacketRequestDto> requestDto = RequestDto.wrap(FORWARD_PACKET, VpnForwardPacketRequestDto.builder().packet(packet).build());
        Packet<RequestDto<?>> packetDto = Packet.ofRequest(requestDto);

        try {
            synchronized (connectionLock) {
                objectMapper.serialize(packetDto, serverOutputStream);
            }
        } catch (RuntimeException | IOException ex) {
            DebugLog.log("Send error: " + ex.getMessage());
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

    public boolean isSocketConnected() {
        synchronized (connectionLock) {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }
    }

    private void closeConnection() {
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
        }
    }
}
