package su.grinev.myvpn.handlers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import su.grinev.myvpn.DebugLog;

/**
 * Handles screen state changes (on/off).
 * Single Responsibility: Monitor and respond to screen state changes.
 */
public class ScreenStateHandler {

    /**
     * Callback interface for screen state changes.
     */
    public interface ScreenStateCallback {
        void onScreenOff();
        void onScreenOn();
    }

    private final Context context;
    private final ScreenStateCallback callback;
    private boolean isRegistered = false;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                DebugLog.log("Screen off detected");
                callback.onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                DebugLog.log("Screen on detected");
                callback.onScreenOn();
            }
        }
    };

    public ScreenStateHandler(Context context, ScreenStateCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * Register the screen state receiver.
     * Safe to call multiple times - will only register once.
     */
    public void register() {
        if (!isRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }
            isRegistered = true;
        }
    }

    /**
     * Unregister the screen state receiver.
     * Safe to call multiple times.
     */
    public void unregister() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(screenReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was not registered
            }
            isRegistered = false;
        }
    }

    /**
     * Check if the receiver is currently registered.
     */
    public boolean isRegistered() {
        return isRegistered;
    }
}
