package su.grinev.myvpn.account;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import su.grinev.myvpn.BuildConfig;
import su.grinev.myvpn.MainActivity;
import su.grinev.myvpn.R;
import su.grinev.myvpn.databinding.ActivityLoginBinding;

/**
 * Google sign-in gate. First sign-in doubles as registration: billing creates the user and
 * grants the trial, then we enroll this device and get the JWT the VPN node authenticates.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String TAG = "LoginActivity";

    private ActivityLoginBinding binding;
    private CredentialManager credentialManager;
    private AccountRepository accountRepository;
    private ExecutorService executor;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        credentialManager = CredentialManager.create(this);
        accountRepository = new AccountRepository(this);
        executor = Executors.newSingleThreadExecutor();

        binding.signInButton.setOnClickListener(v -> startGoogleSignIn());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void startGoogleSignIn() {
        if (BuildConfig.GOOGLE_SERVER_CLIENT_ID.isEmpty()) {
            // Misconfigured build — fail loudly here rather than with an opaque Google error.
            showError(getString(R.string.login_error_not_configured));
            return;
        }
        setBusy(true);

        GetSignInWithGoogleOption option =
                new GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        credentialManager.getCredentialAsync(
                this,
                request,
                null,
                Runnable::run,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {

                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleCredential(response);
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        setBusy(false);
                        if (e instanceof GetCredentialCancellationException) {
                            return;
                        }
                        Log.e(TAG, "Credential Manager failed", e);
                        showError(getString(R.string.login_error_google));
                    }
                });
    }

    private void handleCredential(GetCredentialResponse response) {
        if (!(response.getCredential() instanceof androidx.credentials.CustomCredential custom)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(custom.getType())) {
            setBusy(false);
            showError(getString(R.string.login_error_google));
            return;
        }
        String idToken = GoogleIdTokenCredential.createFrom(custom.getData()).getIdToken();
        executor.execute(() -> {
            try {
                accountRepository.signIn(idToken);
                main.post(this::openMain);
            } catch (AccountRepository.AccountException e) {
                Log.w(TAG, "Sign-in failed: " + e.getKind(), e);
                main.post(() -> {
                    setBusy(false);
                    showError(messageFor(e));
                });
            }
        });
    }

    private String messageFor(AccountRepository.AccountException e) {
        return switch (e.getKind()) {
            // The trial is one-per-account, so a returning user whose trial lapsed lands here.
            case NO_SUBSCRIPTION -> getString(R.string.login_error_no_subscription);
            case DEVICE_QUOTA -> getString(R.string.login_error_device_quota);
            case NETWORK -> getString(R.string.login_error_network);
            default -> getString(R.string.login_error_server);
        };
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void setBusy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.signInButton.setEnabled(!busy);
        binding.errorText.setVisibility(View.GONE);
    }

    private void showError(String message) {
        binding.errorText.setText(message);
        binding.errorText.setVisibility(View.VISIBLE);
    }
}
