package su.grinev.myvpn.account;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Thin REST client for the billing service. Deliberately built on {@link HttpsURLConnection} +
 * {@code org.json} — five endpoints do not justify pulling a networking stack into an app whose
 * whole point is a lean data path.
 *
 * <p>Every call blocks; callers run them off the main thread.
 */
public class BillingApi {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final String baseUrl;

    public BillingApi(String baseUrl) {
        // Trailing slash would double up against the leading slash of every path.
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Google id_token → user session. Public endpoint. */
    public JSONObject authGoogle(String idToken) throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject().put("idToken", idToken);
        return new JSONObject(request("POST", "/v1/auth/google", null, body.toString()));
    }

    /**
     * Server-issued nonce for the Play Integrity token. Returns null when the server has
     * attestation switched off, which tells the client to skip it.
     */
    public String integrityNonce(String sessionToken) throws IOException, ApiException, JSONException {
        JSONObject resp = new JSONObject(request("GET", "/v1/devices/integrity-nonce", sessionToken, null));
        return resp.isNull("nonce") ? null : resp.getString("nonce");
    }

    /** Registers this device under the session's user and returns the device JWT + refresh token. */
    public JSONObject enroll(String sessionToken, String deviceId, String deviceName, String integrityToken)
            throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject()
                .put("deviceId", deviceId)
                .put("deviceName", deviceName)
                .put("platform", "ANDROID");
        return new JSONObject(
                request("POST", "/v1/devices/enroll", sessionToken, body.toString(), integrityToken));
    }

    /** Exchanges the refresh token for a fresh device JWT. Public endpoint — the token is the credential. */
    public JSONObject refresh(String deviceId, String refreshToken)
            throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject()
                .put("deviceId", deviceId)
                .put("refreshToken", refreshToken);
        return new JSONObject(request("POST", "/v1/devices/refresh", null, body.toString()));
    }

    /** Live nodes grouped by location. */
    public JSONArray nodes(String sessionToken) throws IOException, ApiException, JSONException {
        return new JSONArray(request("GET", "/v1/nodes", sessionToken, null));
    }

    /** Current subscription + quota. */
    public JSONObject subscription(String sessionToken) throws IOException, ApiException, JSONException {
        return new JSONObject(request("GET", "/v1/subscription", sessionToken, null));
    }

    /** Verifies a Play purchase; the server also acknowledges it. */
    public JSONObject verifyPurchase(String sessionToken, String purchaseToken, String productId)
            throws IOException, ApiException, JSONException {
        JSONObject body = new JSONObject()
                .put("purchaseToken", purchaseToken)
                .put("productId", productId);
        return new JSONObject(request("POST", "/v1/billing/play/verify", sessionToken, body.toString()));
    }

    private String request(String method, String path, String bearer, String body)
            throws IOException, ApiException {
        return request(method, path, bearer, body, null);
    }

    private String request(String method, String path, String bearer, String body, String integrityToken)
            throws IOException, ApiException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (integrityToken != null) {
                conn.setRequestProperty("X-Integrity-Token", integrityToken);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                return readAll(conn.getInputStream());
            }
            throw new ApiException(status, readAll(conn.getErrorStream()));
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = stream.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Non-2xx response. The status drives recovery: 401 → re-login, 404 → no subscription, 409 → quota. */
    public static class ApiException extends Exception {

        private final int status;
        private final String body;

        public ApiException(int status, String body) {
            super("HTTP " + status + (body == null || body.isEmpty() ? "" : ": " + body));
            this.status = status;
            this.body = body;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }

        public boolean isUnauthorized() {
            return status == 401;
        }

        /** Billing maps "no active subscription" to 404 and the device-quota breach to 409. */
        public boolean isNoSubscription() {
            return status == 404;
        }

        public boolean isQuotaExceeded() {
            return status == 409;
        }
    }
}
