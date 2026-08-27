package su.grinev.myvpn;

/**
 * What a screen-off does to a live tunnel, as a pure decision.
 *
 * <p>Historically screen-off always <em>parked</em> the tunnel: {@code FLOW_CONTROL STOP} on every
 * connection plus keepalive off. That saves battery but kills the sessions running through the tunnel —
 * with the keepalive stopped nothing answers the server's PING, the node evicts the connection after its
 * 30s request timeout, and every TCP flow inside the tunnel dies with it. The user-visible shape is
 * "the VPN drops everything as soon as the phone falls asleep".
 *
 * <p>The {@code keepTunnelWhileAsleep} setting picks between the two: keep the tunnel breathing through
 * the sleep (default), or the old battery-saving park. Pure of Android APIs, so it is unit-tested on the
 * JVM; the service supplies the live screen/connection state.
 */
public final class SleepPolicy {

    private SleepPolicy() {
    }

    /** Park the tunnel on screen-off (FLOW_CONTROL STOP + keepalive off) — only in battery-saving mode. */
    public static boolean shouldParkTunnel(boolean keepTunnelWhileAsleep, boolean connectionAlive) {
        return connectionAlive && !keepTunnelWhileAsleep;
    }

    /**
     * Hold a partial wake lock. Only while we are keeping the tunnel, the screen is off and there is a
     * live connection to keep — an idle CPU otherwise lets the keepalive tick slip past the node's
     * request timeout, which is the very eviction this mode exists to avoid.
     */
    public static boolean shouldHoldWakeLock(boolean keepTunnelWhileAsleep,
                                             boolean screenInteractive,
                                             boolean connectionAlive) {
        return keepTunnelWhileAsleep && !screenInteractive && connectionAlive;
    }
}
