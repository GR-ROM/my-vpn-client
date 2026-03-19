package su.grinev.myvpn.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import android.os.Build;

import androidx.core.app.NotificationCompat;

import su.grinev.myvpn.DebugLog;
import su.grinev.myvpn.MainActivity;
import su.grinev.myvpn.R;
import su.grinev.myvpn.State;

/**
 * Manages VPN service notifications.
 * Single Responsibility: Create and update notifications.
 * Open/Closed: State-to-notification mapping is centralized and easily extensible.
 */
public class VpnNotificationManager {
    private static final String CHANNEL_ID = "vpn";
    private static final int NOTIFICATION_ID = 1;

    private final Context context;
    private final NotificationManager notificationManager;
    private final String disconnectAction;
    private final Class<?> serviceClass;

    public VpnNotificationManager(Context context, String disconnectAction, Class<?> serviceClass) {
        this.context = context;
        this.disconnectAction = disconnectAction;
        this.serviceClass = serviceClass;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    /**
     * Get the notification ID used by this manager.
     */
    public static int getNotificationId() {
        return NOTIFICATION_ID;
    }

    /**
     * Build a notification with the given text resource ID.
     */
    public Notification buildNotification(int textResId) {
        Intent disconnect = new Intent(context, serviceClass);
        disconnect.setAction(disconnectAction);
        PendingIntent disconnectPi = PendingIntent.getService(
                context,
                0,
                disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent ui = new Intent(context, MainActivity.class);
        PendingIntent uiPi = PendingIntent.getActivity(
                context,
                0,
                ui,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn_key)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(textResId))
                .setContentIntent(uiPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_disconnect, context.getString(R.string.btn_disconnect), disconnectPi)
                .build();
    }

    /**
     * Update the notification with a new text.
     */
    public void updateNotification(int textResId) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(textResId));
        }
    }

    /**
     * Get the notification text resource ID for a given state.
     * Centralizes state-to-notification mapping (Open/Closed Principle).
     */
    public int getNotificationTextForState(State state) {
        return switch (state) {
            case CONNECTING -> R.string.notif_connecting;
            case CONNECTED, LOGIN, AWAITING_LOGIN_RESPONSE, LIVE -> R.string.notif_connected;
            case WAITING -> R.string.notif_reconnecting;
            case SLEEPING -> R.string.notif_sleeping;
            case ERROR -> R.string.notif_error;
            case DISCONNECTED, SHUTDOWN -> R.string.notif_disconnected;
        };
    }

    /**
     * Update notification based on state.
     */
    public void updateNotificationForState(State state) {
        updateNotification(getNotificationTextForState(state));
    }

    /**
     * Ensure the notification channel exists before starting the foreground service.
     * Safe to call multiple times — createNotificationChannel is idempotent.
     * Call this from the Activity BEFORE startForegroundService() to avoid
     * EMUI race conditions where the channel isn't registered in time.
     */
    public static void ensureChannelExists(Context context) {
        DebugLog.log("ensureChannelExists: SDK=" + Build.VERSION.SDK_INT);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "VPN",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
            DebugLog.log("ensureChannelExists: channel created/updated OK");
        } else {
            DebugLog.log("ensureChannelExists: WARNING NotificationManager is null!");
        }
    }

    private void createNotificationChannel() {
        ensureChannelExists(context);
    }
}
