package su.grinev.myvpn;

import static su.grinev.model.Command.DISCONNECT;
import static su.grinev.model.Command.FORWARD_PACKET;
import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.NetUtils.ipv4ToIntBytes;
import static su.grinev.myvpn.State.AWAITING_LOGIN_RESPONSE;
import static su.grinev.myvpn.State.CONNECTED;
import static su.grinev.myvpn.State.CONNECTING;
import static su.grinev.myvpn.State.DISCONNECTED;
import static su.grinev.myvpn.State.GET_IP;
import static su.grinev.myvpn.State.LIVE;
import static su.grinev.myvpn.State.LOGIN;
import static su.grinev.myvpn.State.SHUTDOWN;
import static su.grinev.myvpn.State.WAITING;
import static su.grinev.myvpn.StateUtils.advanceStateIfTrueOrElse;
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
import su.grinev.model.Status;
import su.grinev.model.VpnForwardPacketRequestDto;
import su.grinev.model.VpnIpResponseDto;
import su.grinev.model.VpnLoginRequestDto;

public class VpnClient {
    private static final int TIMEOUT = 10;
    private final String serverAddress;
    private final int serverPort;
    private DataOutputStream serverOutputStream;
    private DataInputStream serverInputStream;
    private final String jwt;
    public State state;
    private int timeout = 0;
    private boolean hasError = false;
    private final Set<State> disconnected = Set.of(DISCONNECTED, WAITING);
    private final BsonMapper objectMapper;
    private final SSLContext sslContext;
    private final Consumer<byte[]> onClientPacketHandler;
    public String assignedIp;
    public byte[] assignedIpBytes;
    private final Thread worker;
    private final Consumer<String> onIpAssigned;
    private final TunAndroid tunAndroid;
    private final Consumer<State> onStateChange;

    public VpnClient(
            String serverAddress,
            int serverPort,
            String jwt,
            Consumer<byte[]> onClientPacket,
            Consumer<String> onIpAssigned,
            TunAndroid tunAndroid,
            Consumer<State> onStateChange) throws IOException, InterruptedException {
        this.jwt = jwt;
        this.onClientPacketHandler = onClientPacket;
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.state = DISCONNECTED;
        this.onIpAssigned = onIpAssigned;
        this.objectMapper = new BsonMapper(100, 1000, 4 * 1024, 64, null);
        this.tunAndroid = tunAndroid;
        this.onStateChange = onStateChange;

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

        worker = new Thread(() -> {
            while (state != SHUTDOWN) {
                try {
                    run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    state = SHUTDOWN;
                    DebugLog.log("VPN client is shutdown");
                } catch (NoSuchAlgorithmException | KeyManagementException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        worker.start();
    }

    private void run() throws InterruptedException, NoSuchAlgorithmException, KeyManagementException {
        state = advanceStateIfTrueOrElse(hasError, WAITING, CONNECTING, state, disconnected);
        onStateChange.accept(state);
        SSLSocket ssl = null;

        if (state == WAITING) {
            if (timeout++ == TIMEOUT) {
                timeout = 0;
                hasError = false;
                state = DISCONNECTED;
            } else {
                synchronized (this) {
                    this.wait(1000);
                }
                DebugLog.log("Waiting " + timeout + "sec");
            }
        }

        if (state == CONNECTING) {
            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                DebugLog.log("Connecting to " + serverAddress + ":" + serverPort);
                SSLSocket socket;
                socket = (SSLSocket) factory.createSocket();
                setupSocket(socket);

                socket.startHandshake();

                serverOutputStream = new DataOutputStream(socket.getOutputStream());
                serverInputStream = new DataInputStream(socket.getInputStream());

                state = LOGIN;
                DebugLog.log("Connected");
                onStateChange.accept(CONNECTED);
                while (state != DISCONNECTED) {
                    switch (state) {
                        case LOGIN -> {
                            RequestDto<VpnLoginRequestDto> requestDto = RequestDto.wrap(Command.LOGIN, VpnLoginRequestDto.builder().jwt(jwt).build());
                            Packet<RequestDto<?>> packet = Packet.ofRequest(requestDto);
                            objectMapper.serialize(packet, serverOutputStream);
                            DebugLog.log("Login request sent");
                            state = AWAITING_LOGIN_RESPONSE;
                        }
                        case AWAITING_LOGIN_RESPONSE -> {
                            Packet<?> packet = objectMapper.deserialize(serverInputStream, Packet.class);
                            ResponseDto<VpnIpResponseDto> responseDto = (ResponseDto<VpnIpResponseDto>) packet.getPayload();

                            if (responseDto.getStatus() == Status.OK) {
                                DebugLog.log("Authorized");
                                state = GET_IP;
                            } else {
                                DebugLog.log(responseDto.getStatus().name());
                                state = SHUTDOWN;
                                hasError = true;
                            }
                            assignedIp = intToIpv4(responseDto.getData().getIpAddress());
                            assignedIpBytes = ipv4ToIntBytes(assignedIp);
                            state = LIVE;
                            DebugLog.log("Virtual IP: " + assignedIp);
                            onIpAssigned.accept(assignedIp);
                        }
                        case LIVE -> {
                            Packet<?> packet = objectMapper.deserialize(serverInputStream, Packet.class);
                            RequestDto<VpnForwardPacketRequestDto> requestDto = (RequestDto<VpnForwardPacketRequestDto>) packet.getPayload();

                            if (requestDto.getCommand() == FORWARD_PACKET && requestDto.getData() instanceof VpnForwardPacketRequestDto vpnForwardPacketRequestDto) {
                                if (vpnForwardPacketRequestDto.getPacket().length > MAX_MTU) {
                                    throw new IOException("Invalid packet length");
                                }
                                onClientPacketHandler.accept(vpnForwardPacketRequestDto.getPacket());
                            } else if (requestDto.getCommand() == DISCONNECT) {
                                disconnect();
                            }
                        }
                    }
                }
            } catch (RuntimeException | IOException e) {
                handleError(e);
            } finally {
                try {
                    if (serverOutputStream != null) {
                        serverOutputStream.close();
                    }
                    if (serverInputStream != null) {
                        serverInputStream.close();
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                DebugLog.log("Data stream closed");
            }
        }
    }

    private void setupSocket(SSLSocket socket) throws IOException {
        socket.setTcpNoDelay(false);
        socket.connect(new InetSocketAddress(serverAddress, serverPort));
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.setUseClientMode(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(1800 * 1000);
    }

    private void handleError(Throwable e) {
        e.printStackTrace();
        state = DISCONNECTED;
        hasError = true;
    }

    public void sendToClient(byte[] packet) {
        RequestDto<VpnForwardPacketRequestDto> requestDto = RequestDto.wrap(FORWARD_PACKET, VpnForwardPacketRequestDto.builder().packet(packet).build());
        Packet<RequestDto<?>> packet1 = Packet.ofRequest(requestDto);

        try {
            objectMapper.serialize(packet1, serverOutputStream);
        } catch (RuntimeException | IOException ex) {
            handleError(ex);
            throw new RuntimeException(ex);
        }
    }

    public void stop() {
        disconnect();
        state = SHUTDOWN;
        try {
            Thread.sleep(100);
            if (worker.isAlive()) {
                worker.interrupt();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void disconnect() {
        if (state != DISCONNECTED) {
            state = DISCONNECTED;
            onStateChange.accept(DISCONNECTED);
            hasError = false;
            DebugLog.log("Disconnected from server");
        }

        try {
            serverOutputStream.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            serverOutputStream = null;
            serverInputStream = null;
        }
    }
}