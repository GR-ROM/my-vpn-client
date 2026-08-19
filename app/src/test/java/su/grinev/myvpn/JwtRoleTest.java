package su.grinev.myvpn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import su.grinev.model.JwtRole;

/**
 * The multisession is sized from this before the client has talked to the server, so every shape a
 * token can arrive in — including a broken one — has to land on a role instead of an exception.
 */
public class JwtRoleTest {

    private static String token(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8))
                + "." + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8))
                + ".c2ln";
    }

    @Test
    public void rolesMapToTheServersPerRoleConnectionLimits() {
        assertEquals(JwtRole.ADMIN, JwtRole.fromToken(token("{\"sub\":\"a\",\"role\":\"ADMIN\"}")));
        assertEquals(JwtRole.USER, JwtRole.fromToken(token("{\"sub\":\"u\",\"role\":\"USER\"}")));
        assertEquals(JwtRole.TRIAL_USER, JwtRole.fromToken(token("{\"clientId\":7,\"role\":\"TRIAL_USER\"}")));

        assertEquals(4, JwtRole.ADMIN.sessions());
        assertEquals(2, JwtRole.USER.sessions());
        assertEquals(1, JwtRole.TRIAL_USER.sessions());
    }

    @Test
    public void spacingAndCaseInTheClaimStillResolve() {
        assertEquals(JwtRole.TRIAL_USER, JwtRole.fromToken(token("{\"role\" : \" trial_user \"}")));
    }

    @Test
    public void anythingUnusableIsTreatedAsUser() {
        assertEquals(JwtRole.USER, JwtRole.fromToken(null));
        assertEquals(JwtRole.USER, JwtRole.fromToken(""));
        assertEquals(JwtRole.USER, JwtRole.fromToken("not-a-token"));
        assertEquals(JwtRole.USER, JwtRole.fromToken(token("{\"sub\":\"u\"}")));
        assertEquals(JwtRole.USER, JwtRole.fromToken(token("{\"role\":\"SUPERUSER\"}")));
        assertEquals(JwtRole.USER, JwtRole.fromToken(token("{\"role\":7}")));
        assertEquals(JwtRole.USER, JwtRole.fromToken("aGVhZGVy.!!!.c2ln"));
    }
}
