package su.grinev.myvpn;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
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
import su.grinev.pool.PoolFactory;

@SuppressLint("VpnServicePolicy")
public class MyVpnService extends VpnService implements ScreenStateHandler.ScreenStateCallback {

    public static final String ACTION_DISCONNECT = "su.grinev.myvpn.DISCONNECT";
    private SettingsProvider settingsProvider;
    private VpnNotificationManager notificationManager;
    private ScreenStateHandler screenStateHandler;
    private VpnStateManager stateManager;
    private TrafficStatsManager trafficStats;
    private final Object vpnLock = new Object();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private VpnClientWrapper vpnClientWrapper;
    private volatile boolean isConnecting = false;
    private volatile boolean isStopping = false;
    private boolean wasConnectedBeforeSleep = false;
    private boolean isSleeping = false;
    private static volatile PoolFactory poolFactory;

    private static PoolFactory getPoolFactory() {
        if (poolFactory == null) {
            synchronized (MyVpnService.class) {
                if (poolFactory == null) {
                    DebugLog.log("getPoolFactory: creating PoolFactory");
                    poolFactory = PoolFactory.Builder.builder()
                            .setMinPoolSize(100)
                            .setMaxPoolSize(1000)
                            .setBlocking(true)
                            .setOutOfPoolTimeout(100)
                            .build();
                    DebugLog.log("getPoolFactory: OK");
                }
            }
        }
        return poolFactory;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("MyVPN", "onCreate: entry, SDK=" + Build.VERSION.SDK_INT
                + " (" + Build.VERSION.RELEASE + "), device=" + Build.MANUFACTURER + " " + Build.MODEL);

        try {
            stateManager = VpnStateManager.getInstance();
            trafficStats = TrafficStatsManager.getInstance();
        } catch (Exception e) {
            Log.e("MyVPN", "onCreate: singleton init failed", e);
        }

        // Call startForeground ASAP to avoid ForegroundServiceDidNotStartInTimeException
        try {
            notificationManager = new VpnNotificationManager(this, ACTION_DISCONNECT, MyVpnService.class);
            android.app.Notification notification = notificationManager.buildNotification(R.string.notif_starting);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d("MyVPN", "onCreate: startForeground with FOREGROUND_SERVICE_TYPE_SPECIAL_USE");
                startForeground(VpnNotificationManager.getNotificationId(),
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                Log.d("MyVPN", "onCreate: startForeground (legacy, no type)");
                startForeground(VpnNotificationManager.getNotificationId(), notification);
            }
            Log.d("MyVPN", "onCreate: startForeground OK");
        } catch (Exception e) {
            Log.e("MyVPN", "onCreate: startForeground FAILED", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLog.log("onStartCommand: entry, SDK=" + Build.VERSION.SDK_INT
                + " (" + Build.VERSION.RELEASE + "), device=" + Build.MANUFACTURER + " " + Build.MODEL);
        DebugLog.log("onStartCommand: intent=" + intent
                + ", action=" + (intent != null ? intent.getAction() : "null")
                + ", flags=" + flags + ", startId=" + startId);

        try {
            DebugLog.log("onStartCommand: initializing dependencies");
            initializeDependencies();
            DebugLog.log("onStartCommand: dependencies initialized OK");
        } catch (Exception e) {
            DebugLog.log("onStartCommand: initializeDependencies FAILED: " + Log.getStackTraceString(e));
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            DebugLog.log("onStartCommand: DISCONNECT action received");
            wasConnectedBeforeSleep = false;
            CompletableFuture.runAsync(this::stopVpnSync, executor);
            return START_NOT_STICKY;
        }

        try {
            DebugLog.log("onStartCommand: registering screen state handler");
            screenStateHandler.register();
            DebugLog.log("onStartCommand: screen state handler registered OK");
        } catch (Exception e) {
            DebugLog.log("onStartCommand: screenStateHandler.register FAILED: " + Log.getStackTraceString(e));
        }

        DebugLog.log("VPN service started");
        DebugLog.log("Server: " + settingsProvider.getServerIp() + ":" + settingsProvider.getServerPort());

        startVpnConnection();
        return START_STICKY;
    }

    private void initializeDependencies() {
        if (settingsProvider == null) {
            DebugLog.log("initDeps: creating SharedPreferencesSettingsProvider");
            settingsProvider = new SharedPreferencesSettingsProvider(this);
            DebugLog.log("initDeps: settingsProvider OK");
        }
        if (notificationManager == null) {
            DebugLog.log("initDeps: creating VpnNotificationManager");
            notificationManager = new VpnNotificationManager(this, ACTION_DISCONNECT, MyVpnService.class);
            DebugLog.log("initDeps: notificationManager OK");
        }
        if (screenStateHandler == null) {
            DebugLog.log("initDeps: creating ScreenStateHandler");
            screenStateHandler = new ScreenStateHandler(this, this);
            DebugLog.log("initDeps: screenStateHandler OK");
        }
    }

    private void startVpnConnection() {
        DebugLog.log("startVpnConnection: entry");
        synchronized (vpnLock) {
            if (isConnecting) {
                DebugLog.log("startVpnConnection: already in progress, ignoring");
                return;
            }
            isConnecting = true;
        }

        try {
            DebugLog.log("startVpnConnection: setting state CONNECTING");
            updateState(State.CONNECTING);

            DebugLog.log("startVpnConnection: creating TunAndroid");
            TunAndroid tunAndroid = new TunAndroid(this);
            DebugLog.log("startVpnConnection: TunAndroid created OK");

            DebugLog.log("startVpnConnection: creating VpnClientWrapper, server="
                    + settingsProvider.getServerIp() + ":" + settingsProvider.getServerPort());
            VpnClientWrapper newWrapper = new VpnClientWrapper(
                    tunAndroid,
                    settingsProvider.getServerIp(),
                    settingsProvider.getServerPort(),
                    settingsProvider.getJwt(),
                    true,
                    getPoolFactory(),
                    this::onVpnStateChanged
            );
            DebugLog.log("startVpnConnection: VpnClientWrapper created OK");

            synchronized (vpnLock) {
                vpnClientWrapper = newWrapper;
                isConnecting = false;
            }

        } catch (Exception e) {
            DebugLog.log("startVpnConnection: FAILED: " + e.getClass().getName()
                    + ": " + e.getMessage() + "\n" + Log.getStackTraceString(e));
            synchronized (vpnLock) {
                isConnecting = false;
            }
            onVpnStateChanged(State.ERROR);
        }
    }

    /**
     * Callback from VpnClientWrapper when state changes.
     * Note: DISCONNECTED from VpnClient is transient — it will auto-reconnect.
     * We report it as WAITING so the UI shows "reconnecting" instead of "disconnected".
     * The true DISCONNECTED state is only set by stopVpnSync when the service actually stops.
     * Only SHUTDOWN (auth failure) and ERROR are terminal.
     */
    private void onVpnStateChanged(State state) {
        if (isStopping) return;

        switch (state) {
            case CONNECTED:
                trafficStats.start();
                updateState(state);
                break;
            case DISCONNECTED:
                trafficStats.stop();
                // VpnClient will auto-reconnect — show as WAITING, not DISCONNECTED.
                updateState(State.WAITING);
                break;
            case SHUTDOWN:
                trafficStats.stop();
                updateState(state);
                if (!isSleeping) {
                    CompletableFuture.runAsync(this::stopVpnSync, executor);
                }
                break;
            case ERROR:
                trafficStats.stop();
                updateState(state);
                CompletableFuture.runAsync(this::stopVpnSync, executor);
                break;
            default:
                updateState(state);
                break;
        }
    }

    private void updateState(State state) {
        if (stateManager != null) {
            stateManager.setState(state);
        }
        if (notificationManager != null) {
            notificationManager.updateNotificationForState(state);
        }
    }

    // ==================== ScreenStateCallback Implementation ====================

    @Override
    public void onScreenOff() {
        synchronized (vpnLock) {
            if (vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive()) {
                wasConnectedBeforeSleep = true;
                isSleeping = true;
                vpnClientWrapper.pauseKeepAlive();
                if (trafficStats != null) {
                    trafficStats.stop();
                }
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
                if (connectionAlive) {
                    vpnClientWrapper.resumeKeepAlive();
                }
            }

            if (connectionAlive) {
                DebugLog.log("Connection still alive after wake");
                if (trafficStats != null) {
                    trafficStats.start();
                }
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
        if (isStopping) return;
        isStopping = true;

        synchronized (vpnLock) {
            if (vpnClientWrapper != null) {
                vpnClientWrapper.stop();
                vpnClientWrapper = null;
            }
            isConnecting = false;
        }

        updateState(State.DISCONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    // ==================== Lifecycle ====================

    @Override
    public void onDestroy() {
        DebugLog.log("VPN service destroyed");

        if (trafficStats != null) {
            trafficStats.stop();
            trafficStats.reset();
        }

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
        if (stateManager != null) {
            stateManager.reset();
        }
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
        isStopping = true;
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
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
