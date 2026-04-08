package su.grinev.myvpn;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Locale;
import java.util.function.Consumer;

import su.grinev.myvpn.traffic.TrafficGraphView;
import su.grinev.myvpn.traffic.TrafficStats;
import su.grinev.myvpn.traffic.TrafficStatsManager;

public class TrafficActivity extends AppCompatActivity {

    private TrafficGraphView graphView;
    private TextView incomingTotalText;
    private TextView outgoingTotalText;
    private TextView incomingSpeedText;
    private TextView outgoingSpeedText;
    private final TrafficStatsManager statsManager = TrafficStatsManager.getInstance();
    private final Consumer<TrafficStats> statsListener = this::onTrafficUpdate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_traffic);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        graphView = findViewById(R.id.trafficGraph);
        incomingTotalText = findViewById(R.id.incomingTotal);
        outgoingTotalText = findViewById(R.id.outgoingTotal);
        incomingSpeedText = findViewById(R.id.incomingSpeed);
        outgoingSpeedText = findViewById(R.id.outgoingSpeed);

        // Initial update
        updateUI(statsManager.getCurrentStats());
        graphView.updateData(statsManager.getHistory());
    }

    @Override
    protected void onResume() {
        super.onResume();
        statsManager.addListener(statsListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        statsManager.removeListener(statsListener);
    }

    private void onTrafficUpdate(TrafficStats stats) {
        updateUI(stats);
        graphView.updateData(statsManager.getHistory());
    }

    private void updateUI(TrafficStats stats) {
        incomingTotalText.setText(formatBytes(stats.getIncomingBytes()));
        outgoingTotalText.setText(formatBytes(stats.getOutgoingBytes()));
        incomingSpeedText.setText(formatSpeed(stats.getIncomingRateMbps()));
        outgoingSpeedText.setText(formatSpeed(stats.getOutgoingRateMbps()));
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    private String formatSpeed(double mbps) {
        if (mbps < 1) {
            return String.format(Locale.US, "%.0f Kbps", mbps * 1000);
        } else if (mbps < 1000) {
            return String.format(Locale.US, "%.2f Mbps", mbps);
        } else {
            return String.format(Locale.US, "%.2f Gbps", mbps / 1000);
        }
    }
}
