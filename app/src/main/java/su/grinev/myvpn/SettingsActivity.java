package su.grinev.myvpn;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import su.grinev.myvpn.databinding.ActivitySettingsBinding;
import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;

/**
 * Settings activity for VPN configuration.
 * Single Responsibility: Handle settings UI and persistence.
 * Dependency Inversion: Uses SharedPreferencesSettingsProvider for storage.
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SharedPreferencesSettingsProvider settingsProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settingsProvider = new SharedPreferencesSettingsProvider(this);

        loadSettings();

        binding.saveButton.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        binding.serverIpEdit.setText(settingsProvider.getServerIp());
        binding.serverPortEdit.setText(String.valueOf(settingsProvider.getServerPort()));
        binding.jwtEdit.setText(settingsProvider.getJwt());
    }

    private void saveSettings() {
        String serverIp = binding.serverIpEdit.getText().toString().trim();
        String portStr = binding.serverPortEdit.getText().toString().trim();
        String jwt = binding.jwtEdit.getText().toString().trim();

        if (serverIp.isEmpty()) {
            binding.serverIpEdit.setError(getString(R.string.settings_error_required));
            return;
        }

        int serverPort;
        try {
            serverPort = Integer.parseInt(portStr);
            if (serverPort < 1 || serverPort > 65535) {
                binding.serverPortEdit.setError(getString(R.string.settings_error_port));
                return;
            }
        } catch (NumberFormatException e) {
            binding.serverPortEdit.setError(getString(R.string.settings_error_port));
            return;
        }

        settingsProvider.saveSettings(serverIp, serverPort, jwt);

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ==================== Static Accessors (for backward compatibility) ====================

    /**
     * @deprecated Use SharedPreferencesSettingsProvider.getServerIp() instead
     */
    @Deprecated
    public static String getServerIp(Context context) {
        return new SharedPreferencesSettingsProvider(context).getServerIp();
    }

    /**
     * @deprecated Use SharedPreferencesSettingsProvider.getServerPort() instead
     */
    @Deprecated
    public static int getServerPort(Context context) {
        return new SharedPreferencesSettingsProvider(context).getServerPort();
    }

    /**
     * @deprecated Use SharedPreferencesSettingsProvider.getJwt() instead
     */
    @Deprecated
    public static String getJwt(Context context) {
        return new SharedPreferencesSettingsProvider(context).getJwt();
    }
}
