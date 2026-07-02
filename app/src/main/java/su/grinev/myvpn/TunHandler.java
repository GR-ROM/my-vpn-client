package su.grinev.myvpn;

import java.io.IOException;
import java.nio.ByteBuffer;

import su.grinev.pool.FastPool;

public abstract class TunHandler {
    public static final int MAX_MTU = 4 * 1024;
    public volatile boolean stop = false;
    protected volatile boolean running = false;
    protected final Tun tun;
    protected final Thread readerThread = new Thread(this::handleTunPackets);
    protected final FastPool<ByteBuffer> bufferPool;

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
                break;   // finally still releases buf
            } catch (RuntimeException e) {
                if (!stop) {
                    DebugLog.log("TUN reader error (continuing): " + e);
                }
            } finally {
                if (buf != null && !handed) {
                    bufferPool.release(buf);
                }
            }
        }
    }

    /** Returns true if the consumer took ownership of {@code packet} (caller must not release it). */
    public abstract boolean onTunPacketReceived(ByteBuffer packet);

    protected synchronized void start() {
        readerThread.start();
        running = true;
    }

    protected synchronized void stop() {
        stop = true;
        if (readerThread.isAlive()) {
            readerThread.interrupt();
        }
        running = false;
    }
}
