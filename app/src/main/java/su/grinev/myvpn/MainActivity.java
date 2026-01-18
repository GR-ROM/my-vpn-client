package su.grinev.myvpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import su.grinev.myvpn.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private State currentState = State.DISCONNECTED;
    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MyVpnService.ACTION_STATE.equals(intent.getAction())) {
                String stateName = intent.getStringExtra(MyVpnService.EXTRA_STATE);
                if (stateName != null) {
                    State state = State.valueOf(stateName);
                    runOnUiThread(() -> updateUI(state));
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.connectButton.setOnClickListener(this::onConnectClicked);

        DebugLog.observe(text -> runOnUiThread(() -> {
            binding.logView.setText(text);
            binding.logView.post(() -> {
                int scrollAmount = binding.logView.getLayout().getLineTop(binding.logView.getLineCount()) - binding.logView.getHeight();
                binding.logView.scrollTo(0, Math.max(scrollAmount, 0));
            });
        }));

        updateUI(State.DISCONNECTED);
    }

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                vpnReceiver,
                new IntentFilter(MyVpnService.ACTION_STATE)
        );
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver);
        super.onStop();
    }

    private void onConnectClicked(View v) {
        switch (currentState) {
            case DISCONNECTED:
            case ERROR:
                Intent prepare = VpnService.prepare(this);
                if (prepare != null) {
                    startActivityForResult(prepare, 1);
                } else {
                    startVpn();
                }
                break;
            case CONNECTING:
            case CONNECTED:
            default:
                Intent i = new Intent(this, MyVpnService.class);
                i.setAction(MyVpnService.ACTION_DISCONNECT);
                startService(i);
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            startVpn();
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, MyVpnService.class);
        startForegroundService(i);
        DebugLog.log("VPN start");
    }

    private void updateUI(State state) {
        currentState = state;

        switch (state) {
            case CONNECTED:
                binding.connectButton.setText("Disconnect");
                break;
            case CONNECTING:
                binding.connectButton.setText("Connecting…");
                break;
            case DISCONNECTED:
            case ERROR:
                binding.connectButton.setText("Connect");
                break;
        }

        String statusText = switch (state) {
            case CONNECTED -> "Status: CONNECTED";
            case CONNECTING -> "Status: CONNECTING…";
            case DISCONNECTED -> "Status: DISCONNECTED";
            case ERROR -> "Status: ERROR";
            case WAITING -> "Status: WAITING FOR RECONNECT";
            default -> "Status: UNKNOWN";
        };
        binding.statusText.setText(statusText);
    }

}