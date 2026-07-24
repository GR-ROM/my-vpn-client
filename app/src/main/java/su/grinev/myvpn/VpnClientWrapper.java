package su.grinev.myvpn;

import static su.grinev.myvpn.NetUtils.intToIpv4;
import static su.grinev.myvpn.VpnClient.BUFFER_SIZE;

import android.net.ConnectivityManager;
import android.net.VpnService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    // CopyOnWriteArrayList: each VpnClient starts connecting from its own constructor, so a fast
    // session can reach LIVE and iterate this list (onIpAssigned → reprotectSocket) while the
    // wrapper constructor is still adding the remaining sessions — an ArrayList would CME there.
    private final List<VpnClient> vpnClients = new CopyOnWriteArrayList<>();
    private final boolean defaultRouteViaVpn;
    private final Set<String> excludedApps;
    private final TrafficStatsManager trafficStats = TrafficStatsManager.getInstance();
    private final Consumer<State> aggregateStateConsumer;
    private final DefaultNetworkMonitor networkMonitor;

    private final Object stateLock = new Object();
    private final State[] sessionStates = new State[SESSION_COUNT];

    // Set (before anything else) by stop(): once the service tears this wrapper down, its session
    // callbacks must no longer reach the service. Without this, stop()'s own setState(SHUTDOWN) on
    // every session aggregates to SHUTDOWN and re-enters MyVpnService.onVpnStateChanged — which on
    // the reconnect() path queued a stopVpnSync that killed the FRESH connection and the service
    // right after it started (the "have to toggle twice" symptom).
    private volatile boolean stopped = false;

    // Establish the TUN once (on the first session to go LIVE). All sessions share the same virtual IP,
    // so re-establishing the interface per session / per reconnect would needlessly micro-drop traffic.
    private final Object tunLock = new Object();
    private boolean tunConfigured = false;
    private String configuredTunIp;                 // IP the TUN interface is currently established with
    private boolean ipReconnectRequested = false;   // guard: request the reconnect only once per IP change
    // Asks the service for a full reconnect when the server hands us a DIFFERENT virtual IP than the TUN
    // is on (rare: hash collision / pool change). A stale TUN IP makes the OS drop the downlink (dest =
    // new IP != TUN IP) — "connected but no internet". We can't hot-swap the interface (the reader
    // thread is single-use), so a fresh wrapper must re-establish it. Set once in the constructor.
    private Runnable onReconnectRequested;

    // Reusable liveness snapshot for flow selection (onTunPacketReceived is single-producer: the
    // TunHandler reader thread), so there's no per-packet allocation.
    private final boolean[] liveScratch = new boolean[SESSION_COUNT];

    // --- Diagnostics: periodic heartbeat + data-plane counters, added to diagnose "shows LIVE but
    // no traffic" reports. TUN-reader-side counters are single-writer volatiles; TUN-write-side ones
    // are AtomicLongs (both session worker threads write there).
    private static final long HEARTBEAT_INTERVAL_S = 30;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiagHeartbeat");
        t.setDaemon(true);
        return t;
    });
    private volatile long tunRxPackets;        // TUN reader thread: packets handed to a session
    private volatile long tunRxBytes;
    private volatile long tunDropsNoSession;   // TUN reader thread: dropped, no LIVE session
    private volatile long lastTunReadMs = System.currentTimeMillis();   // when the TUN reader last delivered a packet
    private final AtomicLong tunTxPackets = new AtomicLong();
    private final AtomicLong tunTxBytes = new AtomicLong();
    private final AtomicLong tunWriteErrors = new AtomicLong();
    private volatile State lastAggregate = State.DISCONNECTED;

    public VpnClientWrapper(
            TunAndroid tun,
            String serverAddress,
            int serverPort,
            String jwt,
            boolean defaultRouteViaVpn,
            Set<String> excludedApps,
            PoolFactory poolFactory,
            Consumer<State> onStateChange,
            Runnable onReconnectRequested
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
        this.onReconnectRequested = onReconnectRequested;
        Arrays.fill(sessionStates, State.DISCONNECTED);

        VpnService vpnService = tun.getVpnService();
        ConnectivityManager cm = vpnService.getSystemService(ConnectivityManager.class);
        this.networkMonitor = new DefaultNetworkMonitor(cm, this);

        // One CLIENT-WIDE request-seq counter shared by every session: the request seq (spec §5.2) is a
        // general per-request number, and the server's download gate is a single client-wide state
        // ordered by it — so any seq'd request (FLOW_CONTROL, upload) must draw from one shared counter.
        AtomicInteger requestSeq = new AtomicInteger(0);
        for (int i = 0; i < SESSION_COUNT; i++) {
            final int idx = i;
            vpnClients.add(new VpnClient(serverAddress, serverPort, jwt,
                    this::onClientPacketReceived, this::onIpAssigned, poolFactory,
                    state -> onSessionStateChanged(idx, state),
                    vpnService::protect, bufferPool::release,
                    networkMonitor::isAvailable, "s" + idx,
                    idx == 0,   // ep0: s0 is the control connection (advertises CONTROL_CONN)
                    requestSeq));
        }
        networkMonitor.start();
        heartbeat.scheduleWithFixedDelay(this::logHeartbeat,
                HEARTBEAT_INTERVAL_S, HEARTBEAT_INTERVAL_S, TimeUnit.SECONDS);
    }

    /**
     * Periodic one-line health snapshot. Reading a stretch of these around a "connected but no
     * internet" episode shows which invariant broke: net=DOWN (no underlying network), tunReader
     * dead (upload path gone), a session stuck LIVE with ka=off / stale lastRx (dead link
     * undetected), or flow=STOP lingering after screen-on (downlink left paused). A gap in
     * heartbeats means the process itself was frozen (Doze) or killed.
     */
    private void logHeartbeat() {
        try {
            StringBuilder sb = new StringBuilder(320);
            sb.append("HB net=").append(networkMonitor.isAvailable() ? "up" : "DOWN");
            sb.append(" agg=").append(lastAggregate);
            sb.append(" tunReader=").append(!super.running ? "off" : (readerThread.isAlive() ? "alive" : "DEAD"));
            // tunIdle = seconds since the reader last delivered a packet. If this grows while agg=LIVE and
            // the screen is on, the uplink is starved (OS not routing app traffic into our TUN / reader wedged).
            sb.append(" tunIdle=").append((System.currentTimeMillis() - lastTunReadMs) / 1000).append("s");
            sb.append(" tunRx=").append(tunRxPackets).append("p/").append(tunRxBytes >> 10).append("K");
            sb.append(" noSessDrop=").append(tunDropsNoSession);
            sb.append(" tunTx=").append(tunTxPackets.get()).append("p/").append(tunTxBytes.get() >> 10).append("K");
            sb.append(" tunWErr=").append(tunWriteErrors.get());
            for (VpnClient c : vpnClients) {
                sb.append(" | ").append(c.diagSummary());
            }
            DebugLog.log(sb.toString());
        } catch (Throwable t) {
            DebugLog.log("HB failed: " + t);   // never let the heartbeat task die silently
        }
    }

    private void onIpAssigned(VpnIpResponseDto vpnIpResponseDto) {
        String assignedIp = intToIpv4(vpnIpResponseDto.getIpAddress());
        boolean configure;
        boolean reconnect = false;
        synchronized (tunLock) {
            if (!tunConfigured) {
                configure = true;
                configuredTunIp = assignedIp;
                tunConfigured = true;
            } else {
                configure = false;
                // The server can hand a different virtual IP than the TUN is on (rare now that the
                // server assigns by clientId hash — only a collision / pool change). The TUN can't be
                // re-addressed in place (single-use reader thread), so ask for a clean reconnect: the
                // fresh wrapper re-establishes the interface with the new IP. Otherwise the OS keeps
                // dropping the downlink and the session sits "connected but no internet".
                if (!assignedIp.equals(configuredTunIp) && !ipReconnectRequested) {
                    ipReconnectRequested = true;
                    reconnect = true;
                }
            }
        }
        if (reconnect) {
            DebugLog.log("onIpAssigned: assigned IP " + assignedIp + " != TUN IP " + configuredTunIp
                    + " — reconnecting to re-establish the TUN");
            if (onReconnectRequested != null) {
                onReconnectRequested.run();
            }
            return;
        }
        try {
            if (configure) {
                int prefixLength = effectiveTunPrefix(vpnIpResponseDto.getPrefixLength());
                String gateway = intToIpv4(vpnIpResponseDto.getGatewayIpAddress());
                // The gateway is the node's built-in DNS forwarder, so the TUN's DNS is the gateway.
                // (VpnIpResponseDto tag 2 is maxConnections, not a DNS address — the server sends no
                // separate DNS server; configureTun points DNS at the gateway regardless.)
                String dns = gateway;
                DebugLog.log("TUN configure: " + assignedIp + "/" + prefixLength + " gateway=" + gateway
                        + " dns=" + dns + " (server prefix=" + vpnIpResponseDto.getPrefixLength() + ")");
                tun.configureTun(assignedIp, prefixLength, gateway, dns, defaultRouteViaVpn, excludedApps);
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
        if (stopped) {
            return;   // in-flight monitor callback during teardown — don't wake stopping sessions
        }
        int woken = 0;
        for (VpnClient c : vpnClients) {
            if (c.getState() != State.LIVE) {
                c.forceReconnect();
                woken++;
            }
        }
        DebugLog.log("Network changed: forced reconnect on " + woken + "/" + vpnClients.size()
                + " non-LIVE sessions (net=" + (networkMonitor.isAvailable() ? "up" : "DOWN") + ")");
    }

    // ==================== State aggregation ====================

    /** Forward a single aggregate state so one session flapping doesn't drag the UI off LIVE. */
    private void onSessionStateChanged(int index, State state) {
        if (stopped) {
            return;   // teardown-induced transitions (SHUTDOWN etc.) must not reach the service
        }
        State aggregate;
        boolean aggregateChanged;
        synchronized (stateLock) {
            sessionStates[index] = state;
            aggregate = aggregateStateLocked();
            aggregateChanged = aggregate != lastAggregate;
            lastAggregate = aggregate;
        }
        if (aggregateChanged) {
            DebugLog.log("Aggregate state -> " + aggregate);
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
        stopped = true;   // silence session callbacks BEFORE tearing anything down
        DebugLog.log("VpnClientWrapper.stop()");
        heartbeat.shutdownNow();
        super.stop();
        networkMonitor.stop();
        vpnClients.forEach(VpnClient::stop);
        tun.close();
    }

    public boolean isConnectionAlive() {
        return vpnClients.stream().anyMatch(c -> c.getState() == State.LIVE && c.isSocketConnected());
    }

    /**
     * True if any session still has an unacked FLOW_CONTROL request. On wake the resume sends
     * FLOW_CONTROL START (on the ep0 control connection); if the ack hasn't arrived by the wake
     * verify, the peer died while we slept (server restart) — {@link #isConnectionAlive()} can't see
     * that because {@code Socket.isConnected()} stays true — so we reconnect.
     */
    public boolean anyFlowAckPending() {
        return vpnClients.stream().anyMatch(VpnClient::isFlowAckPending);
    }

    /**
     * Wake verify decision: reconnect iff the connection we resumed is still the current one AND its
     * FLOW_CONTROL resume was never acked (link dead). Pure, so it is unit-tested on the JVM.
     */
    static boolean shouldReconnectOnWake(boolean wrapperStillCurrent, boolean flowAckStillPending) {
        return wrapperStillCurrent && flowAckStillPending;
    }

    /**
     * TUN mask to use for the assigned IP. The server sends the pool prefix (e.g. 22 for a /22 pool)
     * so the gateway (.0.1) stays on-link for any pool IP; a legacy server sends 0 → fall back to a
     * wide /16 (keeps the gateway on-link across the whole 10.x.0.0/16 range). Pure, unit-tested.
     */
    static int effectiveTunPrefix(int serverPrefixLength) {
        return serverPrefixLength > 0 ? serverPrefixLength : 16;
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
        DebugLog.log("suspendDownlink: FLOW_CONTROL STOP to all sessions");
        vpnClients.forEach(c -> c.sendFlowControl(FlowAction.STOP));
    }

    /** Resume the downlink (screen-on): FLOW_CONTROL START on every connection. */
    public void resumeDownlink() {
        DebugLog.log("resumeDownlink: FLOW_CONTROL START to all sessions");
        vpnClients.forEach(c -> c.sendFlowControl(FlowAction.START));
    }

    // ==================== Data plane ====================

    @Override
    public boolean onTunPacketReceived(ByteBuffer packet) {
        lastTunReadMs = System.currentTimeMillis();   // a packet came off the TUN — the uplink reader is delivering
        int idx = selectLiveSession(NetUtils.fiveTupleHash(packet));
        if (idx < 0) {
            tunDropsNoSession++;
            return false;   // no live session — not handed off, TunHandler releases the buffer
        }
        tunRxPackets++;
        tunRxBytes += packet.remaining();
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
        int len = packet.remaining();
        try {
            trafficStats.addIncomingBytes(len);
            tun.writePacket(packet);
            tunTxPackets.incrementAndGet();
            tunTxBytes.addAndGet(len);
        } catch (IOException e) {
            // A failing TUN write means the downlink dies at the very last hop while sessions still
            // look healthy — log it distinctly (rate-limited) instead of masquerading as a generic
            // connection error in the session's read loop.
            long n = tunWriteErrors.incrementAndGet();
            if (n == 1 || n % 500 == 0) {
                DebugLog.log("TUN write failed (#" + n + "): " + e);
            }
            throw new RuntimeException(e);
        }
    }
}
