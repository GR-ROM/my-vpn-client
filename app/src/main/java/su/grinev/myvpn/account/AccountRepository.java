package su.grinev.myvpn.account;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import su.grinev.myvpn.BuildConfig;

/**
 * Orchestrates the account lifecycle: Google sign-in → device enroll → token refresh → node
 * catalog. Owns the rule that the VPN never connects with a stale device JWT.
 *
 * <p>All methods block; call them off the main thread.
 */
public class AccountRepository {

    private static final String TAG = "AccountRepository";

    /**
     * Refresh this far before expiry. The node rejects an expired JWT at LOGIN, and a tunnel
     * that dies mid-session is worse than one extra HTTP call at connect time.
     */
    private static final long REFRESH_SKEW_SEC = 30 * 60;

    private final BillingApi api;
    private final AccountStore store;
    private final IntegrityTokenProvider integrityTokenProvider;

    public AccountRepository(Context context) {
        this(new BillingApi(BuildConfig.BILLING_BASE_URL),
                new AccountStore(context),
                new IntegrityTokenProvider(context));
    }

    /** Tests pass a null {@code integrityTokenProvider} to skip attestation. */
    public AccountRepository(BillingApi api, AccountStore store, IntegrityTokenProvider integrityTokenProvider) {
        this.api = api;
        this.store = store;
        this.integrityTokenProvider = integrityTokenProvider;
    }

    public AccountStore getStore() {
        return store;
    }

    /**
     * Full sign-in: verify the Google id_token, then enroll this device. First sign-in also
     * provisions the trial server-side, so enroll succeeds for a brand-new user.
     */
    public void signIn(String googleIdToken) throws AccountException {
        try {
            JSONObject auth = api.authGoogle(googleIdToken);
            String sessionToken = auth.getString("sessionToken");
            store.setSessionToken(sessionToken);
            store.setEmail(auth.optString("email", null));

            // Attestation is best-effort on the client: a null token still reaches the server,
            // which decides by its own policy whether to allow, shadow-log, or reject.
            String integrityToken = integrityTokenProvider == null
                    ? null
                    : integrityTokenProvider.requestToken(api.integrityNonce(sessionToken));

            JSONObject enrolled = api.enroll(sessionToken, store.getDeviceId(), deviceName(), integrityToken);
            saveCredentials(enrolled);
        } catch (BillingApi.ApiException e) {
            throw translate(e);
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.NETWORK, "Sign-in failed", e);
        }
    }

    /**
     * Ensures a usable device JWT, refreshing when it is close to expiry.
     *
     * <p>Returns the JWT to connect with. If the network is down but the current JWT is still
     * valid we keep it: an offline refresh must not block a tunnel that would still work.
     */
    public String ensureFreshJwt() throws AccountException {
        String jwt = store.getDeviceJwt();
        String refreshToken = store.getRefreshToken();
        if (refreshToken == null) {
            throw new AccountException(AccountException.Kind.SIGNED_OUT, "Not signed in", null);
        }
        if (jwt != null && !expiringSoon()) {
            return jwt;
        }
        try {
            JSONObject refreshed = api.refresh(store.getDeviceId(), refreshToken);
            saveCredentials(refreshed);
            return store.getDeviceJwt();
        } catch (BillingApi.ApiException e) {
            // A rejected refresh token is terminal: revoked device, reuse detected, or a dead
            // subscription. Wipe credentials so the UI lands on the login screen instead of
            // looping on a token the server will never accept again.
            if (e.isUnauthorized()) {
                store.signOut();
                throw new AccountException(AccountException.Kind.SIGNED_OUT, "Session expired", e);
            }
            throw translate(e);
        } catch (Exception e) {
            if (jwt != null && !expired()) {
                Log.w(TAG, "Refresh failed, using still-valid JWT: " + e.getMessage());
                return jwt;
            }
            throw new AccountException(AccountException.Kind.NETWORK, "Token refresh failed", e);
        }
    }

    /** Live nodes grouped by location, for the server picker. */
    public List<Location> locations() throws AccountException {
        try {
            JSONArray arr = api.nodes(requireSession());
            List<Location> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject loc = arr.getJSONObject(i);
                JSONArray nodesJson = loc.getJSONArray("nodes");
                List<Node> nodes = new ArrayList<>(nodesJson.length());
                for (int j = 0; j < nodesJson.length(); j++) {
                    JSONObject n = nodesJson.getJSONObject(j);
                    nodes.add(new Node(n.getString("host"), n.getInt("port"), n.optString("name", "")));
                }
                out.add(new Location(loc.getString("location"), loc.optString("countryCode", ""), nodes));
            }
            return out;
        } catch (BillingApi.ApiException e) {
            throw translate(e);
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.NETWORK, "Failed to load servers", e);
        }
    }

    /**
     * Verifies a Play purchase and immediately refreshes the device JWT.
     *
     * <p>The refresh is not optional: the node shapes by the {@code role}/plan baked into the
     * token, so without it the user has paid and is still throttled at the old tier.
     */
    public void verifyPurchase(String purchaseToken, String productId) throws AccountException {
        try {
            api.verifyPurchase(requireSession(), purchaseToken, productId);
            JSONObject refreshed = api.refresh(store.getDeviceId(), store.getRefreshToken());
            saveCredentials(refreshed);
        } catch (BillingApi.ApiException e) {
            throw translate(e);
        } catch (Exception e) {
            throw new AccountException(AccountException.Kind.NETWORK, "Purchase verification failed", e);
        }
    }

    public void signOut() {
        store.signOut();
    }

    private String requireSession() throws AccountException {
        String session = store.getSessionToken();
        if (session == null) {
            throw new AccountException(AccountException.Kind.SIGNED_OUT, "Not signed in", null);
        }
        return session;
    }

    private void saveCredentials(JSONObject response) throws Exception {
        store.saveCredentials(
                response.getString("deviceJwt"),
                response.getString("refreshToken"),
                parseExpiry(response.optString("expiresAt", null)),
                response.optString("plan", ""),
                response.optInt("speedMbps", 0),
                response.optInt("maxDevices", 0));
    }

    /** Billing serialises {@code expiresAt} as an ISO-8601 instant. */
    private static long parseExpiry(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) {
            return 0L;
        }
        try {
            return Instant.parse(iso).getEpochSecond();
        } catch (Exception e) {
            Log.w(TAG, "Unparseable expiresAt: " + iso);
            return 0L;
        }
    }

    private boolean expiringSoon() {
        long exp = store.getJwtExpiresAt();
        // Unknown expiry — refresh rather than gamble on a token the node may reject.
        return exp == 0L || Instant.now().getEpochSecond() + REFRESH_SKEW_SEC >= exp;
    }

    private boolean expired() {
        long exp = store.getJwtExpiresAt();
        return exp == 0L || Instant.now().getEpochSecond() >= exp;
    }

    private static String deviceName() {
        String name = Build.MANUFACTURER + " " + Build.MODEL;
        return name.length() > 128 ? name.substring(0, 128) : name;
    }

    private static AccountException translate(BillingApi.ApiException e) {
        if (e.isUnauthorized()) {
            return new AccountException(AccountException.Kind.SIGNED_OUT, "Session expired", e);
        }
        if (e.isNoSubscription()) {
            return new AccountException(AccountException.Kind.NO_SUBSCRIPTION, "Subscription expired", e);
        }
        if (e.isQuotaExceeded()) {
            return new AccountException(AccountException.Kind.DEVICE_QUOTA, "Device limit reached", e);
        }
        return new AccountException(AccountException.Kind.SERVER, e.getMessage(), e);
    }

    /** Failure the UI must react to differently — each kind maps to its own screen. */
    public static class AccountException extends Exception {

        public enum Kind {
            /** Re-login required. */
            SIGNED_OUT,
            /** Trial or subscription is over — offer the paywall. */
            NO_SUBSCRIPTION,
            /** Quota full — offer the device list so the user can free a slot. */
            DEVICE_QUOTA,
            NETWORK,
            SERVER
        }

        private final Kind kind;

        public AccountException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind getKind() {
            return kind;
        }
    }

    public record Location(String location, String countryCode, List<Node> nodes) {}

    public record Node(String host, int port, String name) {}
}
