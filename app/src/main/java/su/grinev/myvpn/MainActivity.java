package su.grinev.myvpn;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.function.Consumer;

import su.grinev.myvpn.databinding.ActivityMainBinding;
import su.grinev.myvpn.notification.VpnNotificationManager;
import su.grinev.myvpn.settings.SettingsProvider;
import su.grinev.myvpn.settings.SettingsValidator;
import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;
import su.grinev.myvpn.state.VpnStateManager;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private State currentState = State.DISCONNECTED;
    private SettingsProvider settingsProvider;
    private SettingsValidator settingsValidator;
    private final VpnStateManager stateManager = VpnStateManager.getInstance();
    private final Consumer<State> stateListener = this::updateUI;
    private ObjectAnimator pulseAnimator;
    private int currentBgResId = R.drawable.btn_disconnected;
    private final Handler logHandler = new Handler(Looper.getMainLooper());
    private volatile String pendingLogText;
    private boolean logUpdateScheduled = false;
    private static final long LOG_UPDATE_INTERVAL_MS = 250;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        initializeDependencies();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Apply bottom inset to content area so the log is not obscured by the nav bar.
        // Top inset is handled by CoordinatorLayout + AppBarLayout (fitsSystemWindows in XML).
        int contentPaddingBottom = binding.contentLayout.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentLayout, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    contentPaddingBottom + bars.bottom);
            return insets;
        });
        binding.connectButton.setOnClickListener(this::onConnectClicked);
        binding.settingsButton.setOnClickListener(v -> openSettings());
        binding.trafficButton.setOnClickListener(v -> openTraffic());
        binding.captionText.setText(getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME);

        DebugLog.printSplash(BuildConfig.VERSION_NAME);

        DebugLog.observe(text -> {
            pendingLogText = text;
            if (!logUpdateScheduled) {
                logUpdateScheduled = true;
                logHandler.postDelayed(() -> {
                    logUpdateScheduled = false;
                    String t = pendingLogText;
                    if (t != null) {
                        binding.logView.setText(t);
                        binding.logScrollView.post(() -> binding.logScrollView.fullScroll(View.FOCUS_DOWN));
                    }
                }, LOG_UPDATE_INTERVAL_MS);
            }
        });

        updateUI(State.DISCONNECTED);
    }

    private void initializeDependencies() {
        settingsProvider = new SharedPreferencesSettingsProvider(this);
        settingsValidator = new SettingsValidator(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        stateManager.observeState(stateListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI(stateManager.getState());
    }

    @Override
    protected void onStop() {
        stateManager.unobserveState(stateListener);
        super.onStop();
    }

    private void onConnectClicked(View v) {
        switch (currentState) {
            case DISCONNECTED:
            case ERROR:
                Intent prepare = VpnService.prepare(this);
                if (prepare != null) {
                    startActivityForResult(prepare, 1);
                } else {
                    startVpn();
                }
                break;
            case CONNECTING:
            case CONNECTED:
            default:
                Intent i = new Intent(this, MyVpnService.class);
                i.setAction(MyVpnService.ACTION_DISCONNECT);
                startService(i);
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void startVpn() {
        String validationError = settingsValidator.validate(settingsProvider);
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show();
            DebugLog.log("Validation failed: " + validationError);
            return;
        }

        DebugLog.log("startVpn: SDK=" + android.os.Build.VERSION.SDK_INT
                + " (" + android.os.Build.VERSION.RELEASE + ")");

        try {
            DebugLog.log("startVpn: ensuring notification channel exists");
            VpnNotificationManager.ensureChannelExists(this);
            DebugLog.log("startVpn: notification channel OK");
        } catch (Exception e) {
            DebugLog.log("startVpn: ensureChannelExists FAILED: " + android.util.Log.getStackTraceString(e));
        }

        try {
            Intent i = new Intent(this, MyVpnService.class);
            DebugLog.log("startVpn: calling startForegroundService");
            startForegroundService(i);
            DebugLog.log("startVpn: startForegroundService returned OK");
        } catch (Exception e) {
            DebugLog.log("startVpn: startForegroundService FAILED: " + e.getClass().getName()
                    + ": " + e.getMessage() + "\n" + android.util.Log.getStackTraceString(e));
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openTraffic() {
        startActivity(new Intent(this, TrafficActivity.class));
    }

    private void updateUI(State state) {
        currentState = state;
        switch (state) {
            case CONNECTED, LOGIN, AWAITING_LOGIN_RESPONSE, LIVE -> binding.connectButton.setText(R.string.btn_disconnect);
            case CONNECTING, WAITING -> binding.connectButton.setText(R.string.btn_connecting);
            case SLEEPING -> binding.connectButton.setText(R.string.btn_sleeping);
            case DISCONNECTED, ERROR, SHUTDOWN -> binding.connectButton.setText(R.string.btn_connect);
        }

        int bgResId = switch (state) {
            case CONNECTED, LIVE -> R.drawable.btn_connected;
            case CONNECTING, WAITING, LOGIN, AWAITING_LOGIN_RESPONSE -> R.drawable.btn_connecting;
            case ERROR -> R.drawable.btn_error;
            case DISCONNECTED, SHUTDOWN, SLEEPING -> R.drawable.btn_disconnected;
        };
        transitionBackground(bgResId);

        boolean animating = state == State.CONNECTING || state == State.WAITING
                || state == State.LOGIN || state == State.AWAITING_LOGIN_RESPONSE;
        if (animating) {
            startPulse();
        } else {
            stopPulse();
        }

        int statusResId = switch (state) {
            case CONNECTED, LOGIN, AWAITING_LOGIN_RESPONSE, LIVE -> R.string.status_connected;
            case CONNECTING -> R.string.status_connecting;
            case DISCONNECTED, SHUTDOWN -> R.string.status_disconnected;
            case ERROR -> R.string.status_error;
            case WAITING -> R.string.status_waiting;
            case SLEEPING -> R.string.status_sleeping;
        };
        binding.statusText.setText(statusResId);
    }

    private void transitionBackground(int newBgResId) {
        if (newBgResId == currentBgResId) return;
        Drawable oldBg = ContextCompat.getDrawable(this, currentBgResId);
        Drawable newBg = ContextCompat.getDrawable(this, newBgResId);
        TransitionDrawable transition = new TransitionDrawable(new Drawable[]{oldBg, newBg});
        transition.setCrossFadeEnabled(true);
        binding.connectButton.setBackground(transition);
        transition.startTransition(400);
        currentBgResId = newBgResId;
    }

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) return;
        pulseAnimator = ObjectAnimator.ofFloat(binding.connectButton, "alpha", 1f, 0.3f);
        pulseAnimator.setDuration(600);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        binding.connectButton.setAlpha(1f);
    }
}
