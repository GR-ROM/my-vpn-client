package su.grinev.myvpn.traffic;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Manages traffic statistics tracking and rate computation.
 * Singleton - accessed via getInstance().
 */
public class TrafficStatsManager {
    private static final TrafficStatsManager INSTANCE = new TrafficStatsManager();
    private static final int MAX_HISTORY_SIZE = 3600;
    private static final long UPDATE_INTERVAL_MS = 1000; // 1 second
    private final AtomicLong incomingBytes = new AtomicLong(0);
    private final AtomicLong outgoingBytes = new AtomicLong(0);
    private final AtomicLong lastIncomingBytes = new AtomicLong(0);
    private final AtomicLong lastOutgoingBytes = new AtomicLong(0);
    private final ArrayDeque<TrafficStats> history = new ArrayDeque<>();
    private final Object historyLock = new Object();
    private final AtomicInteger listenerIdGenerator = new AtomicInteger(0);
    private final Map<Integer, Consumer<TrafficStats>> listeners = new ConcurrentHashMap<>();
    private final Map<Consumer<TrafficStats>, Integer> listenerIds = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateRunnable = this::computeAndNotify;
    private volatile boolean isRunning = false;

    private TrafficStatsManager() {
    }

    public static TrafficStatsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Start the periodic rate computation.
     */
    public void start() {
        if (!isRunning) {
            isRunning = true;
            lastIncomingBytes.set(incomingBytes.get());
            lastOutgoingBytes.set(outgoingBytes.get());
            mainHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS);
        }
    }

    /**
     * Stop the periodic rate computation.
     */
    public void stop() {
        isRunning = false;
        mainHandler.removeCallbacks(updateRunnable);
    }

    /**
     * Reset all statistics.
     */
    public void reset() {
        incomingBytes.set(0);
        outgoingBytes.set(0);
        lastIncomingBytes.set(0);
        lastOutgoingBytes.set(0);
        synchronized (historyLock) {
            history.clear();
        }
    }

    /**
     * Record incoming bytes.
     */
    public void addIncomingBytes(int bytes) {
        incomingBytes.addAndGet(bytes);
    }

    /**
     * Record outgoing bytes.
     */
    public void addOutgoingBytes(int bytes) {
        outgoingBytes.addAndGet(bytes);
    }

    /**
     * Get current statistics snapshot.
     */
    public TrafficStats getCurrentStats() {
        long incoming = incomingBytes.get();
        long outgoing = outgoingBytes.get();
        long lastIncoming = lastIncomingBytes.get();
        long lastOutgoing = lastOutgoingBytes.get();

        double inRate = bytesToMbps(incoming - lastIncoming);
        double outRate = bytesToMbps(outgoing - lastOutgoing);

        return new TrafficStats(incoming, outgoing, inRate, outRate);
    }

    /**
     * Get history for graph rendering.
     */
    public List<TrafficStats> getHistory() {
        synchronized (historyLock) {
            return new ArrayList<>(history);
        }
    }

    /**
     * Register a listener for traffic updates.
     */
    public void addListener(Consumer<TrafficStats> listener) {
        if (listener == null) return;

        removeListener(listener);

        int id = listenerIdGenerator.incrementAndGet();
        listeners.put(id, listener);
        listenerIds.put(listener, id);
    }

    /**
     * Unregister a listener.
     */
    public void removeListener(Consumer<TrafficStats> listener) {
        if (listener == null) return;

        Integer id = listenerIds.remove(listener);
        if (id != null) {
            listeners.remove(id);
        }
    }

    private void computeAndNotify() {
        if (!isRunning) return;

        long currentIncoming = incomingBytes.get();
        long currentOutgoing = outgoingBytes.get();
        long prevIncoming = lastIncomingBytes.getAndSet(currentIncoming);
        long prevOutgoing = lastOutgoingBytes.getAndSet(currentOutgoing);

        double inRate = bytesToMbps(currentIncoming - prevIncoming) / ((double) UPDATE_INTERVAL_MS / 1000);
        double outRate = bytesToMbps(currentOutgoing - prevOutgoing) / ((double) UPDATE_INTERVAL_MS / 1000);

        TrafficStats stats = new TrafficStats(currentIncoming, currentOutgoing, inRate, outRate);

        synchronized (historyLock) {
            history.addLast(stats);
            while (history.size() > MAX_HISTORY_SIZE) {
                history.pollFirst();
            }
        }

        // Notify listeners on main thread
        for (Consumer<TrafficStats> listener : listeners.values()) {
            try {
                listener.accept(stats);
            } catch (Exception e) {
                // Ignore listener errors
            }
        }

        // Schedule next update
        if (isRunning) {
            mainHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS);
        }
    }

    private double bytesToMbps(long bytes) {
        // bytes per second to megabits per second
        // bytes * 8 (bits) / 1,000,000 (mega)
        return (bytes * 8.0) / 1_000_000.0;
    }
}
