package su.grinev.myvpn;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BufferPool {

    private final List<byte[]> available = new LinkedList<>();
    private final Set<byte[]> occupied = new HashSet<>();
    private final AtomicBoolean blocked = new AtomicBoolean(false);

    public BufferPool(int poolSize, int bufferSize) {
        for (int i = 0; i != poolSize; i++) {
            byte[] buffer = new byte[bufferSize];
            available.add(buffer);
        }
    }

    public byte[] getBuffer() {
        if (available.isEmpty()) {
            synchronized (this) {
                try {
                    blocked.set(true);
                    wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            byte[] buffer;
            synchronized (this) {
                buffer = available.remove(0);
                occupied.add(buffer);
            }
            return buffer;
        }
        throw new IllegalStateException("Unable to to allocate buffer");
    }

    public void release(byte[] buffer) {
        if (!occupied.contains(buffer)) {
            throw new IllegalArgumentException("Buffer wasn't allocated");
        }
        synchronized (this) {
            available.add(buffer);
            occupied.remove(buffer);
            if (blocked.compareAndExchange(true, false)) { this.notify(); }
        }
    }

}
