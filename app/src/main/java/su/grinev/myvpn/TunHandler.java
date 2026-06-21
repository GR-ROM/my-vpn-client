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
        try {
            while (!stop) {
                ByteBuffer buf = bufferPool.get();
                boolean handed = false;
                try {
                    int bytesRead = tun.readPacket(buf);
                    if (bytesRead > 20) {
                        buf.flip();
                        handed = onTunPacketReceived(buf);   // true if the consumer took ownership
                    }
                } finally {
                    if (!handed) {
                        bufferPool.release(buf);
                    }
                }
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
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
