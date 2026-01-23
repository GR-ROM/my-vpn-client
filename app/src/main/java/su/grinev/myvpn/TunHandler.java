package su.grinev.myvpn;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class TunHandler {
    public static final int MAX_MTU = 4 * 1024;
    public volatile boolean stop = false;
    protected volatile boolean running = false;
    protected final Tun tun;
    protected final Thread readerThread = new Thread(this::handleTunPackets);
    protected final BufferPool bufferPool;

    public TunHandler(Tun tun, BufferPool bufferPool) {
        this.tun = tun;
        this.bufferPool = bufferPool;
    }

    private void handleTunPackets() {
        AtomicInteger bytesRead = new AtomicInteger(0);
        try {
            while (!stop) {
                byte[] packet = bufferPool.getBuffer();
                try {
                    tun.readPacket(packet, bytesRead);
                    if (bytesRead.get() > 20) {
                        onTunPacketReceived(packet, bytesRead.get());
                    }
                } finally {
                    bufferPool.release(packet);
                }
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public abstract void onTunPacketReceived(byte[] packet, int bytesRead);

    private String getProtocolName(int protocol) {
        return switch (protocol) {
            case 1 -> "ICMP";
            case 6 -> "TCP";
            case 17 -> "UDP";
            default -> "Unknown";
        };
    }

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
