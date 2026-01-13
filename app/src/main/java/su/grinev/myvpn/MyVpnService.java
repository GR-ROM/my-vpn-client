package su.grinev.myvpn;

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


public class MyVpnService extends VpnService {
    private static final String CHANNEL_ID = "vpn";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_DISCONNECT = "su.grinev.myvpn.DISCONNECT";
    public static final String ACTION_STATE      = "su.grinev.myvpn.STATE";
    public static final String EXTRA_STATE       = "state";

    private VpnClientWrapper vpnClientWrapper;
    private String vpnServer = "178.253.22.137";
    private int port = 8443;
    private String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJqdGkiOiIxMjMiLCJzdWIiOiJWcG5DbGllbnQiLCJ0b2tlbl90eXBlIjoiQUNDRVNTIiwiY2xpZW50SWQiOjEyMywiaWF0IjoxNzY4Mjc4NTIxLCJleHAiOjE3NzA4NzA1MjF9.KB05K0CgEpeXcrjiI6-oWwsZMvTkmjFDw5Z6qJkBNbCIkdGs_7e4G2X3zPkpt3qyVCm_KtTIst6VSOJtIholrFTHbRI8qElPhsSA2hAg5XxkX5HVxITBvBMGIVDDgScULAe8UP6ZY1mRYydtOyFSGR6_0FIUoaC6Tzvg8yCX44ECD4tQjmpMW9r_dfcucN8CrffElsWmftA2OOVn0BMjDnKRQMowxXJCgExWPvJbD9N2z2Lo9tXswzy7wr4ZqsFWGXieKWFtda3wGMUwYs_kdD_uSrwdvO9oKv9MgVOGFv9-loaH_9t3kOO9cwdHNsSbgfLP_-Ya_qPW1J_RzTWaIA";

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
