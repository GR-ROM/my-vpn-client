package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the multisession flow balancer ({@link VpnClientWrapper#selectLiveSession}): 5-tuple hash
 * modulo the session count, honoring that target when it's live, and only spilling to the first live
 * session (ascending index) when the target is dead. Stable flow affinity → reduced head-of-line
 * blocking; a session going down moves only its own flows.
 */
public class MultisessionBalancerTest {

    @Test
    public void allLiveIsPlainModuloOverSize() {
        boolean[] live = {true, true};
        for (int hash = -100; hash <= 100; hash++) {
            assertEquals(Math.floorMod(hash, 2), VpnClientWrapper.selectLiveSession(hash, live));
        }
    }

    @Test
    public void honorsTargetWhenTargetIsLive() {
        int n = 4;
        for (int mask = 1; mask < (1 << n); mask++) {
            boolean[] live = new boolean[n];
            for (int i = 0; i < n; i++) {
                live[i] = (mask & (1 << i)) != 0;
            }
            for (int hash = -200; hash <= 200; hash++) {
                int target = Math.floorMod(hash, n);
                if (live[target]) {
                    assertEquals("mask=" + mask + " hash=" + hash,
                            target, VpnClientWrapper.selectLiveSession(hash, live));
                }
            }
        }
    }

    @Test
    public void spillsToFirstLiveWhenTargetIsDead() {
        int n = 4;
        for (int mask = 1; mask < (1 << n); mask++) {
            boolean[] live = new boolean[n];
            int firstLive = -1;
            for (int i = 0; i < n; i++) {
                live[i] = (mask & (1 << i)) != 0;
                if (live[i] && firstLive < 0) {
                    firstLive = i;
                }
            }
            for (int hash = -200; hash <= 200; hash++) {
                int target = Math.floorMod(hash, n);
                if (!live[target]) {
                    assertEquals("mask=" + mask + " hash=" + hash,
                            firstLive, VpnClientWrapper.selectLiveSession(hash, live));
                }
            }
        }
    }

    @Test
    public void neverReturnsADeadSession() {
        int n = 4;
        int[] edgeHashes = {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1, 123456789, -987654321};
        for (int mask = 1; mask < (1 << n); mask++) {
            boolean[] live = new boolean[n];
            for (int i = 0; i < n; i++) {
                live[i] = (mask & (1 << i)) != 0;
            }
            for (int hash : edgeHashes) {
                int idx = VpnClientWrapper.selectLiveSession(hash, live);
                assertTrue("mask=" + mask + " hash=" + hash + " idx=" + idx, idx >= 0 && live[idx]);
            }
        }
    }

    @Test
    public void noneLiveReturnsMinusOne() {
        assertEquals(-1, VpnClientWrapper.selectLiveSession(42, new boolean[]{false, false}));
        assertEquals(-1, VpnClientWrapper.selectLiveSession(Integer.MIN_VALUE, new boolean[]{false}));
    }

    @Test
    public void affinityIsStableForAFlow() {
        boolean[] live = {true, false};
        int hash = 0x5A5A5A5A;
        int first = VpnClientWrapper.selectLiveSession(hash, live);
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, VpnClientWrapper.selectLiveSession(hash, live));
        }
        assertTrue(live[first]);
    }
}
