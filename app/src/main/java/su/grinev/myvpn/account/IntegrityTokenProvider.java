package su.grinev.myvpn.account;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityTokenResponse;

import java.util.concurrent.TimeUnit;

import su.grinev.myvpn.BuildConfig;

/**
 * Play Integrity attestation for device enroll.
 *
 * <p>Uses the classic (nonce-based) API deliberately: the server issues the nonce and verifies it
 * came back inside the token, which is what makes a captured token useless on replay. The standard
 * API carries a request hash instead and would not give us that.
 *
 * <p>Returns null whenever attestation is unavailable — no cloud project configured, no Play
 * Services, sideloaded build. The server decides what an absent token means (shadow mode lets it
 * through, enforcement rejects it); the client must not fail the whole sign-in over it.
 */
public class IntegrityTokenProvider {

    private static final String TAG = "IntegrityToken";
    private static final long TIMEOUT_SEC = 30;

    private final Context context;

    public IntegrityTokenProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    public String requestToken(String nonce) {
        if (nonce == null || nonce.isEmpty() || BuildConfig.INTEGRITY_CLOUD_PROJECT == 0L) {
            return null;
        }
        try {
            IntegrityManager manager = IntegrityManagerFactory.create(context);
            IntegrityTokenResponse response = Tasks.await(
                    manager.requestIntegrityToken(IntegrityTokenRequest.builder()
                            .setNonce(nonce)
                            .setCloudProjectNumber(BuildConfig.INTEGRITY_CLOUD_PROJECT)
                            .build()),
                    TIMEOUT_SEC, TimeUnit.SECONDS);
            return response.token();
        } catch (Exception e) {
            Log.w(TAG, "Integrity token unavailable: " + e.getMessage());
            return null;
        }
    }
}
