package su.grinev.myvpn.settings;

import java.util.Set;

/**
 * Abstraction for VPN settings access.
 * Follows Dependency Inversion Principle - depend on abstractions, not concretions.
 */
public interface SettingsProvider {
    String getServerIp();
    int getServerPort();
    String getJwt();
    Set<String> getExcludedApps();
}
