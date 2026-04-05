package su.grinev.myvpn;

import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.VpnClient.BUFFER_SIZE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.function.Consumer;

import su.grinev.model.VpnIpResponseDto;
import su.grinev.myvpn.traffic.TrafficStatsManager;
import su.grinev.pool.PoolFactory;

public class VpnClientWrapper extends TunHandler {
    private final Tun tun;
    private final VpnClient vpnClient;
    private final boolean defaultRouteViaVpn;
    private final Set<String> excludedApps;
    private final TrafficStatsManager trafficStats = TrafficStatsManager.getInstance();

    public VpnClientWrapper(
            TunAndroid tun,
            String serverAddress,
            int serverPort,
            String jwt,
            boolean defaultRouteViaVpn,
            Set<String> excludedApps,
            PoolFactory poolFactory,
            Consumer<State> onStateChange
    ) throws IOException, InterruptedException {
        super(tun, poolFactory.getFastPool("tunBufferPool", () -> ByteBuffer.allocateDirect(BUFFER_SIZE)));
        this.tun = tun;
        this.defaultRouteViaVpn = defaultRouteViaVpn;
        this.excludedApps = excludedApps;
        android.net.VpnService vpnService = tun.getVpnService();
        this.vpnClient = new VpnClient(serverAddress, serverPort, jwt, this::onClientPacketReceived, this::onIpAssigned, poolFactory, onStateChange, s -> {
            boolean ok = vpnService.protect(s);
            DebugLog.log("protect(socket) returned " + ok);
        });
    }

    private void onIpAssigned(VpnIpResponseDto vpnIpResponseDto) {
        try {
            tun.configureTun(
                    intToIpv4(vpnIpResponseDto.getIpAddress()),
                    intToIpv4(vpnIpResponseDto.getGatewayIpAddress()),
                    intToIpv4(vpnIpResponseDto.getDnsServer()),
                    defaultRouteViaVpn,
                    excludedApps
            );
            // Re-protect socket after tunnel is established.
            // On Android 10, protect() before tunnel may not persist.
            vpnClient.reprotectSocket();
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

    public void pauseKeepAlive() {
        vpnClient.pauseKeepAlive();
    }

    public void resumeKeepAlive() {
        vpnClient.resumeKeepAlive();
    }

    @Override
    public void onTunPacketReceived(ByteBuffer packet) {
        if (vpnClient.getState() == State.LIVE) {
            trafficStats.addOutgoingBytes(packet.remaining());
            vpnClient.sendToServer(packet);
        }
    }

    public void onClientPacketReceived(ByteBuffer packet) {
        try {
            trafficStats.addIncomingBytes(packet.remaining());
            tun.writePacket(packet);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
