package su.grinev.myvpn;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.VpnService;
import android.util.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import su.grinev.myvpn.handlers.ScreenStateHandler;
import su.grinev.myvpn.notification.VpnNotificationManager;
import su.grinev.myvpn.settings.SettingsProvider;
import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;
import su.grinev.myvpn.state.VpnStateManager;
import su.grinev.myvpn.traffic.TrafficStatsManager;

@SuppressLint("VpnServicePolicy")
public class MyVpnService extends VpnService implements ScreenStateHandler.ScreenStateCallback {

    public static final String ACTION_DISCONNECT = "su.grinev.myvpn.DISCONNECT";
    private SettingsProvider settingsProvider;
    private VpnNotificationManager notificationManager;
    private ScreenStateHandler screenStateHandler;
    private final VpnStateManager stateManager = VpnStateManager.getInstance();
    private final TrafficStatsManager trafficStats = TrafficStatsManager.getInstance();
    private final Object vpnLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private VpnClientWrapper vpnClientWrapper;
    private volatile boolean isConnecting = false;
    private boolean wasConnectedBeforeSleep = false;
    private boolean isSleeping = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        initializeDependencies();

        startForeground(VpnNotificationManager.getNotificationId(), notificationManager.buildNotification(R.string.notif_starting));

        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            wasConnectedBeforeSleep = false;
            CompletableFuture.runAsync(this::stopVpnSync, executor);
            return START_NOT_STICKY;
        }

        screenStateHandler.register();

        DebugLog.log("VPN service started");
        DebugLog.log("Server: " + settingsProvider.getServerIp() + ":" + settingsProvider.getServerPort());

        startVpnConnection();
        return START_STICKY;
    }

    private void initializeDependencies() {
        if (settingsProvider == null) {
            settingsProvider = new SharedPreferencesSettingsProvider(this);
        }
        if (notificationManager == null) {
            notificationManager = new VpnNotificationManager(this, ACTION_DISCONNECT, MyVpnService.class);
        }
        if (screenStateHandler == null) {
            screenStateHandler = new ScreenStateHandler(this, this);
        }
    }

    private void startVpnConnection() {
        synchronized (vpnLock) {
            if (isConnecting) {
                DebugLog.log("Connection already in progress, ignoring");
                return;
            }
            isConnecting = true;
        }

        try {
            updateState(State.CONNECTING);
            TunAndroid tunAndroid = new TunAndroid(this);

            VpnClientWrapper newWrapper = new VpnClientWrapper(
                    tunAndroid,
                    settingsProvider.getServerIp(),
                    settingsProvider.getServerPort(),
                    settingsProvider.getJwt(),
                    true,
                    this::onVpnStateChanged
            );

            synchronized (vpnLock) {
                vpnClientWrapper = newWrapper;
                isConnecting = false;
            }

        } catch (Exception e) {
            DebugLog.log("VPN error: " + Log.getStackTraceString(e));
            synchronized (vpnLock) {
                isConnecting = false;
            }
            onVpnStateChanged(State.ERROR);
        }
    }

    /**
     * Callback from VpnClientWrapper when state changes.
     */
    private void onVpnStateChanged(State state) {
        updateState(state);

        switch (state) {
            case CONNECTED:
                trafficStats.start();
                break;
            case DISCONNECTED, SHUTDOWN:
                trafficStats.stop();
                if (!isSleeping) {
                    stopSelf();
                }
                break;
            case ERROR:
                trafficStats.stop();
                stopSelf();
                break;
        }
    }

    private void updateState(State state) {
        stateManager.setState(state);
        notificationManager.updateNotificationForState(state);
    }

    // ==================== ScreenStateCallback Implementation ====================

    @Override
    public void onScreenOff() {
        synchronized (vpnLock) {
            if (vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive()) {
                wasConnectedBeforeSleep = true;
                isSleeping = true;
                updateState(State.SLEEPING);
            }
        }
    }

    @Override
    public void onScreenOn() {
        isSleeping = false;

        if (wasConnectedBeforeSleep) {
            wasConnectedBeforeSleep = false;

            boolean connectionAlive;
            synchronized (vpnLock) {
                connectionAlive = vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive();
            }

            if (connectionAlive) {
                DebugLog.log("Connection still alive after wake");
                updateState(State.CONNECTED);
            } else {
                DebugLog.log("Connection lost during suspend, reconnecting");
                reconnect();
            }
        }
    }

    // ==================== Connection Management ====================

    private void reconnect() {
        CompletableFuture.runAsync(() -> {
            notificationManager.updateNotification(R.string.notif_reconnecting);
            stateManager.setState(State.CONNECTING);
            synchronized (vpnLock) {
                if (vpnClientWrapper != null) {
                    vpnClientWrapper.stop();
                    vpnClientWrapper = null;
                }
                isConnecting = false;
            }
            startVpnConnection();
        }, executor);
    }

    private void stopVpnSync() {
        synchronized (vpnLock) {
            if (vpnClientWrapper != null) {
                vpnClientWrapper.stop();
                vpnClientWrapper = null;
            }
            isConnecting = false;
        }

        updateState(State.DISCONNECTED);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ==================== Lifecycle ====================

    @Override
    public void onDestroy() {
        DebugLog.log("VPN service destroyed");

        trafficStats.stop();
        trafficStats.reset();

        synchronized (vpnLock) {
            if (vpnClientWrapper != null) {
                vpnClientWrapper.stop();
                vpnClientWrapper = null;
            }
            isConnecting = false;
        }

        if (screenStateHandler != null) {
            screenStateHandler.unregister();
        }

        shutdownExecutor();
        stateManager.reset();
        super.onDestroy();
    }

    private void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        DebugLog.log("App swiped away, stopping VPN service");
        wasConnectedBeforeSleep = false;

        synchronized (vpnLock) {
            if (vpnClientWrapper != null) {
                vpnClientWrapper.stop();
                vpnClientWrapper = null;
            }
            isConnecting = false;
        }

        if (screenStateHandler != null) {
            screenStateHandler.unregister();
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    // ==================== Static Accessors (for backward compatibility) ====================

    /**
     * @deprecated Use VpnStateManager.getInstance().observeState() instead
     */
    @Deprecated
    public static void observeState(java.util.function.Consumer<State> listener) {
        VpnStateManager.getInstance().observeState(listener);
    }

    /**
     * @deprecated Use VpnStateManager.getInstance().unobserveState() instead
     */
    @Deprecated
    public static void unobserveState(java.util.function.Consumer<State> listener) {
        VpnStateManager.getInstance().unobserveState(listener);
    }

    /**
     * @deprecated Use VpnStateManager.getInstance().getState() instead
     */
    @Deprecated
    public static State getCurrentState() {
        return VpnStateManager.getInstance().getState();
    }
}
