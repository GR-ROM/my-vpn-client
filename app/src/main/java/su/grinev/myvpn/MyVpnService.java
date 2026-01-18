package su.grinev.myvpn;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
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
    private String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJqdGkiOiIxMTEiLCJzdWIiOiJWcG5DbGllbnQiLCJ0b2tlbl90eXBlIjoiQUNDRVNTIiwiY2xpZW50SWQiOjExMSwiaWF0IjoxNzY4MzI1OTc2LCJleHAiOjE3NzA5MTc5NzZ9.yTXfmzurInvDtrFn2oSFH9ciG5EzJ0uheimkuTp7Ffi3K8EKcVVPHmUy2jQcHex_MqQsituFsb7UQoOC7PVbfTAhFvsFXHj5URIa7J9JxsP7PE_1Q7M4cu_7nGW-VnyUKtNWN2adGMlDui2_jUsN9vSeIfR-lnu8rgz338Byy2jkFtWIjPrenwkcCY_xWuYu-AX2KDKCmK5KZo_qF82eb2Jcxj4yV6wgFBPtgUZhaFZIDTRZgLb6T9qRY-JWancydGmjHZqturpVj-lkynEonlS1jJ9kOysCQtnIgBzc4IONMyntqPRF3GKvoxzmszykbIlL-fYvjxF8oVqSqe94OQ";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"));

        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        DebugLog.log("VPN service started");

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
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    private void onChangeState(State state) {
        sendState(state);

        switch (state) {
            case CONNECTING:
                updateNotification("Connecting…");
                break;

            case CONNECTED:
                updateNotification("Connected");
                break;

            case DISCONNECTED:
                updateNotification("Disconnected");
                stopSelf();
                break;

            case ERROR:
                updateNotification("Error");
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }

            stopSelf();
        }).start();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
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
                .setContentTitle("MyVPN")
                .setContentText(text)
                .setContentIntent(uiPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_disconnect, "Disconnect", disconnectPi)
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
}
