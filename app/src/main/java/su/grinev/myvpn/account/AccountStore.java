package su.grinev.myvpn.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.UUID;

/**
 * Persistent account state: the device credentials plus the picked server.
 *
 * <p>Backed by {@link EncryptedSharedPreferences} — the refresh token is a long-lived bearer
 * credential, and the device JWT authenticates against the VPN node. On devices where the
 * keystore-backed store cannot be opened (rare OEM breakage, corrupted keyset) we fall back to
 * plain prefs rather than bricking sign-in; the tokens stay app-private either way.
 */
public class AccountStore {

    private static final String TAG = "AccountStore";
    private static final String PREFS_NAME = "VpnAccount";
    private static final String PREFS_NAME_FALLBACK = "VpnAccountPlain";

    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SESSION_TOKEN = "session_token";
    private static final String KEY_DEVICE_JWT = "device_jwt";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_JWT_EXPIRES_AT = "jwt_expires_at";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_PLAN = "plan";
    private static final String KEY_SPEED_MBPS = "speed_mbps";
    private static final String KEY_MAX_DEVICES = "max_devices";
    private static final String KEY_LOCATION = "location";

    private final SharedPreferences prefs;

    public AccountStore(Context context) {
        this.prefs = openPrefs(context.getApplicationContext());
    }

    private static SharedPreferences openPrefs(Context context) {
        try {
            MasterKey key = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            Log.e(TAG, "Encrypted store unavailable, falling back to plain prefs", e);
            return context.getSharedPreferences(PREFS_NAME_FALLBACK, Context.MODE_PRIVATE);
        }
    }

    /**
     * Stable per-install device identity, generated once. Not ANDROID_ID: that one is scoped to
     * the Google account and survives reinstalls unevenly, which would make the same phone look
     * like a different device — or two phones look like one — against the subscription quota.
     */
    public synchronized String getDeviceId() {
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    public String getSessionToken() {
        return prefs.getString(KEY_SESSION_TOKEN, null);
    }

    public void setSessionToken(String token) {
        prefs.edit().putString(KEY_SESSION_TOKEN, token).apply();
    }

    public String getDeviceJwt() {
        return prefs.getString(KEY_DEVICE_JWT, null);
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }

    /** Epoch seconds; 0 when unknown. */
    public long getJwtExpiresAt() {
        return prefs.getLong(KEY_JWT_EXPIRES_AT, 0L);
    }

    public String getEmail() {
        return prefs.getString(KEY_EMAIL, null);
    }

    public void setEmail(String email) {
        prefs.edit().putString(KEY_EMAIL, email).apply();
    }

    public String getPlan() {
        return prefs.getString(KEY_PLAN, null);
    }

    public int getSpeedMbps() {
        return prefs.getInt(KEY_SPEED_MBPS, 0);
    }

    public int getMaxDevices() {
        return prefs.getInt(KEY_MAX_DEVICES, 0);
    }

    public String getLocation() {
        return prefs.getString(KEY_LOCATION, null);
    }

    public void setLocation(String location) {
        prefs.edit().putString(KEY_LOCATION, location).apply();
    }

    /** Applies an enroll/refresh result atomically — a half-written credential pair is unusable. */
    public void saveCredentials(String deviceJwt, String refreshToken, long expiresAtEpochSec,
                                String plan, int speedMbps, int maxDevices) {
        prefs.edit()
                .putString(KEY_DEVICE_JWT, deviceJwt)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_JWT_EXPIRES_AT, expiresAtEpochSec)
                .putString(KEY_PLAN, plan)
                .putInt(KEY_SPEED_MBPS, speedMbps)
                .putInt(KEY_MAX_DEVICES, maxDevices)
                .apply();
    }

    public boolean isSignedIn() {
        return getRefreshToken() != null && getDeviceJwt() != null;
    }

    /** Drops credentials but keeps the device id, so re-login reuses the same quota slot. */
    public void signOut() {
        prefs.edit()
                .remove(KEY_SESSION_TOKEN)
                .remove(KEY_DEVICE_JWT)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_JWT_EXPIRES_AT)
                .remove(KEY_EMAIL)
                .remove(KEY_PLAN)
                .remove(KEY_SPEED_MBPS)
                .remove(KEY_MAX_DEVICES)
                .apply();
    }
}
