package su.grinev.myvpn;

import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.VpnClient.BUFFER_SIZE;

import android.net.VpnService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import su.grinev.model.VpnIpResponseDto;
import su.grinev.myvpn.traffic.TrafficStatsManager;
import su.grinev.pool.PoolFactory;

public class VpnClientWrapper extends TunHandler {
    private final Tun tun;
    private final List<VpnClient> vpnClients = new ArrayList<>();
    private final boolean defaultRouteViaVpn;
    private final Set<String> excludedApps;
    private final TrafficStatsManager trafficStats = TrafficStatsManager.getInstance();
    // Reusable liveness snapshot for session selection (onTunPacketReceived is single-producer: the
    // TunHandler reader thread), so no per-packet allocation.
    private final boolean[] liveScratch;

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
        VpnService vpnService = tun.getVpnService();
        this.vpnClients.add(new VpnClient(serverAddress, serverPort, jwt, this::onClientPacketReceived, this::onIpAssigned, poolFactory, onStateChange, vpnService::protect, bufferPool::release));
        this.vpnClients.add(new VpnClient(serverAddress, serverPort, jwt, this::onClientPacketReceived, this::onIpAssigned, poolFactory, onStateChange, vpnService::protect, bufferPool::release));
        this.liveScratch = new boolean[vpnClients.size()];
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
            vpnClients.forEach(VpnClient::reprotectSocket);
            if (!super.running) {
                super.start();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void stop() {
        super.stop();
        vpnClients.forEach(VpnClient::stop);
        tun.close();
    }

    public boolean isConnectionAlive() {
        return vpnClients.stream().anyMatch(c -> c.getState() == State.LIVE && c.isSocketConnected());
    }

    public void pauseKeepAlive() {
        vpnClients.forEach(VpnClient::pauseKeepAlive);
    }

    public void resumeKeepAlive() {
        vpnClients.forEach(VpnClient::resumeKeepAlive);
    }

    @Override
    public boolean onTunPacketReceived(ByteBuffer packet) {
        VpnClient vpnClient = selectLiveSession(NetUtils.fiveTupleHash(packet));
        if (vpnClient == null) {
            return false;   // no live session — not handed off, TunHandler releases the buffer
        }

        trafficStats.addOutgoingBytes(packet.remaining());
        vpnClient.sendToServer(packet);   // takes ownership of packet
        return true;
    }

    private VpnClient selectLiveSession(int hash) {
        for (int i = 0; i < vpnClients.size(); i++) {
            liveScratch[i] = vpnClients.get(i).getState() == State.LIVE;
        }
        int idx = selectLiveSession(hash, liveScratch);
        return idx < 0 ? null : vpnClients.get(idx);
    }

    /**
     * Select the session index for a flow: hash modulo the total session count, and if that target
     * session is live, pin the flow to it. Only if the target is dead does the flow spill to the
     * first live session (ascending index order) — so a session going down moves only its own flows,
     * not everyone's. Returns -1 if no session is live.
     */
    static int selectLiveSession(int hash, boolean[] live) {
        int target = Math.floorMod(hash, live.length);
        if (live[target]) {
            return target;
        }
        for (int i = 0; i < live.length; i++) {
            if (live[i]) {
                return i;
            }
        }
        return -1;
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
