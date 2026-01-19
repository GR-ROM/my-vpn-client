package su.grinev.myvpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.regex.Pattern;

import su.grinev.myvpn.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private State currentState = State.DISCONNECTED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.connectButton.setOnClickListener(this::onConnectClicked);
        binding.settingsButton.setOnClickListener(v -> openSettings());
        binding.captionText.setText(getString(R.string.app_name) + " v" + BuildConfig.VERSION_NAME);

        DebugLog.printSplash(BuildConfig.VERSION_NAME);

        DebugLog.observe(text -> runOnUiThread(() -> {
            binding.logView.setText(text);
            binding.logScrollView.post(() -> binding.logScrollView.fullScroll(View.FOCUS_DOWN));
        }));

        updateUI(State.DISCONNECTED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        MyVpnService.observeState(state -> runOnUiThread(() -> updateUI(state)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI(MyVpnService.getCurrentState());
    }

    @Override
    protected void onStop() {
        MyVpnService.unobserveState();
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
        String validationError = validateSettings();
        if (validationError != null) {
            Toast.makeText(this, validationError, Toast.LENGTH_LONG).show();
            DebugLog.log("Validation failed: " + validationError);
            return;
        }

        Intent i = new Intent(this, MyVpnService.class);
        startForegroundService(i);
        DebugLog.log("VPN start");
    }

    private String validateSettings() {
        String serverIp = SettingsActivity.getServerIp(this);
        int serverPort = SettingsActivity.getServerPort(this);
        String jwt = SettingsActivity.getJwt(this);

        // Validate IP address
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return getString(R.string.validation_ip_empty);
        }

        Pattern ipPattern = Pattern.compile(
                "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        );
        if (!ipPattern.matcher(serverIp.trim()).matches()) {
            return getString(R.string.validation_ip_invalid);
        }

        // Validate port
        if (serverPort < 1 || serverPort > 65535) {
            return getString(R.string.validation_port_invalid);
        }

        // Validate JWT
        if (jwt == null || jwt.trim().isEmpty()) {
            return getString(R.string.validation_jwt_empty);
        }

        // Basic JWT format check (header.payload.signature)
        String[] jwtParts = jwt.trim().split("\\.");
        if (jwtParts.length != 3) {
            return getString(R.string.validation_jwt_invalid);
        }

        return null;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void updateUI(State state) {
        currentState = state;

        switch (state) {
            case CONNECTED:
                binding.connectButton.setText(R.string.btn_disconnect);
                break;
            case CONNECTING:
                binding.connectButton.setText(R.string.btn_connecting);
                break;
            case SLEEPING:
                binding.connectButton.setText(R.string.btn_sleeping);
                break;
            case DISCONNECTED:
            case ERROR:
                binding.connectButton.setText(R.string.btn_connect);
                break;
        }

        int statusResId = switch (state) {
            case CONNECTED -> R.string.status_connected;
            case CONNECTING -> R.string.status_connecting;
            case DISCONNECTED -> R.string.status_disconnected;
            case ERROR -> R.string.status_error;
            case WAITING -> R.string.status_waiting;
            case SLEEPING -> R.string.status_sleeping;
            default -> R.string.status_unknown;
        };
        binding.statusText.setText(statusResId);
    }

}