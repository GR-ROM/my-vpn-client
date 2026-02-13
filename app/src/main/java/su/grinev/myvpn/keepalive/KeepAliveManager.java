package su.grinev.myvpn.keepalive;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import su.grinev.Codec;
import su.grinev.model.Command;
import su.grinev.model.Packet;
import su.grinev.model.RequestDto;
import su.grinev.myvpn.DebugLog;

/**
 * Manages connection keep-alive by sending periodic PING packets.
 * Single Responsibility: Monitor connection health and send keep-alive pings.
 *
 * Usage:
 * 1. Call onPacketReceived() whenever any packet is received from server
 * 2. Call onPongReceived() when a PONG response is received
 * 3. Call start() when connection is established (LIVE state)
 * 4. Call stop() when disconnecting
 */
public class KeepAliveManager {

    /**
     * Callback interface for keep-alive events.
     */
    public interface KeepAliveCallback {
        /**
         * Called when keep-alive detects connection is dead (no PONG response).
         */
        void onConnectionDead();
    }

    private static final long KEEPALIVE_INTERVAL_MS = 5000; // 5 seconds
    private static final long PONG_TIMEOUT_MS = 3000; // 3 seconds to wait for PONG
    private static final long CHECK_INTERVAL_MS = 1000; // Check every 1 second

    private final Object lock;
    private final Codec codec;
    private final KeepAliveCallback callback;

    private volatile DataOutputStream outputStream;
    private final AtomicLong lastPacketReceivedTime = new AtomicLong(0);
    private final AtomicLong pingSentTime = new AtomicLong(0);
    private final AtomicBoolean awaitingPong = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> checkTask;

    public KeepAliveManager(Object lock, Codec codec, KeepAliveCallback callback) {
        this.lock = lock;
        this.codec = codec;
        this.callback = callback;
    }

    /**
     * Start the keep-alive monitoring.
     * Should be called when connection enters LIVE state.
     *
     * @param outputStream The output stream to send PING packets
     */
    public void start(DataOutputStream outputStream) {
        if (isRunning.compareAndSet(false, true)) {
            this.outputStream = outputStream;
            lastPacketReceivedTime.set(System.currentTimeMillis());
            awaitingPong.set(false);
            pingSentTime.set(0);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "KeepAliveThread");
                t.setDaemon(true);
                return t;
            });

            checkTask = scheduler.scheduleWithFixedDelay(
                    this::checkConnection,
                    CHECK_INTERVAL_MS,
                    CHECK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );

            DebugLog.log("KeepAlive started");
        }
    }

    /**
     * Stop the keep-alive monitoring.
     * Should be called when disconnecting.
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (checkTask != null) {
                checkTask.cancel(false);
                checkTask = null;
            }

            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                scheduler = null;
            }

            outputStream = null;
            awaitingPong.set(false);
            DebugLog.log("KeepAlive stopped");
        }
    }

    /**
     * Notify that a packet was received from the server.
     * Call this for ANY packet received (FORWARD_PACKET, PONG, etc.)
     */
    public void onPacketReceived() {
        lastPacketReceivedTime.set(System.currentTimeMillis());
    }

    /**
     * Notify that a PONG response was received.
     * This clears the awaiting PONG state.
     */
    public void onPongReceived() {
        if (awaitingPong.compareAndSet(true, false)) {
            long rtt = System.currentTimeMillis() - pingSentTime.get();
            DebugLog.log("PONG received, RTT: " + rtt + "ms");
        }
        lastPacketReceivedTime.set(System.currentTimeMillis());
    }

    /**
     * Check if currently waiting for a PONG response.
     */
    public boolean isAwaitingPong() {
        return awaitingPong.get();
    }

    /**
     * Get the time since last packet was received.
     */
    public long getTimeSinceLastPacket() {
        return System.currentTimeMillis() - lastPacketReceivedTime.get();
    }

    private void checkConnection() {
        if (!isRunning.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        long timeSinceLastPacket = now - lastPacketReceivedTime.get();

        // Check if we're waiting for PONG and it timed out
        if (awaitingPong.get()) {
            long timeSincePing = now - pingSentTime.get();
            if (timeSincePing > PONG_TIMEOUT_MS) {
                DebugLog.log("PONG timeout after " + timeSincePing + "ms, connection dead");
                awaitingPong.set(false);
                isRunning.set(false);
                callback.onConnectionDead();
                return;
            }
        }

        // Check if we need to send a PING
        if (!awaitingPong.get() && timeSinceLastPacket > KEEPALIVE_INTERVAL_MS) {
            sendPing();
        }
    }

    private void sendPing() {
        DataOutputStream out = outputStream;
        if (out == null) {
            return;
        }

        try {
            RequestDto<Void> pingRequest = RequestDto.wrap(Command.PING);
            Packet<RequestDto<?>> packet = Packet.ofRequest(pingRequest);

            synchronized (lock) {
                codec.serialize(packet, out);
            }

            pingSentTime.set(System.currentTimeMillis());
            awaitingPong.set(true);
            DebugLog.log("PING sent");

        } catch (IOException e) {
            DebugLog.log("Failed to send PING: " + e.getMessage());
            isRunning.set(false);
            callback.onConnectionDead();
        }
    }
}
