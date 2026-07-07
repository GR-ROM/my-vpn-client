package su.grinev.myvpn;

import su.grinev.model.FlowAction;

/**
 * Tracks the FLOW_CONTROL request/ack handshake for one connection (spec §11.3).
 *
 * <p>The app sets the desired downlink state ({@link #setDesired}); each send records the in-flight
 * seq and send time ({@link #onSent}); the server's {@code ResponseDto} whose {@code requestId}
 * equals that seq confirms it ({@link #onResponse}); if no ack arrives within the timeout
 * ({@link #isAckOverdue}) the caller reconnects. A fresh connection {@link #reset}s so a stale ack
 * from the previous session can't satisfy the new one.
 *
 * <p>Fields are {@code volatile} and written in publish order (time before seq, seq is the commit)
 * so the reader thread never observes a torn (seq, sentMs) pair — the same discipline the inline
 * VpnClient code used before this was extracted for {@link FlowAckTrackerTest}.
 */
public final class FlowAckTracker {

    static final int NO_PENDING = -1;

    private volatile FlowAction desired = FlowAction.START;
    private volatile long pendingSentMs;
    private volatile int pendingSeq = NO_PENDING;

    /** The downlink state the app currently wants (START while the screen is on, STOP otherwise). */
    public FlowAction desired() {
        return desired;
    }

    public void setDesired(FlowAction action) {
        this.desired = action;
    }

    public boolean hasPending() {
        return pendingSeq != NO_PENDING;
    }

    public int pendingSeq() {
        return pendingSeq;
    }

    /** Record a FLOW_CONTROL just sent with {@code seq} at {@code nowMs}; starts the ack-timeout clock. */
    public void onSent(int seq, long nowMs) {
        pendingSentMs = nowMs;   // publish time before seq — seq is the commit the reader keys on
        pendingSeq = seq;
    }

    /**
     * A {@code ResponseDto} arrived. Returns {@code true} (and clears the pending ack) iff its
     * {@code requestId} matches the in-flight FLOW_CONTROL seq; {@code false} otherwise (e.g. a PONG,
     * an upload reply, or a stale ack for a superseded send).
     */
    public boolean onResponse(int requestId) {
        if (pendingSeq != NO_PENDING && requestId == pendingSeq) {
            pendingSeq = NO_PENDING;
            return true;
        }
        return false;
    }

    /**
     * {@code true} if a FLOW_CONTROL ack is still outstanding and older than {@code timeoutMs} — the
     * link is wedged and the caller should drop and reconnect.
     */
    public boolean isAckOverdue(long nowMs, long timeoutMs) {
        return pendingSeq != NO_PENDING && nowMs - pendingSentMs > timeoutMs;
    }

    /** Fresh connection: forget any in-flight ack (its seq belonged to the old session). */
    public void reset() {
        pendingSeq = NO_PENDING;
    }
}
