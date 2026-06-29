package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Pins the progressive reconnect backoff ({@link ReconnectBackoff#next}): start 1s, double up to a
 * 60s cap. This is the "server unreachable, link up" retry schedule; a missing network link parks
 * instead and is not covered here.
 */
public class ReconnectBackoffTest {

    private static final int MIN = 1;
    private static final int MAX = 60;

    @Test
    public void doublesFromOneUpToTheCap() {
        int s = MIN;
        s = ReconnectBackoff.next(s, MAX); assertEquals(2, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(4, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(8, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(16, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(32, s);
        s = ReconnectBackoff.next(s, MAX); assertEquals(60, s);   // min(64, 60)
    }

    @Test
    public void staysAtCapOnceReached() {
        assertEquals(60, ReconnectBackoff.next(60, MAX));
        assertEquals(60, ReconnectBackoff.next(40, MAX));   // min(80, 60)
        assertEquals(60, ReconnectBackoff.next(1000, MAX));
    }

    @Test
    public void neverExceedsCap() {
        int s = MIN;
        for (int i = 0; i < 50; i++) {
            s = ReconnectBackoff.next(s, MAX);
            org.junit.Assert.assertTrue("s=" + s, s <= MAX);
        }
        assertEquals(60, s);
    }

    @Test
    public void resetFloorThenFirstStep() {
        // After a successful connect / network event the caller resets to MIN; the next step is 2.
        int s = MIN;                       // reset
        assertEquals(2, ReconnectBackoff.next(s, MAX));
    }
}
