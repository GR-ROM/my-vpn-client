package su.grinev.myvpn.settings;

import android.content.Context;

import java.util.regex.Pattern;

import su.grinev.myvpn.R;

/**
 * Validates VPN settings.
 * Single Responsibility: Validate settings values.
 * Open/Closed: Validation rules can be extended by subclassing.
 */
public class SettingsValidator {
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final int JWT_PARTS_COUNT = 3;

    private final Context context;

    public SettingsValidator(Context context) {
        this.context = context;
    }

    /**
     * Validate settings from a SettingsProvider.
     *
     * @param settingsProvider The settings provider to validate
     * @return Error message if validation fails, null if settings are valid
     */
    public String validate(SettingsProvider settingsProvider) {
        return validate(
                settingsProvider.getServerIp(),
                settingsProvider.getServerPort(),
                settingsProvider.getJwt()
        );
    }

    /**
     * Validate individual settings values.
     *
     * @param serverIp   The server IP address
     * @param serverPort The server port
     * @param jwt        The JWT token
     * @return Error message if validation fails, null if settings are valid
     */
    public String validate(String serverIp, int serverPort, String jwt) {
        String ipError = validateIp(serverIp);
        if (ipError != null) return ipError;

        String portError = validatePort(serverPort);
        if (portError != null) return portError;

        String jwtError = validateJwt(jwt);
        if (jwtError != null) return jwtError;

        return null;
    }

    /**
     * Validate IP address.
     */
    public String validateIp(String serverIp) {
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return context.getString(R.string.validation_ip_empty);
        }

        if (!IP_PATTERN.matcher(serverIp.trim()).matches()) {
            return context.getString(R.string.validation_ip_invalid);
        }

        return null;
    }

    /**
     * Validate port number.
     */
    public String validatePort(int serverPort) {
        if (serverPort < MIN_PORT || serverPort > MAX_PORT) {
            return context.getString(R.string.validation_port_invalid);
        }
        return null;
    }

    /**
     * Validate JWT token.
     */
    public String validateJwt(String jwt) {
        if (jwt == null || jwt.trim().isEmpty()) {
            return context.getString(R.string.validation_jwt_empty);
        }

        String[] jwtParts = jwt.trim().split("\\.");
        if (jwtParts.length != JWT_PARTS_COUNT) {
            return context.getString(R.string.validation_jwt_invalid);
        }

        return null;
    }
}
