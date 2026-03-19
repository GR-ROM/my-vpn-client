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
                try {
                    int bytesRead = tun.readPacket(buf);
                    if (bytesRead > 20) {
                        buf.flip();
                        onTunPacketReceived(buf);
                    }
                } finally {
                    bufferPool.release(buf);
                }
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public abstract void onTunPacketReceived(ByteBuffer packet);

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
