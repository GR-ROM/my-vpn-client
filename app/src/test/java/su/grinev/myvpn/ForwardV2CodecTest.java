package su.grinev.myvpn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;

public class ForwardV2CodecTest {

    // Canonical v0.2 FORWARD map prefix — pinned to the wire spec and to the server's
    // ForwardPacketFastPath / desktop ForwardV2Codec. If this drifts, the fast paths stop matching.
    private static final byte[] CANONICAL_PREFIX = {
            (byte) 0x83,                              // map(3)
            0x00, (byte) 0xA3, '0', '.', '2',         // 0:"0.2"
            0x01, (byte) 0xA3, 'F', 'W', 'D',         // 1:"FWD"
            0x02                                      // 2: (bin packet follows)
    };

    @Test
    public void prefixMatchesWireSpec() {
        assertArrayEquals(CANONICAL_PREFIX, ForwardV2Codec.PREFIX);
    }

    private void assertRoundTrips(int len) {
        byte[] ip = new byte[len];
        for (int i = 0; i < len; i++) {
            ip[i] = (byte) (i * 7 + 3);
        }

        byte[] frame = new byte[ForwardV2Codec.frameSize(len)];
        int total = ForwardV2Codec.encode(frame, ByteBuffer.wrap(ip));
        assertEquals(frame.length, total);

        // big-endian length header covers the whole frame (including itself)
        int header = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16)
                | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        assertEquals(total, header);

        assertTrue(ForwardV2Codec.isForward(frame, total));
        int offset = ForwardV2Codec.packetOffset(frame);
        int binLen = ForwardV2Codec.packetLength(frame);
        assertEquals(len, binLen);

        byte[] got = new byte[binLen];
        System.arraycopy(frame, offset, got, 0, binLen);
        assertArrayEquals(ip, got);
    }

    @Test
    public void roundTripsSmallPacketBin8() {
        assertRoundTrips(100);   // < 256 -> bin8 header
    }

    @Test
    public void roundTripsLargePacketBin16() {
        assertRoundTrips(1400);  // >= 256 -> bin16 header
    }

    @Test
    public void roundTripsMtuPacket() {
        assertRoundTrips(TunHandler.MAX_MTU);   // largest packet the client forwards
    }

    @Test
    public void rejectsNonForwardFrame() {
        byte[] notV2 = new byte[20];
        notV2[4] = (byte) 0x84;  // v0.1 full envelope map(4), not the v0.2 prefix
        assertFalse(ForwardV2Codec.isForward(notV2, notV2.length));
    }

    @Test
    public void rejectsTooShortFrame() {
        assertFalse(ForwardV2Codec.isForward(new byte[8], 8));
    }

    // Mirrors writeBatch: pack several frames back-to-back into one buffer via encode(dst, offset, ..),
    // then walk it frame-by-frame the way the receiver frames by length header and verify each.
    @Test
    public void packsMultipleFramesBackToBack() {
        int[] lens = {1, 64, 255, 256, 1400, TunHandler.MAX_MTU};

        int total = 0;
        for (int len : lens) {
            total += ForwardV2Codec.frameSize(len);
        }
        byte[] scratch = new byte[total];
        byte[][] ips = new byte[lens.length][];

        int pos = 0;
        for (int k = 0; k < lens.length; k++) {
            byte[] ip = new byte[lens[k]];
            for (int i = 0; i < ip.length; i++) {
                ip[i] = (byte) (k * 91 + i * 7 + 1);
            }
            ips[k] = ip;
            int frameLen = ForwardV2Codec.encode(scratch, pos, ByteBuffer.wrap(ip));
            assertEquals(ForwardV2Codec.frameSize(lens[k]), frameLen);
            pos += frameLen;
        }
        assertEquals(total, pos);

        // Walk frame-by-frame: read length header, slice the frame, verify it standalone.
        int cursor = 0;
        for (int k = 0; k < lens.length; k++) {
            int frameLen = ((scratch[cursor] & 0xFF) << 24) | ((scratch[cursor + 1] & 0xFF) << 16)
                    | ((scratch[cursor + 2] & 0xFF) << 8) | (scratch[cursor + 3] & 0xFF);
            assertEquals(ForwardV2Codec.frameSize(lens[k]), frameLen);

            byte[] frame = new byte[frameLen];
            System.arraycopy(scratch, cursor, frame, 0, frameLen);

            assertTrue(ForwardV2Codec.isForward(frame, frameLen));
            int off = ForwardV2Codec.packetOffset(frame);
            int binLen = ForwardV2Codec.packetLength(frame);
            assertEquals(lens[k], binLen);

            byte[] got = new byte[binLen];
            System.arraycopy(frame, off, got, 0, binLen);
            assertArrayEquals(ips[k], got);

            cursor += frameLen;
        }
        assertEquals(total, cursor);   // consumed exactly the packed region, no gaps/overlap
    }
}
