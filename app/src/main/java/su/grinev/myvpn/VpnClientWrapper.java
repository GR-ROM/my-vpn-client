package su.grinev.myvpn;

import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.VpnClient.BUFFER_SIZE;

import android.net.ConnectivityManager;
import android.net.VpnService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import su.grinev.model.FlowAction;
import su.grinev.model.VpnIpResponseDto;
import su.grinev.myvpn.traffic.TrafficStatsManager;
import su.grinev.pool.FastPool;
import su.grinev.pool.PoolFactory;

/**
 * Multisession front-end: opens {@link #SESSION_COUNT} parallel TLS connections to the server over the
 * system default network and spreads upload flows across them by 5-tuple hash (flow affinity). Several
 * connections reduce head-of-line blocking — a stall/retransmit on one TCP/TLS connection only delays
 * its own flows, not all traffic.
 *
 * <p>It does NOT bind to specific transports (Wi-Fi/cellular): the OS picks the best network. A
 * {@link DefaultNetworkMonitor} drives fast recovery — when the default network appears or switches,
 * all sessions reconnect immediately on the new network instead of waiting for a socket timeout.
 */
public class VpnClientWrapper extends TunHandler implements DefaultNetworkMonitor.Listener {

    /** Number of parallel connections in the multisession (HoL reduction). */
    private static final int SESSION_COUNT = 2;

    private final Tun tun;
    private final List<VpnClient> vpnClients = new ArrayList<>();
    private final boolean defaultRouteViaVpn;
    private final Set<String> excludedApps;
    private final TrafficStatsManager trafficStats = TrafficStatsManager.getInstance();
    private final Consumer<State> aggregateStateConsumer;
    private final DefaultNetworkMonitor networkMonitor;

    private final Object stateLock = new Object();
    private final State[] sessionStates = new State[SESSION_COUNT];

    // Establish the TUN once (on the first session to go LIVE). All sessions share the same virtual IP,
    // so re-establishing the interface per session / per reconnect would needlessly micro-drop traffic.
    private final Object tunLock = new Object();
    private boolean tunConfigured = false;

    // Reusable liveness snapshot for flow selection (onTunPacketReceived is single-producer: the
    // TunHandler reader thread), so there's no per-packet allocation.
    private final boolean[] liveScratch = new boolean[SESSION_COUNT];

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
        // Non-blocking on purpose (factory default is blocking): the pool is hit once per
        // upload packet from the TUN reader and released from the session writers — the
        // blocking pool's semaphore + timeout meant a lock CAS per packet, a possible
        // 100ms reader stall near exhaustion, and an IllegalStateException that could kill
        // the reader (2 sessions x 512 queue slots can exceed the 1000 permits). Non-blocking
        // get() always succeeds; in-flight buffers stay bounded by the tail-dropping send queues.
        super(tun, new FastPool<>("tunBufferPool", () -> ByteBuffer.allocateDirect(BUFFER_SIZE), b -> {}, 100, 1000, false, 0));
        this.tun = tun;
        this.defaultRouteViaVpn = defaultRouteViaVpn;
        this.excludedApps = excludedApps;
        this.aggregateStateConsumer = onStateChange;
        Arrays.fill(sessionStates, State.DISCONNECTED);

        VpnService vpnService = tun.getVpnService();
        ConnectivityManager cm = vpnService.getSystemService(ConnectivityManager.class);
        this.networkMonitor = new DefaultNetworkMonitor(cm, this);

        for (int i = 0; i < SESSION_COUNT; i++) {
            final int idx = i;
            vpnClients.add(new VpnClient(serverAddress, serverPort, jwt,
                    this::onClientPacketReceived, this::onIpAssigned, poolFactory,
                    state -> onSessionStateChanged(idx, state),
                    vpnService::protect, bufferPool::release,
                    networkMonitor::isAvailable, "s" + idx));
        }
        networkMonitor.start();
    }

    private void onIpAssigned(VpnIpResponseDto vpnIpResponseDto) {
        boolean configure;
        synchronized (tunLock) {
            configure = !tunConfigured;
            tunConfigured = true;
        }
        try {
            if (configure) {
                tun.configureTun(
                        intToIpv4(vpnIpResponseDto.getIpAddress()),
                        intToIpv4(vpnIpResponseDto.getGatewayIpAddress()),
                        intToIpv4(vpnIpResponseDto.getDnsServer()),
                        defaultRouteViaVpn,
                        excludedApps
                );
            }
            // Re-protect sockets after the tunnel exists (idempotent; covers the just-LIVE session). On
            // Android 10, protect() before the tunnel may not persist.
            vpnClients.forEach(VpnClient::reprotectSocket);
            if (!super.running) {
                super.start();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * An underlying network appeared, or the last one vanished. Wake only the sessions that are NOT
     * currently LIVE (parked / backing off) so they retry instantly on the new path — a LIVE session is
     * left untouched (if its network actually died it notices via a read failure and reconnects with a
     * 1s backoff). This keeps healthy connections stable across unrelated network flaps.
     */
    @Override
    public void onNetworkChanged() {
        for (VpnClient c : vpnClients) {
            if (c.getState() != State.LIVE) {
                c.forceReconnect();
            }
        }
    }

    // ==================== State aggregation ====================

    /** Forward a single aggregate state so one session flapping doesn't drag the UI off LIVE. */
    private void onSessionStateChanged(int index, State state) {
        State aggregate;
        synchronized (stateLock) {
            sessionStates[index] = state;
            aggregate = aggregateStateLocked();
        }
        aggregateStateConsumer.accept(aggregate);
    }

    /** Best state across sessions: any LIVE wins; only when all are terminal do we report bad. */
    private State aggregateStateLocked() {
        boolean anyConnected = false, anyConnecting = false, anyWaiting = false;
        boolean anyShutdown = false, anyError = false, anyTerminalOnly = true;
        for (State s : sessionStates) {
            switch (s) {
                case LIVE -> { return State.LIVE; }
                case CONNECTED -> { anyConnected = true; anyTerminalOnly = false; }
                case CONNECTING, HELLO, AWAITING_HELLO_RESPONSE, LOGIN, AWAITING_LOGIN_RESPONSE -> {
                    anyConnecting = true; anyTerminalOnly = false;
                }
                case WAITING, DISCONNECTED, SLEEPING -> { anyWaiting = true; anyTerminalOnly = false; }
                case SHUTDOWN -> anyShutdown = true;
                case ERROR -> anyError = true;
            }
        }
        if (anyConnected) return State.CONNECTED;
        if (anyConnecting) return State.CONNECTING;
        if (anyWaiting) return State.WAITING;
        if (anyTerminalOnly && anyShutdown) return State.SHUTDOWN;
        if (anyTerminalOnly && anyError) return State.ERROR;
        return State.WAITING;
    }

    // ==================== Lifecycle / public API ====================

    public void stop() {
        super.stop();
        networkMonitor.stop();
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

    /** Suspend the server→client downlink (screen-off): FLOW_CONTROL STOP on every connection. MUST be
     *  called off the main thread (the sends take each session's output lock). */
    public void suspendDownlink() {
        vpnClients.forEach(c -> c.sendFlowControl(FlowAction.STOP));
    }

    /** Resume the downlink (screen-on): FLOW_CONTROL START on every connection. */
    public void resumeDownlink() {
        vpnClients.forEach(c -> c.sendFlowControl(FlowAction.START));
    }

    // ==================== Data plane ====================

    @Override
    public boolean onTunPacketReceived(ByteBuffer packet) {
        int idx = selectLiveSession(NetUtils.fiveTupleHash(packet));
        if (idx < 0) {
            return false;   // no live session — not handed off, TunHandler releases the buffer
        }
        trafficStats.addOutgoingBytes(packet.remaining());
        vpnClients.get(idx).sendToServer(packet);   // takes ownership of packet
        return true;
    }

    private int selectLiveSession(int hash) {
        for (int i = 0; i < vpnClients.size(); i++) {
            liveScratch[i] = vpnClients.get(i).getState() == State.LIVE;
        }
        return selectLiveSession(hash, liveScratch);
    }

    /**
     * Select the session index for a flow: 5-tuple hash modulo the session count, honoring that target
     * when it's live, and only spilling to the first live session (ascending index) when the target is
     * dead. Keeps flow affinity stable and moves only a dead session's flows on failure. Returns -1 if
     * no session is live.
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
