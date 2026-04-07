package su.grinev.myvpn;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

import su.grinev.myvpn.databinding.ActivitySettingsBinding;
import su.grinev.myvpn.settings.SettingsValidator;
import su.grinev.myvpn.settings.SharedPreferencesSettingsProvider;

/**
 * Settings activity for VPN configuration.
 * Single Responsibility: Handle settings UI and persistence.
 * Dependency Inversion: Uses SharedPreferencesSettingsProvider for storage.
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SharedPreferencesSettingsProvider settingsProvider;

    private final ActivityResultLauncher<ScanOptions> qrScanLauncher = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() == null) return;
                String scanned = result.getContents().trim();
                SettingsValidator validator = new SettingsValidator(this);
                String error = validator.validateJwt(scanned);
                if (error != null) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    return;
                }
                binding.jwtEdit.setText(scanned);
                updateJwtExpiration(scanned);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        settingsProvider = new SharedPreferencesSettingsProvider(this);

        binding.jwtEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateJwtExpiration(s.toString().trim());
            }
        });

        loadSettings();

        binding.saveButton.setOnClickListener(v -> saveSettings());
        binding.excludedAppsButton.setOnClickListener(v ->
                startActivity(new Intent(this, ExcludedAppsActivity.class)));
        binding.scanQrButton.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt(getString(R.string.settings_scan_qr));
            options.setBeepEnabled(false);
            options.setOrientationLocked(true);
            qrScanLauncher.launch(options);
        });
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

    private void updateJwtExpiration(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            binding.jwtExpirationLabel.setText("");
            return;
        }
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            binding.jwtExpirationLabel.setText("");
            return;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JSONObject json = new JSONObject(payload);
            long exp = json.getLong("exp");
            Date expDate = new Date(exp * 1000);
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            boolean expired = expDate.before(new Date());
            if (expired) {
                binding.jwtExpirationLabel.setText(getString(R.string.settings_jwt_expired, sdf.format(expDate)));
                binding.jwtExpirationLabel.setTextColor(Color.RED);
            } else {
                binding.jwtExpirationLabel.setText(getString(R.string.settings_jwt_expires, sdf.format(expDate)));
                binding.jwtExpirationLabel.setTextColor(0xFF4CAF50);
            }
        } catch (Exception e) {
            binding.jwtExpirationLabel.setText(getString(R.string.settings_jwt_expiration_unknown));
            binding.jwtExpirationLabel.setTextColor(Color.GRAY);
        }
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
