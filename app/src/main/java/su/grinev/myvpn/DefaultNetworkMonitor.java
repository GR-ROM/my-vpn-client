package su.grinev.myvpn;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the device's <b>underlying</b> internet networks (Wi-Fi / cellular), NOT the VPN. Lets
 * {@link VpnClientWrapper}:
 * <ul>
 *   <li>tell sessions whether a usable link exists ({@link #isAvailable}) so they PARK instead of
 *       busy-retrying when there is no connectivity;</li>
 *   <li>react to a path appearing / the last path disappearing ({@link Listener#onNetworkChanged})
 *       → reconnect down sessions immediately instead of waiting for the backoff.</li>
 * </ul>
 *
 * <p>Uses {@code registerNetworkCallback} with {@code NOT_VPN + INTERNET} — {@code
 * registerDefaultNetworkCallback} reports the VPN's own (stable) network while our VpnService is up,
 * so it never fires on Wi-Fi↔cellular transitions. Needs only {@code ACCESS_NETWORK_STATE}.
 */
public class DefaultNetworkMonitor {

    public interface Listener {
        void onNetworkChanged();
    }

    private final ConnectivityManager cm;
    private final Listener listener;
    private final Set<Network> networks = ConcurrentHashMap.newKeySet();
    private ConnectivityManager.NetworkCallback callback;

    public DefaultNetworkMonitor(ConnectivityManager cm, Listener listener) {
        this.cm = cm;
        this.listener = listener;
    }

    public void start() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                networks.add(network);
                DebugLog.log("Underlying network available: " + network + " (total=" + networks.size() + ")");
                listener.onNetworkChanged();   // a path appeared → wake down sessions / fail over to it
            }

            @Override
            public void onLost(Network network) {
                networks.remove(network);
                DebugLog.log("Underlying network lost: " + network + " (total=" + networks.size() + ")");
                // Notify only when the LAST path is gone (→ sessions park). When a path survives, a
                // session that was using the lost one dies and reconnects via a 1s backoff onto the
                // survivor — no need to disturb healthy sessions on every flap.
                if (networks.isEmpty()) {
                    listener.onNetworkChanged();
                }
            }
        };
        cm.registerNetworkCallback(request, callback);
    }

    public void stop() {
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            callback = null;
        }
        networks.clear();
    }

    /** True while at least one underlying internet network exists. */
    public boolean isAvailable() {
        return !networks.isEmpty();
    }
}
