package su.grinev.myvpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SleepPolicy} — what a screen-off does to a live tunnel, and when the CPU has to
 * be held to keep it. The regression this guards: with the tunnel parked on screen-off (keepalive off,
 * downlink FLOW_CONTROL STOP'd) nothing answers the node's PING, it evicts the connection after 30s and
 * every session inside the tunnel dies with the sleep.
 */
public class SleepPolicyTest {

    // ---- shouldParkTunnel ----

    @Test
    public void park_onlyInBatterySavingMode() {
        assertTrue("battery-saving mode with a live tunnel parks it",
                SleepPolicy.shouldParkTunnel(false, true));
    }

    @Test
    public void keepTunnel_neverParks() {
        assertFalse("the whole point of the setting: sleep must not touch the tunnel",
                SleepPolicy.shouldParkTunnel(true, true));
    }

    @Test
    public void park_nothingToParkWhenNoLiveConnection() {
        assertFalse(SleepPolicy.shouldParkTunnel(false, false));
        assertFalse(SleepPolicy.shouldParkTunnel(true, false));
    }

    // ---- shouldHoldWakeLock ----

    @Test
    public void wakeLock_heldOnlyWhileKeepingALiveTunnelThroughAScreenOff() {
        assertTrue(SleepPolicy.shouldHoldWakeLock(true, false, true));
    }

    @Test
    public void wakeLock_notHeldWhileTheScreenIsOn() {
        assertFalse("an interactive screen already keeps the CPU up",
                SleepPolicy.shouldHoldWakeLock(true, true, true));
    }

    @Test
    public void wakeLock_notHeldInBatterySavingMode() {
        assertFalse("parked tunnel needs no CPU — that is what the mode buys",
                SleepPolicy.shouldHoldWakeLock(false, false, true));
    }

    @Test
    public void wakeLock_notHeldWithoutALiveConnection() {
        assertFalse("nothing to keep alive — never drain the battery for a dead tunnel",
                SleepPolicy.shouldHoldWakeLock(true, false, false));
        assertFalse(SleepPolicy.shouldHoldWakeLock(false, true, false));
    }
}
