package su.grinev.myvpn;

import java.nio.ByteBuffer;

/**
 * Wire codec for the v0.2 minimal FORWARD frame:
 * <pre>
 *   [int32 length][ msgpack map { 0:"0.2", 1:"FWD", 2:&lt;bin ip packet&gt; } ]
 * </pre>
 * Everything up to the bin value is a constant prefix, so encoding is a memcpy of that prefix plus
 * one copy of the packet, and decoding is a constant-prefix memcmp plus a bin-header read — no
 * general MessagePack machinery. Byte-identical to the server's {@code ForwardPacketFastPath} and
 * the desktop client's {@code ForwardV2Codec}.
 */
public final class ForwardV2Codec {

    /** Constant map prefix: 0x83, 0:"0.2"(fixstr), 1:"FWD"(fixstr), 2: (bin value follows). */
    public static final byte[] PREFIX = {
            (byte) 0x83,                              // map(3)
            0x00, (byte) 0xA3, '0', '.', '2',         // 0:"0.2"
            0x01, (byte) 0xA3, 'F', 'W', 'D',         // 1:"FWD"
            0x02                                      // 2: (bin packet follows)
    };

    private ForwardV2Codec() {
    }

    /** Bytes needed to encode a frame for a packet of {@code packetLen}. */
    public static int frameSize(int packetLen) {
        return 4 + PREFIX.length + (packetLen < 256 ? 2 : 3) + packetLen;
    }

    /**
     * Encode a full length-prefixed v0.2 FORWARD frame for {@code packet} into {@code dst}
     * (must hold at least {@link #frameSize}). Consumes {@code packet}. Returns total bytes written.
     */
    public static int encode(byte[] dst, ByteBuffer packet) {
        return encode(dst, 0, packet);
    }

    /**
     * Encode a full length-prefixed v0.2 FORWARD frame for {@code packet} into {@code dst} starting
     * at {@code offset}, so several frames can be packed back-to-back into one batch buffer.
     * Consumes {@code packet}. Returns the number of bytes written (this frame's length).
     */
    public static int encode(byte[] dst, int offset, ByteBuffer packet) {
        int packetLen = packet.remaining();
        int p = offset + 4;                              // reserve the length header
        System.arraycopy(PREFIX, 0, dst, p, PREFIX.length);
        p += PREFIX.length;
        if (packetLen < 256) {
            dst[p++] = (byte) 0xC4;                      // bin8
            dst[p++] = (byte) packetLen;
        } else {
            dst[p++] = (byte) 0xC5;                      // bin16
            dst[p++] = (byte) (packetLen >>> 8);
            dst[p++] = (byte) packetLen;
        }
        packet.get(dst, p, packetLen);
        p += packetLen;
        int frameLen = p - offset;                       // this frame's length, includes its header
        dst[offset]     = (byte) (frameLen >>> 24);      // big-endian length header
        dst[offset + 1] = (byte) (frameLen >>> 16);
        dst[offset + 2] = (byte) (frameLen >>> 8);
        dst[offset + 3] = (byte) frameLen;
        return frameLen;
    }

    /** True if {@code buf} (a full frame including its 4-byte length header) is a v0.2 FORWARD frame. */
    public static boolean isForward(byte[] buf, int frameLen) {
        if (frameLen < 4 + PREFIX.length + 2) {          // header + prefix + at least a bin8 header
            return false;
        }
        for (int i = 0; i < PREFIX.length; i++) {
            if (buf[4 + i] != PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /** Offset of the IP packet bytes in a matched frame, or -1 if the bin header is malformed. */
    public static int packetOffset(byte[] buf) {
        int p = 4 + PREFIX.length;
        int b = buf[p] & 0xFF;
        if (b == 0xC4) return p + 2;
        if (b == 0xC5) return p + 3;
        return -1;
    }

    /** Length of the IP packet bytes in a matched frame, or -1 if the bin header is malformed. */
    public static int packetLength(byte[] buf) {
        int p = 4 + PREFIX.length;
        int b = buf[p] & 0xFF;
        if (b == 0xC4) return buf[p + 1] & 0xFF;
        if (b == 0xC5) return ((buf[p + 1] & 0xFF) << 8) | (buf[p + 2] & 0xFF);
        return -1;
    }
}
