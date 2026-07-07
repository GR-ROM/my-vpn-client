package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import su.grinev.model.FlowAction;

/**
 * Covers the FLOW_CONTROL ack/timeout state machine ({@link FlowAckTracker}) that implements the
 * client algorithm: send FLOW_CONTROL, wait for the ack (ResponseDto.requestId == seq) up to a
 * timeout, and if it doesn't arrive treat the link as wedged (caller reconnects). Also guards the
 * "desired downlink state" the caller re-asserts on every fresh LIVE session.
 */
public class FlowAckTrackerTest {

    private static final long TIMEOUT = 5_000;

    @Test
    public void freshTrackerWantsStartNothingPendingNotOverdue() {
        FlowAckTracker t = new FlowAckTracker();
        assertEquals(FlowAction.START, t.desired());
        assertFalse(t.hasPending());
        assertFalse(t.isAckOverdue(1_000_000, TIMEOUT));
    }

    @Test
    public void setDesiredIsRemembered() {
        FlowAckTracker t = new FlowAckTracker();
        t.setDesired(FlowAction.STOP);
        assertEquals(FlowAction.STOP, t.desired());
        t.setDesired(FlowAction.START);
        assertEquals(FlowAction.START, t.desired());
    }

    @Test
    public void onSentMarksPending() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(7, 1_000);
        assertTrue(t.hasPending());
        assertEquals(7, t.pendingSeq());
    }

    @Test
    public void matchingAckClearsPendingAndReturnsTrue() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(7, 1_000);
        assertTrue(t.onResponse(7));
        assertFalse(t.hasPending());
    }

    @Test
    public void nonMatchingResponseKeepsPendingAndReturnsFalse() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(7, 1_000);
        assertFalse(t.onResponse(0));    // e.g. a keepalive PONG (PING seq = 0)
        assertFalse(t.onResponse(6));    // some other request's reply
        assertTrue(t.hasPending());
        assertEquals(7, t.pendingSeq());
    }

    @Test
    public void responseWithNothingPendingReturnsFalse() {
        FlowAckTracker t = new FlowAckTracker();
        assertFalse(t.onResponse(7));
    }

    @Test
    public void notOverdueBeforeTimeoutOverdueAfter() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(1, 1_000);
        assertFalse(t.isAckOverdue(1_000 + TIMEOUT, TIMEOUT));       // exactly at the boundary is not > timeout
        assertFalse(t.isAckOverdue(1_000 + TIMEOUT - 1, TIMEOUT));
        assertTrue(t.isAckOverdue(1_000 + TIMEOUT + 1, TIMEOUT));
    }

    @Test
    public void ackedRequestIsNeverOverdue() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(1, 1_000);
        assertTrue(t.onResponse(1));
        assertFalse(t.isAckOverdue(1_000_000, TIMEOUT));   // confirmed → no reconnect, whatever the clock
    }

    @Test
    public void resetForgetsPendingSoStaleAckCantConfirm() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(1, 1_000);
        t.reset();                                  // fresh session
        assertFalse(t.hasPending());
        assertFalse(t.isAckOverdue(1_000_000, TIMEOUT));
        assertFalse(t.onResponse(1));               // the old session's ack must not satisfy the new one
    }

    @Test
    public void resendSupersedesSoOnlyLatestSeqConfirms() {
        FlowAckTracker t = new FlowAckTracker();
        t.onSent(1, 1_000);
        t.onSent(2, 2_000);                         // re-sent (e.g. LIVE re-assert) — seq 2 is now in flight
        assertFalse(t.onResponse(1));               // stale ack for the superseded send is ignored
        assertTrue(t.hasPending());
        assertTrue(t.onResponse(2));                // the current seq confirms
        assertFalse(t.hasPending());
    }

    // --- the end-to-end algorithm the caller runs ---

    @Test
    public void algorithm_ackWithinTimeout_noReconnect() {
        FlowAckTracker t = new FlowAckTracker();
        t.setDesired(FlowAction.START);
        t.onSent(10, 1_000);                        // send FLOW_CONTROL START
        t.onResponse(10);                           // ack arrives 200ms later (server is healthy)
        assertFalse(t.isAckOverdue(1_200, TIMEOUT)); // → stay connected
    }

    @Test
    public void algorithm_noAckWithinTimeout_signalsReconnect() {
        FlowAckTracker t = new FlowAckTracker();
        t.setDesired(FlowAction.START);
        t.onSent(10, 1_000);                        // send FLOW_CONTROL START
        // ...no ack (link wedged)...
        assertTrue(t.isAckOverdue(1_000 + TIMEOUT + 1, TIMEOUT));   // caller reconnects; desired persists
        assertEquals(FlowAction.START, t.desired()); // re-asserted on the fresh session's LIVE entry
    }
}
