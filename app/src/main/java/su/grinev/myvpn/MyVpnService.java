package su.grinev.myvpn;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

@SuppressLint("VpnServicePolicy")
public class MyVpnService extends VpnService {
    private static final String CHANNEL_ID = "vpn";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_DISCONNECT = "su.grinev.myvpn.DISCONNECT";
    public static final String ACTION_STATE = "su.grinev.myvpn.STATE";
    public static final String EXTRA_STATE = "state";
    private VpnClientWrapper vpnClientWrapper;
    private String vpnServer;
    private int port;
    private String jwt;
    private boolean wasConnectedBeforeSleep = false;
    private boolean isSleeping = false;
    private ConnectivityManager connectivityManager;
    private Network currentNetwork;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            DebugLog.log("Network available: " + network);
            if (currentNetwork != null && !currentNetwork.equals(network)) {
                DebugLog.log("Network changed, reconnecting");
                handleNetworkChange();
            }
            currentNetwork = network;
        }

        @Override
        public void onLost(Network network) {
            DebugLog.log("Network lost: " + network);
            if (network.equals(currentNetwork)) {
                currentNetwork = null;
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                handleScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                handleScreenOn();
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification(R.string.notif_starting));

        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            wasConnectedBeforeSleep = false;
            stopVpn();
            return START_NOT_STICKY;
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, filter);

        // Register network change listener
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);

        // Load settings
        vpnServer = SettingsActivity.getServerIp(this);
        port = SettingsActivity.getServerPort(this);
        jwt = SettingsActivity.getJwt(this);

        DebugLog.log("VPN service started");
        DebugLog.log("Server: " + vpnServer + ":" + port);
        startVpnConnection();

        return START_STICKY;
    }

    private void startVpnConnection() {
        new Thread(() -> {
            try {
                sendState(State.CONNECTING);
                TunAndroid tunAndroid = new TunAndroid(this);

                vpnClientWrapper = new VpnClientWrapper(
                        tunAndroid,
                        vpnServer,
                        port,
                        jwt,
                        true,
                        this::onChangeState
                );

            } catch (Exception e) {
                DebugLog.log("VPN error: " + Log.getStackTraceString(e));
                onChangeState(State.ERROR);
            }
        }, "VpnConnectionThread").start();
    }

    private void onChangeState(State state) {
        sendState(state);

        switch (state) {
            case CONNECTING:
                updateNotification(R.string.notif_connecting);
                break;

            case CONNECTED:
                updateNotification(R.string.notif_connected);
                break;

            case DISCONNECTED:
                updateNotification(R.string.notif_disconnected);
                if (!isSleeping) {
                    stopSelf();
                }
                break;

            case SLEEPING:
                updateNotification(R.string.notif_sleeping);
                break;

            case ERROR:
                updateNotification(R.string.notif_error);
                stopSelf();
                break;
        }
    }

    private void sendState(State state) {
        Intent i = new Intent(ACTION_STATE);
        i.putExtra(EXTRA_STATE, state.name());
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    private void stopVpn() {
        new Thread(() -> {
            if (vpnClientWrapper != null) {
                vpnClientWrapper.stop();
                vpnClientWrapper = null;
            }

            onChangeState(State.DISCONNECTED);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }).start();
    }

    private void updateNotification(int textResId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(textResId));
        }
    }

    private Notification buildNotification(int textResId) {
        createNotificationChannel();

        Intent disconnect = new Intent(this, MyVpnService.class);
        disconnect.setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPi = PendingIntent.getService(
                this,
                0,
                disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent ui = new Intent(this, MainActivity.class);
        PendingIntent uiPi = PendingIntent.getActivity(
                this,
                0,
                ui,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(textResId))
                .setContentIntent(uiPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_disconnect, getString(R.string.btn_disconnect), disconnectPi)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void handleNetworkChange() {
        if (vpnClientWrapper != null) {
            updateNotification(R.string.notif_reconnecting);
            sendState(State.CONNECTING);

            new Thread(() -> {
                if (vpnClientWrapper != null) {
                    vpnClientWrapper.stop();
                    vpnClientWrapper = null;
                }
                startVpnConnection();
            }).start();
        }
    }

    private void handleScreenOff() {
        DebugLog.log("Screen off detected");
        if (vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive()) {
            wasConnectedBeforeSleep = true;
            isSleeping = true;
            sendState(State.SLEEPING);
            updateNotification(R.string.notif_sleeping);
            // Keep connection alive - don't stop VPN
        }
    }

    private void handleScreenOn() {
        DebugLog.log("Screen on detected");
        isSleeping = false;

        if (wasConnectedBeforeSleep) {
            wasConnectedBeforeSleep = false;

            // Check if connection is still alive after device suspend
            if (vpnClientWrapper != null && vpnClientWrapper.isConnectionAlive()) {
                DebugLog.log("Connection still alive after wake");
                sendState(State.CONNECTED);
                updateNotification(R.string.notif_connected);
            } else {
                // Connection was lost during suspend, need to reconnect
                DebugLog.log("Connection lost during suspend, reconnecting");
                if (vpnClientWrapper != null) {
                    vpnClientWrapper.stop();
                    vpnClientWrapper = null;
                }
                updateNotification(R.string.notif_reconnecting);
                startVpnConnection();
            }
        }
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }
        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        DebugLog.log("App swiped away, stopping VPN service");
        wasConnectedBeforeSleep = false;

        if (vpnClientWrapper != null) {
            vpnClientWrapper.stop();
            vpnClientWrapper = null;
        }

        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered
        }

        if (connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }

        stopForeground(STOP_FOREGROUND_REMOVE);

        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
}
