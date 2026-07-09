package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link VpnClientWrapper#effectiveTunPrefix} — the TUN mask selection.
 *
 * <p>The server assigns virtual IPs across the whole VPN pool while the gateway stays at {@code .0.1},
 * so the TUN must be masked with the pool's real prefix (sent as {@code VpnIpResponseDto.prefixLength})
 * or the gateway goes off-link and traffic dies the moment the tunnel comes up. A legacy server sends
 * {@code 0}; the client then falls back to a wide {@code /16}.
 */
public class TunPrefixTest {

    @Test
    public void usesServerPrefixWhenProvided() {
        assertEquals(22, VpnClientWrapper.effectiveTunPrefix(22));   // the /22 pool on 87
        assertEquals(24, VpnClientWrapper.effectiveTunPrefix(24));
        assertEquals(16, VpnClientWrapper.effectiveTunPrefix(16));
        assertEquals(30, VpnClientWrapper.effectiveTunPrefix(30));
    }

    @Test
    public void fallsBackToSlash16WhenLegacyOrUnset() {
        assertEquals(16, VpnClientWrapper.effectiveTunPrefix(0));    // legacy server omits the field
        assertEquals(16, VpnClientWrapper.effectiveTunPrefix(-1));   // defensive: never emit a bad prefix
    }
}
