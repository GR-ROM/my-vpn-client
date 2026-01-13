package su.grinev.myvpn;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public interface Tun {

    void close();
    void readPacket(byte[] packet, AtomicInteger bytesRead) throws IOException;
    int writePacket(byte[] packet, int size) throws IOException;
    String getDeviceName();
    void configureTun(String ip, boolean defaultRouteViaVpn) throws InterruptedException, IOException;
}
