package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the client's session selection ({@link VpnClientWrapper#selectLiveSession}): hash modulo the
 * total session count, honoring that target when it's live, and only spilling to the first live
 * session (ascending index order) when the target is dead. This keeps flow affinity stable and moves
 * only a dead session's flows on failover, not everyone's.
 */
public class MultisessionBalancerTest {

    @Test
    public void allLiveIsPlainModuloOverSize() {
        boolean[] live = {true, true, true, true};
        for (int hash = -100; hash <= 100; hash++) {
            // every session live => the target is always honored => plain modulo over total size
            assertEquals(Math.floorMod(hash, 4),
                    VpnClientWrapper.selectLiveSession(hash, live));
        }
    }

    @Test
    public void honorsTargetWhenTargetIsLive() {
        int n = 6;
        for (int mask = 1; mask < (1 << n); mask++) { // at least one live
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
        int n = 6;
        for (int mask = 1; mask < (1 << n); mask++) { // at least one live
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
        int n = 6;
        int[] edgeHashes = {Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 0, 1, 123456789, -987654321};
        for (int mask = 1; mask < (1 << n); mask++) { // mask>=1 => at least one live
            boolean[] live = new boolean[n];
            for (int i = 0; i < n; i++) {
                live[i] = (mask & (1 << i)) != 0;
            }
            for (int hash = -50; hash <= 50; hash++) {
                int idx = VpnClientWrapper.selectLiveSession(hash, live);
                assertTrue("mask=" + mask + " hash=" + hash + " idx=" + idx, idx >= 0 && live[idx]);
            }
            for (int hash : edgeHashes) {
                int idx = VpnClientWrapper.selectLiveSession(hash, live);
                assertTrue("mask=" + mask + " hash=" + hash + " idx=" + idx, idx >= 0 && live[idx]);
            }
        }
    }

    @Test
    public void noneLiveReturnsMinusOne() {
        assertEquals(-1, VpnClientWrapper.selectLiveSession(42, new boolean[]{false, false, false}));
        assertEquals(-1, VpnClientWrapper.selectLiveSession(Integer.MIN_VALUE, new boolean[]{false}));
    }

    @Test
    public void affinityIsStableForAFlow() {
        boolean[] live = {true, false, true, true, false};
        int hash = 0x5A5A5A5A;
        int first = VpnClientWrapper.selectLiveSession(hash, live);
        for (int i = 0; i < 1000; i++) {
            assertEquals(first, VpnClientWrapper.selectLiveSession(hash, live));
        }
        assertTrue(live[first]);
    }
}
