package su.grinev.myvpn;

import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;

public class TunAndroid implements Tun {

    @Getter
    private final VpnService vpnService;
    private ParcelFileDescriptor tunFd;
    private FileInputStream inputStream;
    private FileOutputStream outputStream;
    private String deviceName;
    public TunAndroid(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public void configureTun(String ip, String gateway, String dnsServer, boolean defaultRouteViaVpn) throws IOException {
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
        DebugLog.log("Set tun IP: " + ip);
        if (defaultRouteViaVpn) {
            builder.addRoute("0.0.0.0", 0);
        }

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IOException("Failed to establish TUN");
        }
        inputStream = new FileInputStream(tunFd.getFileDescriptor());
        outputStream = new FileOutputStream(tunFd.getFileDescriptor());
        deviceName = "tun0";
    }

    @Override
    public void close() {
        try {
            if (inputStream != null) {
                inputStream.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (tunFd != null) {
                tunFd.close();
            }
        } catch (IOException ignored) {
        }

        inputStream = null;
        outputStream = null;
        tunFd = null;
        deviceName = null;

        DebugLog.log("TUN closed");
    }

    @Override
    public void readPacket(byte[] packet, AtomicInteger bytesRead) throws IOException {
        if (inputStream != null) {
            int read = inputStream.read(packet);
            bytesRead.set(read);
        } else {
            try {
                Thread.sleep(1000);
                DebugLog.log("tun is not ready, waiting...");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Thread interrupted while waiting for TUN");
            }
        }
    }

    @Override
    public int writePacket(byte[] packet, int size) throws IOException {
        if (outputStream == null) {
            throw new RuntimeException("tun not ready");
        }
        outputStream.write(packet, 0, size);
        return size;
    }

    @Override
    public String getDeviceName() {
        return deviceName;
    }
}