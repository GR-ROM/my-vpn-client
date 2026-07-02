package su.grinev.myvpn;

import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Set;

import lombok.Getter;

public class TunAndroid implements Tun {

    // Poll timeout only bounds shutdown latency; longer = fewer idle wakeups (battery).
    private static final int READ_POLL_TIMEOUT_MS = 2000;

    @Getter
    private final VpnService vpnService;
    private ParcelFileDescriptor tunFd;
    private FileChannel readChannel;
    private FileChannel writeChannel;
    private String deviceName;
    // Preallocated for the single TUN reader thread — readPacket runs once per upload
    // packet, and a fresh StructPollfd[] per call is pure GC churn on the hottest loop.
    private StructPollfd[] readPollFds;
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
            } catch (PackageManager.NameNotFoundException e) {
                // excluded app not installed — skip
            }
        }
        if (defaultRouteViaVpn) {
            builder.addRoute("0.0.0.0", 0);
        }

        tunFd = builder.establish();
        if (tunFd == null) {
            throw new IOException("Failed to establish TUN");
        }
        readChannel = new FileInputStream(tunFd.getFileDescriptor()).getChannel();
        writeChannel = new FileOutputStream(tunFd.getFileDescriptor()).getChannel();
        StructPollfd pollFd = new StructPollfd();
        pollFd.fd = tunFd.getFileDescriptor();
        pollFd.events = (short) OsConstants.POLLIN;
        readPollFds = new StructPollfd[]{pollFd};
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
        readPollFds = null;
        tunFd = null;
        deviceName = null;
    }

    @Override
    public int readPacket(ByteBuffer buf) throws IOException {
        StructPollfd[] fds = readPollFds;
        if (readChannel != null && fds != null) {
            fds[0].revents = 0;
            try {
                int result = Os.poll(fds, READ_POLL_TIMEOUT_MS);
                if (result <= 0 || (fds[0].revents & OsConstants.POLLIN) == 0) {
                    return 0;
                }
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) return 0;
                throw new IOException("poll failed on TUN fd", e);
            }
            buf.clear();
            return readChannel.read(buf);
        } else {
            try {
                Thread.sleep(1000);
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
