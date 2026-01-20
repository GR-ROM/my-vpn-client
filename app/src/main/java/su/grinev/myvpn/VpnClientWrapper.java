package su.grinev.myvpn;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

import su.grinev.myvpn.traffic.TrafficStatsManager;

public class VpnClientWrapper extends TunHandler {
    private final Tun tun;
    private final VpnClient vpnClient;
    private final boolean defaultRouteViaVpn;
    private final TrafficStatsManager trafficStats = TrafficStatsManager.getInstance();

    public VpnClientWrapper(
            TunAndroid tun,
            String serverAddress,
            int serverPort,
            String jwt,
            boolean defaultRouteViaVpn,
            Consumer<State> onStateChange
    ) throws IOException, InterruptedException {
        super(tun, new BufferPool(1000, 4 * 1024));
        this.tun = tun;
        this.defaultRouteViaVpn = defaultRouteViaVpn;
        this.vpnClient = new VpnClient(serverAddress, serverPort, jwt, this::onClientPacketReceived, this::onIpAssigned, onStateChange);
    }

    private void onIpAssigned(String ip) {
        try {
            tun.configureTun(ip, defaultRouteViaVpn);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        super.stop();

        vpnClient.stop();
        tun.close();
    }

    public boolean isConnectionAlive() {
        return vpnClient.getState() == State.LIVE && vpnClient.isSocketConnected();
    }

    @Override
    public void onTunPacketReceived(byte[] packet, int bytesRead) {
        if (vpnClient.getState() == State.LIVE) {
            trafficStats.addOutgoingBytes(bytesRead);
            vpnClient.sendToServer(Arrays.copyOf(packet, bytesRead));
        }
    }

    public void onClientPacketReceived(byte[] packet) {
        try {
            trafficStats.addIncomingBytes(packet.length);
            tun.writePacket(packet, packet.length);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
