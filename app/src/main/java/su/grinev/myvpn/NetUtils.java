package su.grinev.myvpn;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class NetUtils {
    public static final int PROTOCOL_TCP = 6;
    public static final int PROTOCOL_UDP = 17;

    public static boolean isTcpOrUdp(byte[] packet) {
        return (packet[9] & 0xFF) == PROTOCOL_TCP || (packet[9] & 0xFF) == PROTOCOL_UDP;
    }

    public static int extractDestinationIP(byte[] packet, int size) {
        if (size < 20) {
            return 0;
        }
        return (packet[16] & 0xFF) << 24
                | (packet[17] & 0xFF) << 16
                | (packet[18] & 0xFF) << 8
                | packet[19] & 0xFF;
    }

    public static int extractSourceIP(byte[] packet, int size) {
        if (size < 20) {
            throw new IllegalArgumentException("Packet too short to contain IP header");
        }
        return (packet[12] & 0xFF) << 24
                | (packet[13] & 0xFF) << 16
                | (packet[14] & 0xFF) << 8
                | packet[15] & 0xFF;
    }

    public static void setSourceIP(byte[] packet, int size, int sourceIP) {
        if (size < 20) {
            throw new IllegalArgumentException("Packet too short to contain IP header");
        }
        packet[12] = (byte) ((sourceIP << 24) & 0xFF);
        packet[13] = (byte) ((sourceIP << 16) & 0xFF);
        packet[14] = (byte) ((sourceIP << 8) & 0xFF);
        packet[15] = (byte) (sourceIP & 0xFF);
    }

    public static int getClientIdByHost(InetAddress inetAddress, int port) {
        return (inetAddress.getHostAddress() + ":" + port).hashCode();
    }

    private void logIPHeader(byte[] packet) {
        if (packet.length < 20) {
            System.out.println("Packet too short to contain an IP header");
            return;
        }

        int versionAndHeaderLength = packet[0] & 0xFF;
        int version = versionAndHeaderLength >> 4;
        int headerLength = (versionAndHeaderLength & 0x0F) * 4;
        int totalLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        int protocol = packet[9] & 0xFF;
        String sourceIP = (packet[12] & 0xFF) + "." + (packet[13] & 0xFF) + "." +
                (packet[14] & 0xFF) + "." + (packet[15] & 0xFF);
        String destIP = (packet[16] & 0xFF) + "." + (packet[17] & 0xFF) + "." +
                (packet[18] & 0xFF) + "." + (packet[19] & 0xFF);

        String protocolName = getProtocolName(protocol);
        System.out.printf("%s > %s: IP (v%d, hl=%d, len=%d) %s\n",
                sourceIP,
                destIP,
                version,
                headerLength,
                totalLength,
                protocolName
        );
    }

    private String getProtocolName(int protocol) {
        return switch (protocol) {
            case 1 -> "ICMP";
            case 6 -> "TCP";
            case 17 -> "UDP";
            default -> "Unknown";
        };
    }

    public static int ipv4ToInt(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address: " + ip);
        int result = 0;
        for (int i = 0; i < 4; i++) {
            int b = Integer.parseInt(parts[i]);
            if (b < 0 || b > 255) throw new IllegalArgumentException("Invalid byte in IP: " + parts[i]);
            result |= (b << (24 - (8 * i)));
        }
        return result;
    }

    public static byte[] ipv4ToIntBytes(String ip) {
        String[] parts = ip.split("\\.");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) (Integer.parseInt(parts[i]) & 0xFF);
        }
        return bytes;
    }

    public static String intToIpv4(int ip) {
        return String.format("%d.%d.%d.%d",
                (ip >>> 24) & 0xFF,
                (ip >>> 16) & 0xFF,
                (ip >>> 8) & 0xFF,
                ip & 0xFF);
    }

    public static String resolveHostname(int ip) {
        byte[] addr = new byte[]{
                (byte) (ip >>> 24),
                (byte) (ip >>> 16),
                (byte) (ip >>> 8),
                (byte) (ip)
        };
        try {
            InetAddress inet = InetAddress.getByAddress(addr);
            return inet.getHostName();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public boolean validateIpPacket(byte[] packet) {
        if (packet.length < 20) {
            System.out.println("Packet is too short to be a valid IP packet");
            return false;
        }
        if (packet.length > 1500) {
            System.out.println("Packet size exceeds MTU");
            return false;
        }
        int version = (packet[0] >> 4) & 0xF;
        if (version != 4) {
            System.out.println("Unsupported IP version: " + version);
            return false;
        }
        int headerLength = (packet[0] & 0xF) * 4;
        if (headerLength < 20 || headerLength > packet.length) {
            System.out.println("Invalid IP header length: " + headerLength);
            return false;
        }
        int totalLength = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
        if (totalLength != packet.length) {
            System.out.println("Mismatch between total length and packet length");
            return false;
        }
        return true;
    }

    public static int computeIpv4HeaderChecksum(byte[] packet, int headerLen) {
        long sum = 0L;

        for (int i = 0; i < headerLen; i += 2) {
            if (i == 10) {
                continue;
            }
            int hi = packet[i] & 0xFF;
            int lo = (i + 1 < headerLen) ? (packet[i + 1] & 0xFF) : 0;
            sum += (hi << 8) | lo;
            if ((sum & 0xFFFF_0000_0000L) != 0) {
                sum = (sum & 0xFFFF_FFFFL) + (sum >>> 16);
            }
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }

        int checksum = (int) (~sum) & 0xFFFF;
        return checksum;
    }


    public static String formatTcpdumpLike(byte[] buf) {
        if (buf == null || buf.length < 1) {
            return "[truncated packet]";
        }

        int firstByte = buf[0] & 0xFF;
        int version = (firstByte >> 4) & 0xF;

        if (version == 4) {
            return formatIpv4(buf);
        } else if (version == 6) {
            return formatIpv6(buf);
        } else {
            return "Unknown IP version: " + version;
        }
    }

    // ================= IPv4 =================
    private static String formatIpv4(byte[] buf) {
        if (buf.length < 20) {
            return "IPv4: [truncated header]";
        }

        int vihl = buf[0] & 0xFF;
        int ihl = vihl & 0x0F;
        int ipHeaderLen = ihl * 4;

        if (buf.length < ipHeaderLen) {
            return "IPv4: [truncated header]";
        }

        int totalLen = ((buf[2] & 0xFF) << 8) | (buf[3] & 0xFF);
        int proto = buf[9] & 0xFF;

        byte[] src = new byte[4];
        byte[] dst = new byte[4];
        System.arraycopy(buf, 12, src, 0, 4);
        System.arraycopy(buf, 16, dst, 0, 4);

        String srcIp = ipv4ToString(src);
        String dstIp = ipv4ToString(dst);

        int payloadOffset = ipHeaderLen;
        int payloadLen = Math.max(0, totalLen - ipHeaderLen);

        String prefix = "IPv4 ";

        return switch (proto) {
            case 6 -> prefix + formatTcp(buf, payloadOffset, payloadLen, srcIp, dstIp);
            case 17 -> prefix + formatUdp(buf, payloadOffset, payloadLen, srcIp, dstIp);
            case 1 -> prefix + srcIp + " > " + dstIp + ": ICMP, length " + payloadLen;
            default -> prefix + srcIp + " > " + dstIp + ": proto " + proto + ", length " + payloadLen;
        };
    }

    // ================= IPv6 =================
    private static String formatIpv6(byte[] buf) {
        if (buf.length < 40) {
            return "IPv6: [truncated header]";
        }

        int payloadLen = ((buf[4] & 0xFF) << 8) | (buf[5] & 0xFF);
        int nextHeader = buf[6] & 0xFF;

        byte[] src = new byte[16];
        byte[] dst = new byte[16];
        System.arraycopy(buf, 8, src, 0, 16);
        System.arraycopy(buf, 24, dst, 0, 16);

        String srcIp = ipv6ToString(src);
        String dstIp = ipv6ToString(dst);

        int payloadOffset = 40;
        int payloadLenClamped = Math.max(0, Math.min(payloadLen, buf.length - payloadOffset));

        String prefix = "IPv6 ";

        return switch (nextHeader) {
            case 6 -> prefix + formatTcp(buf, payloadOffset, payloadLenClamped, srcIp, dstIp);
            case 17 -> prefix + formatUdp(buf, payloadOffset, payloadLenClamped, srcIp, dstIp);
            case 58 -> prefix + srcIp + " > " + dstIp + ": ICMP6, length " + payloadLenClamped;
            default -> prefix + srcIp + " > " + dstIp + ": next-header " + nextHeader + ", length " + payloadLenClamped;
        };
    }

    // ================= TCP / UDP =================

    private static String formatTcp(byte[] buf, int off, int len,
                                    String srcIp, String dstIp) {

        if (buf.length - off < 20) {
            return srcIp + " > " + dstIp + ": TCP, [truncated], length " + len;
        }

        int srcPort = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
        int dstPort = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);

        int dataOffset = ((buf[off + 12] & 0xF0) >> 4) * 4;
        int payloadLen = Math.max(0, len - dataOffset);

        return srcIp + "." + srcPort +
                " > " +
                dstIp + "." + dstPort +
                ": TCP, length " + payloadLen;
    }

    private static String formatUdp(byte[] buf, int off, int len,
                                    String srcIp, String dstIp) {

        if (buf.length - off < 8) {
            return srcIp + " > " + dstIp + ": UDP, [truncated], length " + len;
        }

        int srcPort = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
        int dstPort = ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
        int udpLen = ((buf[off + 4] & 0xFF) << 8) | (buf[off + 5] & 0xFF);

        int payloadLen = Math.max(0, udpLen - 8);

        return srcIp + "." + srcPort +
                " > " +
                dstIp + "." + dstPort +
                ": UDP, length " + payloadLen;
    }

    // ================= Helpers =================

    private static String ipv4ToString(byte[] addr) {
        return (addr[0] & 0xFF) + "." +
                (addr[1] & 0xFF) + "." +
                (addr[2] & 0xFF) + "." +
                (addr[3] & 0xFF);
    }

    private static String ipv6ToString(byte[] addr) {
        try {
            return InetAddress.getByAddress(addr).getHostAddress();
        } catch (UnknownHostException e) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(':');
                int v = ((addr[i] & 0xFF) << 8) | (addr[i + 1] & 0xFF);
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        }
    }
}

