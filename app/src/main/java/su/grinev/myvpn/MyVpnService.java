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
import android.net.VpnService;
import android.os.Build;
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
    private String vpnServer = "178.253.22.137";
    private int port = 8443;
    private String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJqdGkiOiI4OSIsInN1YiI6IlZSIiwidG9rZW5fdHlwZSI6IkFDQ0VTUyIsImNsaWVudElkIjo4OSwiaWF0IjoxNzY4NzU1MDIwLCJleHAiOjE3NzEzNDcwMjB9.lt77sO0v7XUxsuDyW_YqKnnPOvyzJQq3OzP31-GCTHhRyOewA2gXjd5-ln0C0NoV4dAMDhF2iDBCz9A57HCgOR5juOHqrXrNIwO5CYccNb9TOw9jnfvyUNhdgdsBwaRl4rXfNxRRtVTbYKGekm1nq-_LNnpSE827p9mgNYF3x1d06Bli5-4vF5UcRNuLcsWjTYFSXVUFZS_TLtSF90rwGwbFiVP9SU30Zy6Gudrr862xaSVCbv-PeT2vo0PD4uK2AnHq-GFGRMCHSf2jLN8vUFH9FE-BgYfvqZg8CAmruSQDt3NxMlyqQeFouEZPWjAguCRqYlAq2K_FyRjIH2cRZg";
    private boolean wasConnectedBeforeSleep = false;
    private boolean isSleeping = false;

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

        DebugLog.log("VPN service started");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
}
