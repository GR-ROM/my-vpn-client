package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the progressive reconnect backoff ({@link ReconnectBackoff#next}): start 200ms, double up to a
 * 60s cap. The short floor lets the first retry right after a network event (route not fully up →
 * transient ENETUNREACH) recover near-instantly. This is the "server unreachable, link up" schedule;
 * a missing network link parks instead and is not covered here.
 */
public class ReconnectBackoffTest {

    private static final int MIN = 200;
    private static final int MAX = 60_000;

    @Test
    public void doublesFromMinUpToTheCap() {
        int s = MIN;
        s = ReconnectBackoff.next(s, MAX); assertEquals(400, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(800, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(1_600, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(3_200, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(6_400, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(12_800, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(25_600, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(51_200, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(60_000, s);   // min(102400, 60000)
    }

    @Test
    public void staysAtCapOnceReached() {
        assertEquals(60_000, ReconnectBackoff.next(60_000, MAX));
        assertEquals(60_000, ReconnectBackoff.next(40_000, MAX));   // min(80000, 60000)
        assertEquals(60_000, ReconnectBackoff.next(1_000_000, MAX));
    }

    @Test
    public void neverExceedsCap() {
        int s = MIN;
        for (int i = 0; i < 50; i++) {
            s = ReconnectBackoff.next(s, MAX);
            assertTrue("s=" + s, s <= MAX);
        }
        assertEquals(60_000, s);
    }

    @Test
    public void resetFloorThenFirstStep() {
        // After a successful connect / network event the caller resets to MIN; the next step is 400ms.
        int s = MIN;                       // reset
        assertEquals(400, ReconnectBackoff.next(s, MAX));
    }
}
