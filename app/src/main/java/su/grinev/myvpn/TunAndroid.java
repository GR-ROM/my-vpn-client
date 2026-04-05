package su.grinev.myvpn;

import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Set;

import lombok.Getter;

public class TunAndroid implements Tun {

    @Getter
    private final VpnService vpnService;
    private ParcelFileDescriptor tunFd;
    private FileChannel readChannel;
    private FileChannel writeChannel;
    private String deviceName;
    public TunAndroid(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public void configureTun(String ip, String gateway, String dnsServer, boolean defaultRouteViaVpn, Set<String> excludedApps) throws IOException {
        VpnService.Builder builder;
        try {
            builder = vpnService.new Builder()
                    .setSession("MyVPN")
                    .addDisallowedApplication(vpnService.getPackageName())
                    .addAddress(ip, 24)
                    .addDnsServer(gateway);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        for (String pkg : excludedApps) {
            try {
                builder.addDisallowedApplication(pkg);
                DebugLog.log("Excluded from VPN: " + pkg);
            } catch (PackageManager.NameNotFoundException e) {
                DebugLog.log("Excluded app not found, skipping: " + pkg);
            }
        }
        DebugLog.log("Set tun IP: " + ip);
        if (defaultRouteViaVpn) {
            builder.addRoute("0.0.0.0", 0);
        }

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IOException("Failed to establish TUN");
        }
        readChannel = new FileInputStream(tunFd.getFileDescriptor()).getChannel();
        writeChannel = new FileOutputStream(tunFd.getFileDescriptor()).getChannel();
        deviceName = "tun0";
    }

    @Override
    public void close() {
        try {
            if (readChannel != null) {
                readChannel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (writeChannel != null) {
                writeChannel.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (tunFd != null) {
                tunFd.close();
            }
        } catch (IOException ignored) {
        }

        readChannel = null;
        writeChannel = null;
        tunFd = null;
        deviceName = null;

        DebugLog.log("TUN closed");
    }

    @Override
    public int readPacket(ByteBuffer buf) throws IOException {
        if (readChannel != null) {
            buf.clear();
            return readChannel.read(buf);
        } else {
            try {
                Thread.sleep(1000);
                DebugLog.log("tun is not ready, waiting...");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Thread interrupted while waiting for TUN");
            }
            return 0;
        }
    }

    @Override
    public int writePacket(ByteBuffer buf) throws IOException {
        if (writeChannel == null) {
            throw new RuntimeException("tun not ready");
        }
        return writeChannel.write(buf);
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }
}
