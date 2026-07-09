package su.grinev.myvpn;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    public static final String ACTION_RECONNECT = "su.grinev.myvpn.RECONNECT";
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
    private static volatile MyVpnService instance;

    // Level-triggered screen-state reconcile. onScreenOn()/onScreenOff() are edge-triggered by the
    // ACTION_SCREEN_ON/OFF broadcasts, but Doze can delay or drop ACTION_SCREEN_ON after a long
    // screen-off — then onScreenOn() never fires, we stay in SLEEPING, and the downlink stays
    // FLOW_CONTROL STOP'd on the server → "connected but no internet after a long sleep". A periodic
    // poll of the real screen state (PowerManager.isInteractive) self-heals that missed edge.
    private PowerManager powerManager;
    private ScreenStateReconciler screenReconciler;
    private static final long SCREEN_RECONCILE_INTERVAL_MS = 10_000L;
    // On wake we resume a cached-LIVE connection optimistically; the resume sends FLOW_CONTROL START,
    // which a live server acks within a second or two. If the ack is still outstanding after this
    // window the peer died while we slept (e.g. the server restarted) — Socket.isConnected() can't
    // tell — so we force a reconnect. Generous vs the in-loop 5s FLOW-ack timeout to avoid a false
    // reconnect on a slow-but-alive cellular link.
    private static final long WAKE_LIVENESS_TIMEOUT_MS = 8_000L;
    private final Handler screenReconcileHandler = new Handler(Looper.getMainLooper());
    private final Runnable screenReconcileTask = new Runnable() {
        @Override
        public void run() {
            try {
                if (screenReconciler != null) {
                    screenReconciler.tick();
                }
            } finally {
                screenReconcileHandler.postDelayed(this, SCREEN_RECONCILE_INTERVAL_MS);
            }
        }
    };

    /** Protect a socket from the VPN tunnel when the service is running; no-op when the VPN is down. */
    public static boolean protectSocket(Socket socket) {
        MyVpnService current = instance;
        return current == null || current.protect(socket);
    }

    private static PoolFactory getPoolFactory() {
        if (poolFactory == null) {
            synchronized (MyVpnService.class) {
                if (poolFactory == null) {
                    poolFactory = PoolFactory.Builder.builder()
                            .setMinPoolSize(100)
                            .setMaxPoolSize(1000)
                            .setBlocking(true)
                            .setOutOfPoolTimeout(100)
                            .build();
                }
            }
        }
        return poolFactory;
    }

    /**
     * Run a task on the service executor without ever crashing the calling thread: a late callback
     * from a winding-down session (VpnClient.stop waits only 1s for its worker) can arrive after
     * onDestroy has shut the executor down, and a bare CompletableFuture.runAsync would then throw
     * RejectedExecutionException into a thread with no handler — killing the whole app.
     */
    private void runOnExecutor(Runnable task) {
        try {
            CompletableFuture.runAsync(task, executor);
        } catch (RejectedExecutionException e) {
            DebugLog.log("Executor rejected task (service already shutting down)");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashLogger.install();
        FileLogger.init(getApplicationContext(), 7);
        Log.d("MyVPN", "onCreate: entry, SDK=" + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + "), device=" + Build.MANUFACTURER + " " + Build.MODEL);

        try {
            stateManager = VpnStateManager.getInstance();
            trafficStats = TrafficStatsManager.getInstance();
        } catch (Exception e) {
            Log.e("MyVPN", "onCreate: singleton init failed", e);
        }

        try {
            notificationManager = new VpnNotificationManager(this, ACTION_DISCONNECT, MyVpnService.class);
            android.app.Notification notification = notificationManager.buildNotification(R.string.notif_starting);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d("MyVPN", "onCreate: startForeground with FOREGROUND_SERVICE_TYPE_SPECIAL_USE");
                startForeground(VpnNotificationManager.getNotificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                Log.d("MyVPN", "onCreate: startForeground (legacy, no type)");
                startForeground(VpnNotificationManager.getNotificationId(), notification);
            }
            Log.d("MyVPN", "onCreate: startForeground OK");
        } catch (Exception e) {
            Log.e("MyVPN", "onCreate: startForeground FAILED", e);
        }

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        screenReconciler = new ScreenStateReconciler(
                () -> powerManager != null && powerManager.isInteractive(),
                () -> isSleeping,
                this::syntheticResume);
        screenReconcileHandler.postDelayed(screenReconcileTask, SCREEN_RECONCILE_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            initializeDependencies();
        } catch (Exception e) {
            DebugLog.log("onStartCommand: initializeDependencies FAILED: " + Log.getStackTraceString(e));
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            DebugLog.log("onStartCommand: DISCONNECT action received");
            wasConnectedBeforeSleep = false;
            runOnExecutor(this::stopVpnSync);
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            DebugLog.log("onStartCommand: RECONNECT action received");
            reconnect();
            return START_STICKY;
        }

        try {
            screenStateHandler.register();
        } catch (Exception e) {
            DebugLog.log("onStartCommand: screenStateHandler.register FAILED: " + Log.getStackTraceString(e));
        }

        // Connect on the single-thread executor: it serializes behind any in-flight stopVpnSync
        // (fast off→on toggle) instead of racing it, and un-latches isStopping — that flag was never
        // reset, so a reconnect on a reused service instance had all state callbacks silently
        // swallowed (UI stuck, "have to toggle again").
        runOnExecutor(() -> {
            isStopping = false;
            startVpnConnection();
        });
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
                    settingsProvider.getExcludedApps(),
                    getPoolFactory(),
                    this::onVpnStateChanged,
                    this::reconnect
            );

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
                    runOnExecutor(this::stopVpnSync);
                }
                break;
            case ERROR:
                trafficStats.stop();
                updateState(state);
                runOnExecutor(this::stopVpnSync);
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

    /**
     * Synthesize a screen-on resume when {@link ScreenStateReconciler} detects a missed
     * {@code ACTION_SCREEN_ON} (screen really interactive but we still think we're sleeping). Doze can
     * delay or drop that broadcast after a long screen-off, leaving the downlink FLOW_CONTROL STOP'd on
     * the server → "connected but no internet". Called on the main thread (the reconcile timer posts to
     * the main looper), the same thread as the real screen broadcasts, so it never races them.
     */
    private void syntheticResume() {
        DebugLog.log("reconcileScreenState: screen interactive but state=SLEEPING (missed ACTION_SCREEN_ON) -> synthesizing onScreenOn");
        onScreenOn();
    }

    @Override
    public void onScreenOff() {
        synchronized (vpnLock) {
            boolean alive = vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive();
            DebugLog.log("onScreenOff: connectionAlive=" + alive
                    + (alive ? " -> suspending downlink + keepalive" : " -> nothing to suspend"));
            if (alive) {
                wasConnectedBeforeSleep = true;
                isSleeping = true;
                // Pause the whole server→client downlink while the screen is off (FLOW_CONTROL STOP on
                // every connection). MUST run off the main thread: onScreenOff() is a BroadcastReceiver
                // callback (main thread) and the sends take each session's output lock (held by a worker
                // during a network write) — doing it inline would block the main thread → ANR → the OS
                // kills the service. Offload to the executor.
                final VpnClientWrapper w = vpnClientWrapper;
                runOnExecutor(w::suspendDownlink);
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
        DebugLog.log("onScreenOn: wasConnectedBeforeSleep=" + wasConnectedBeforeSleep);

        if (wasConnectedBeforeSleep) {
            wasConnectedBeforeSleep = false;

            boolean connectionAlive;
            VpnClientWrapper wakeWrapper = null;
            synchronized (vpnLock) {
                connectionAlive = vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive();
                DebugLog.log("onScreenOn: connectionAlive=" + connectionAlive
                        + (connectionAlive ? " -> resuming keepalive + downlink" : " -> full reconnect"));
                if (connectionAlive) {
                    vpnClientWrapper.resumeKeepAlive();
                    // Resume the downlink on wake by re-applying the multipath flow policy (primary
                    // starts; the cellular standby stays paused while Wi-Fi is up). Off the main thread
                    // (onScreenOn is a BroadcastReceiver callback and the sends take each session's
                    // output lock). Harmless if a session was re-established meanwhile — the server's
                    // flow defaults to enabled on a fresh login and the policy re-asserts on LIVE.
                    final VpnClientWrapper w = vpnClientWrapper;
                    wakeWrapper = w;
                    runOnExecutor(w::resumeDownlink);
                }
            }

            if (connectionAlive) {
                if (trafficStats != null) {
                    trafficStats.start();
                }
                updateState(State.CONNECTED);
                // isConnectionAlive() above trusts cached LIVE + Socket.isConnected(), which cannot see
                // a peer that died while we slept (server restart is a 100% repro). Verify the resume
                // actually got answered; if the link is silent, force a clean reconnect.
                scheduleWakeLivenessVerify(wakeWrapper);
            } else {
                DebugLog.log("Connection lost during suspend, reconnecting");
                reconnect();
            }
        }
    }

    /**
     * After an optimistic wake-resume, verify the link actually answered. A live server replies to the
     * resume (FLOW ack / PONG) within a second or two; if no frame arrives within
     * {@link #WAKE_LIVENESS_TIMEOUT_MS}, the connection died while we slept (classic case: the server
     * restarted — {@code Socket.isConnected()} stays true, so {@code isConnectionAlive()} was fooled)
     * and we force a clean reconnect. Guarded on the captured wrapper so it no-ops if the connection was
     * already torn down/replaced meanwhile (keepalive or FLOW-ack watchdog beat us to it).
     */
    private void scheduleWakeLivenessVerify(final VpnClientWrapper wakeWrapper) {
        if (wakeWrapper == null) {
            return;
        }
        screenReconcileHandler.postDelayed(() -> {
            boolean linkDead;
            synchronized (vpnLock) {
                // reconnect iff this is still the connection we resumed AND its FLOW_CONTROL START is
                // still unacked (the in-loop / keepalive watchdog may have already replaced the wrapper).
                linkDead = VpnClientWrapper.shouldReconnectOnWake(
                        vpnClientWrapper == wakeWrapper, wakeWrapper.anyFlowAckPending());
            }
            if (linkDead) {
                DebugLog.log("onScreenOn wake-verify: FLOW_CONTROL resume unacked after "
                        + WAKE_LIVENESS_TIMEOUT_MS + "ms — link dead (server restart while asleep?), reconnecting");
                reconnect();
            } else {
                DebugLog.log("onScreenOn wake-verify: FLOW_CONTROL resume acked (or already reconnected), healthy");
            }
        }, WAKE_LIVENESS_TIMEOUT_MS);
    }

    // ==================== Connection Management ====================

    private void reconnect() {
        DebugLog.log("reconnect(): full teardown + restart");
        runOnExecutor(() -> {
            notificationManager.updateNotification(R.string.notif_reconnecting);
            stateManager.setState(State.CONNECTING);
            synchronized (vpnLock) {
                if (vpnClientWrapper != null) {
                    vpnClientWrapper.stop();
                    vpnClientWrapper = null;
                }
                isConnecting = false;
            }
            isStopping = false;
            startVpnConnection();
        });
    }

    private void stopVpnSync() {
        if (isStopping) return;
        isStopping = true;
        DebugLog.log("stopVpnSync: stopping VPN service");

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

    /**
     * The system revoked our VPN (another VPN app took over, or the user disabled it in settings).
     * Without this override the default implementation kills the service silently — the TUN stops
     * carrying traffic while the app may still render a connected state: exactly the reported
     * "green button but no internet" shape. Log it loudly and go through our own orderly shutdown.
     */
    @Override
    public void onRevoke() {
        DebugLog.log("onRevoke: VPN revoked by the system — stopping");
        wasConnectedBeforeSleep = false;
        runOnExecutor(this::stopVpnSync);
    }

    @Override
    public void onDestroy() {
        DebugLog.log("MyVpnService.onDestroy");
        screenReconcileHandler.removeCallbacks(screenReconcileTask);
        if (instance == this) {
            instance = null;
        }
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
