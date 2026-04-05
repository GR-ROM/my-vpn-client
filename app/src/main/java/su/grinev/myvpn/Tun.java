package su.grinev.myvpn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;

public interface Tun {

    void close();
    int readPacket(ByteBuffer buf) throws IOException;
    int writePacket(ByteBuffer buf) throws IOException;
    String getDeviceName();
    void configureTun(String ip, String gatewayIp, String dnsServer, boolean defaultRouteViaVpn, Set<String> excludedApps) throws InterruptedException, IOException;
}
