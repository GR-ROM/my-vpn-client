package su.grinev.myvpn;

/**
 * Pure (no-Android) reconnect backoff math, unit-tested on the JVM. Used by {@link VpnClient} for the
 * "link is up but the server is unreachable" case: the delay starts at the floor and doubles up to a
 * cap. A missing network link does NOT back off — it parks and wakes on a network event — so this is
 * only the progressive-retry schedule.
 */
final class ReconnectBackoff {
    private ReconnectBackoff() {}

    /** Next delay (seconds) after {@code currentSec}: doubled, capped at {@code maxSec}. */
    static int next(int currentSec, int maxSec) {
        return Math.min(currentSec * 2, maxSec);
    }
}
