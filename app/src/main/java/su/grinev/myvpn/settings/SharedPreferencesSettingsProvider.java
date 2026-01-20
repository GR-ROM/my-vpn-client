package su.grinev.myvpn.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SharedPreferences-based implementation of SettingsProvider.
 * Single Responsibility: Read settings from SharedPreferences.
 */
public class SharedPreferencesSettingsProvider implements SettingsProvider {
    public static final String PREFS_NAME = "VpnSettings";
    public static final String KEY_SERVER_IP = "server_ip";
    public static final String KEY_SERVER_PORT = "server_port";
    public static final String KEY_JWT = "jwt";

    public static final String DEFAULT_SERVER_IP = "178.253.22.137";
    public static final int DEFAULT_SERVER_PORT = 8443;
    public static final String DEFAULT_JWT = "";

    private final SharedPreferences prefs;

    public SharedPreferencesSettingsProvider(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public String getServerIp() {
        return prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);
    }

    @Override
    public int getServerPort() {
        return prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    @Override
    public String getJwt() {
        return prefs.getString(KEY_JWT, DEFAULT_JWT);
    }

    /**
     * Save settings to SharedPreferences.
     * Returns true if save was successful.
     */
    public boolean saveSettings(String serverIp, int serverPort, String jwt) {
        return prefs.edit()
                .putString(KEY_SERVER_IP, serverIp)
                .putInt(KEY_SERVER_PORT, serverPort)
                .putString(KEY_JWT, jwt)
                .commit();
    }

}
