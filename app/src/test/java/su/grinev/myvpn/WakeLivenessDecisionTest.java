package su.grinev.myvpn;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link VpnClientWrapper#shouldReconnectOnWake} — the wake verify decision.
 *
 * <p>On screen-on we optimistically resume a cached-LIVE connection and send FLOW_CONTROL START;
 * {@code Socket.isConnected()} can't detect a peer that died while we slept (server restart is a 100%
 * repro), so after a grace window we reconnect iff (a) the connection we resumed is still the current
 * one and (b) its FLOW_CONTROL START was never acked (link dead). The FLOW-ack timeout primitive
 * itself is covered by {@link FlowAckTrackerTest}; this locks in the two-condition guard.
 */
public class WakeLivenessDecisionTest {

    @Test
    public void reconnects_whenCurrentAndResumeUnacked() {
        // the bug case: still our connection, server never acked the resume → dead → reconnect
        assertTrue(VpnClientWrapper.shouldReconnectOnWake(true, true));
    }

    @Test
    public void healthy_whenResumeAcked() {
        // server acked the FLOW_CONTROL START → link is alive → keep the fast-resume, no reconnect
        assertFalse(VpnClientWrapper.shouldReconnectOnWake(true, false));
    }

    @Test
    public void noop_whenWrapperAlreadyReplaced() {
        // keepalive / in-loop FLOW-ack watchdog already reconnected: not our wrapper anymore → don't
        // fire a second reconnect even though a (new) FLOW ack may still be pending
        assertFalse(VpnClientWrapper.shouldReconnectOnWake(false, true));
    }

    @Test
    public void noop_whenReplacedAndAcked() {
        assertFalse(VpnClientWrapper.shouldReconnectOnWake(false, false));
    }
}
