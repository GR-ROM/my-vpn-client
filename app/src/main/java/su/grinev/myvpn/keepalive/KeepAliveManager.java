package su.grinev.myvpn.keepalive;

import java.io.DataOutputStream;
import java.time.Instant;
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

public class KeepAliveManager {

    public interface KeepAliveCallback { void onConnectionDead(); }
    private static final Instant FIXED_TIMESTAMP = Instant.now();
    private static final long KEEPALIVE_INTERVAL_MS = 30000; // 30 seconds
    private static final long PONG_TIMEOUT_MS = 10000; // 10 seconds to wait for PONG
    private static final long CHECK_INTERVAL_MS = 5000; // Check every 5 seconds
    private final Object lock;
    private final Codec codec;
    private final KeepAliveCallback callback;
    private volatile DataOutputStream outputStream;
    private final AtomicLong lastPacketReceivedTime = new AtomicLong(0);
    private final AtomicLong pingSentTime = new AtomicLong(0);
    private final AtomicBoolean awaitingPong = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KeepAliveThread");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> checkTask;
    private final RequestDto<Void> pingRequestDto = new RequestDto<>();
    private final Packet<RequestDto<?>> pingPacketDto = new Packet<>();

    public KeepAliveManager(Object lock, Codec codec, KeepAliveCallback callback) {
        this.lock = lock;
        this.codec = codec;
        this.callback = callback;

        pingRequestDto.setCommand(Command.PING);
        pingRequestDto.setData(null);
        pingPacketDto.setVer("0.1");
        pingPacketDto.setPayload(pingRequestDto);
    }

    public void start(DataOutputStream outputStream) {
        if (isRunning.compareAndSet(false, true)) {
            this.outputStream = outputStream;
            lastPacketReceivedTime.set(System.currentTimeMillis());
            awaitingPong.set(false);
            pingSentTime.set(0);

            checkTask = scheduler.scheduleWithFixedDelay(
                    this::checkConnection,
                    CHECK_INTERVAL_MS,
                    CHECK_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
            );

            DebugLog.log("KeepAlive started");
        }
    }

    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (checkTask != null) {
                checkTask.cancel(false);
                checkTask = null;
            }
            outputStream = null;
            awaitingPong.set(false);
            DebugLog.log("KeepAlive stopped");
        }
    }

    public void destroy() {
        stop();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void onPacketReceived() {
        lastPacketReceivedTime.set(System.currentTimeMillis());
    }

    public void onPongReceived() {
        if (awaitingPong.compareAndSet(true, false)) {
            long rtt = System.currentTimeMillis() - pingSentTime.get();
            DebugLog.log("PONG received, RTT: " + rtt + "ms");
        }
        lastPacketReceivedTime.set(System.currentTimeMillis());
    }

    private void checkConnection() {
        if (!isRunning.get()) { return; }

        long now = System.currentTimeMillis();
        long timeSinceLastPacket = now - lastPacketReceivedTime.get();

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

        if (!awaitingPong.get() && timeSinceLastPacket > KEEPALIVE_INTERVAL_MS) { sendPing(); }
    }

    private void sendPing() {
        DataOutputStream out = outputStream;
        if (out == null) {
            return;
        }

        try {
            pingPacketDto.setTimestamp(FIXED_TIMESTAMP);

            synchronized (lock) {
                codec.serialize(pingPacketDto, out);
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
