package su.grinev.myvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.function.Consumer;

import su.grinev.myvpn.databinding.ActivityMainBinding;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeDependencies();

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.connectButton.setOnClickListener(this::onConnectClicked);
        binding.settingsButton.setOnClickListener(v -> openSettings());
        binding.trafficButton.setOnClickListener(v -> openTraffic());
        binding.captionText.setText(getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME);

        DebugLog.printSplash(BuildConfig.VERSION_NAME);

        DebugLog.observe(text -> runOnUiThread(() -> {
            binding.logView.setText(text);
            binding.logScrollView.post(() -> binding.logScrollView.fullScroll(View.FOCUS_DOWN));
        }));

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

        Intent i = new Intent(this, MyVpnService.class);
        startForegroundService(i);
        DebugLog.log("VPN start");
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
            case CONNECTED, LOGIN, AWAITING_LOGIN_RESPONSE, LIVE:
                binding.connectButton.setText(R.string.btn_disconnect);
                break;
            case CONNECTING:
                binding.connectButton.setText(R.string.btn_connecting);
                break;
            case SLEEPING:
                binding.connectButton.setText(R.string.btn_sleeping);
                break;
            case WAITING:
                binding.connectButton.setText(R.string.btn_connecting);
                break;
            case DISCONNECTED:
            case ERROR:
            case SHUTDOWN:
                binding.connectButton.setText(R.string.btn_connect);
                break;
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
}
