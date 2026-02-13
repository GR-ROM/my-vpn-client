package su.grinev.myvpn;

import static su.grinev.myvpn.VpnClient.BUFFER_SIZE;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

import su.grinev.myvpn.traffic.TrafficStatsManager;
import su.grinev.pool.PoolFactory;

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
            PoolFactory poolFactory,
            Consumer<State> onStateChange
    ) throws IOException, InterruptedException {
        super(tun, new BufferPool(1000, BUFFER_SIZE));
        this.tun = tun;
        this.defaultRouteViaVpn = defaultRouteViaVpn;
        this.vpnClient = new VpnClient(serverAddress, serverPort, jwt, this::onClientPacketReceived, this::onIpAssigned, poolFactory, onStateChange);
    }

    private void onIpAssigned(String ip, String gatewayIp) {
        try {
            tun.configureTun(ip, gatewayIp, defaultRouteViaVpn);
            if (!super.running) {
                super.start();
            }
        } catch (IOException | InterruptedException e) {
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
