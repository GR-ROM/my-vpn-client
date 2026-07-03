package su.grinev.myvpn;

import java.io.IOException;
import java.nio.ByteBuffer;

import su.grinev.pool.FastPool;

public abstract class TunHandler {
    public static final int MAX_MTU = 4 * 1024;
    /** How long stop() waits for the reader to exit; readPacket's poll wakes within ~2s. */
    private static final long READER_JOIN_TIMEOUT_MS = 3000;
    public volatile boolean stop = false;
    protected volatile boolean running = false;
    protected final Tun tun;
    protected final Thread readerThread = new Thread(this::handleTunPackets, "TunReader");
    protected final FastPool<ByteBuffer> bufferPool;
    // Guarded by 'this' (start/stop are synchronized). A Thread can only be started once, and a
    // stopped handler must never restart — both callers used to race on the public 'running' flag.
    private boolean started = false;

    public TunHandler(Tun tun, FastPool<ByteBuffer> bufferPool) {
        this.tun = tun;
        this.bufferPool = bufferPool;
    }

    private void handleTunPackets() {
        while (!stop) {
            // pool.get() stays inside the guard: a pool RuntimeException must not escape
            // and silently kill the reader thread (upload would be dead while the UI
            // still shows LIVE). IOException = TUN closed/revoked — exit; the service
            // teardown owns recovery.
            ByteBuffer buf = null;
            boolean handed = false;
            try {
                buf = bufferPool.get();
                int bytesRead = tun.readPacket(buf);
                if (bytesRead > 20) {
                    buf.flip();
                    handed = onTunPacketReceived(buf);   // true if the consumer took ownership
                }
            } catch (IOException ioException) {
                if (!stop) {
                    DebugLog.log("TUN reader stopped: " + ioException);
                }
                releaseQuietly(buf, handed);
                buf = null;   // released here — the finally below must not release again
                break;
            } catch (RuntimeException e) {
                if (!stop) {
                    DebugLog.log("TUN reader error (continuing): " + e);
                }
            } finally {
                releaseQuietly(buf, handed);
            }
        }
    }

    /**
     * Release a not-handed-off buffer without ever throwing. This runs on the reader — a PLAIN
     * thread with no uncaught-exception handler, so anything escaping here (e.g. the pool's
     * "Double release detected" IllegalStateException when release bookkeeping goes transiently
     * negative during the disconnect burst) kills the whole app process. Log and move on instead.
     */
    private void releaseQuietly(ByteBuffer buf, boolean handed) {
        if (buf == null || handed) {
            return;
        }
        try {
            bufferPool.release(buf);
        } catch (RuntimeException e) {
            DebugLog.log("TUN buffer release failed (ignored): " + e);
        }
    }

    /** Returns true if the consumer took ownership of {@code packet} (caller must not release it). */
    public abstract boolean onTunPacketReceived(ByteBuffer packet);

    protected synchronized void start() {
        // Idempotent: two sessions going LIVE at once both try to start the reader (the old
        // unsynchronized 'if (!running)' check raced → IllegalThreadStateException on the second
        // Thread.start()). Also refuse to start after stop() — a Thread object can't be reused.
        if (started || stop) {
            return;
        }
        started = true;
        readerThread.start();
        running = true;
    }

    protected synchronized void stop() {
        stop = true;
        if (readerThread.isAlive()) {
            readerThread.interrupt();
            // Wait for the reader to actually exit so no packet is pumped into the send queues /
            // pool after teardown proceeds (that post-stop traffic is what raced the queue drains
            // and the TUN close). poll()'s 2s timeout bounds how long the join can take.
            try {
                readerThread.join(READER_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (readerThread.isAlive()) {
                DebugLog.log("TUN reader did not exit within " + READER_JOIN_TIMEOUT_MS + "ms");
            }
        }
        running = false;
    }
}
