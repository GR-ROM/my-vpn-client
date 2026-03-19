package su.grinev.myvpn;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface Tun {

    void close();
    int readPacket(ByteBuffer buf) throws IOException;
    int writePacket(ByteBuffer buf) throws IOException;
    String getDeviceName();
    void configureTun(String ip, String gatewayIp, String dnsServer, boolean defaultRouteViaVpn) throws InterruptedException, IOException;
}
