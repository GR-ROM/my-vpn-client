package su.grinev.myvpn.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SharedPreferencesSettingsProvider implements SettingsProvider {
    public static final String PREFS_NAME = "VpnSettings";
    public static final String KEY_SERVER_IP = "server_ip";
    public static final String KEY_SERVER_PORT = "server_port";
    public static final String KEY_JWT = "jwt";
    public static final String KEY_EXCLUDED_APPS = "excluded_apps";
    public static final String KEY_KEEP_TUNNEL_WHILE_ASLEEP = "keep_tunnel_while_asleep";
    public static final String DEFAULT_SERVER_IP = "87.106.204.29";
    public static final int DEFAULT_SERVER_PORT = 443;
    public static final String DEFAULT_JWT = "";
    // On by default: parking the tunnel on screen-off is what drops the sessions running through it —
    // the server evicts a connection whose PING goes unanswered for 30s, and every TCP flow inside the
    // tunnel dies with it. A user who wants the battery back turns this off in Settings.
    public static final boolean DEFAULT_KEEP_TUNNEL_WHILE_ASLEEP = true;

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

    @Override
    public Set<String> getExcludedApps() {
        return new HashSet<>(prefs.getStringSet(KEY_EXCLUDED_APPS, Collections.emptySet()));
    }

    @Override
    public boolean isKeepTunnelWhileAsleep() {
        return prefs.getBoolean(KEY_KEEP_TUNNEL_WHILE_ASLEEP, DEFAULT_KEEP_TUNNEL_WHILE_ASLEEP);
    }

    /** Persist the sleep policy; the running service re-reads it on every screen-off, so no reconnect. */
    public boolean saveKeepTunnelWhileAsleep(boolean keepAlive) {
        return prefs.edit().putBoolean(KEY_KEEP_TUNNEL_WHILE_ASLEEP, keepAlive).commit();
    }

    public boolean saveExcludedApps(Set<String> packages) {
        return prefs.edit().putStringSet(KEY_EXCLUDED_APPS, packages).commit();
    }

    /**
     * Stores the device JWT issued by billing, so the existing connect path keeps reading the
     * token from one place. Server IP/port stay untouched — they come from the node picker.
     */
    public boolean saveJwt(String jwt) {
        return prefs.edit().putString(KEY_JWT, jwt).commit();
    }

    /** Server selected from the node catalog after login. */
    public boolean saveServer(String serverIp, int serverPort) {
        return prefs.edit()
                .putString(KEY_SERVER_IP, serverIp)
                .putInt(KEY_SERVER_PORT, serverPort)
                .commit();
    }

    public boolean saveSettings(String serverIp, int serverPort, String jwt) {
        return prefs.edit()
                .putString(KEY_SERVER_IP, serverIp)
                .putInt(KEY_SERVER_PORT, serverPort)
                .putString(KEY_JWT, jwt)
                .commit();
    }

}
