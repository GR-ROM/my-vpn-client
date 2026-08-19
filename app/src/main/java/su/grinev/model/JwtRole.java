package su.grinev.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The role carried by the JWT and how many parallel connections it is worth.
 *
 * <p>The server caps a client by the same role (ConnectionLimits: ADMIN 4, USER 2, TRIAL_USER 1)
 * and rejects the surplus with FORBIDDEN, which the client answers by reconnecting in a loop. The
 * authoritative number arrives in the login response (VpnIpResponseDto.maxConnections), but the
 * sessions are opened before that, so the count is read from the token the client already holds.
 *
 * <p>The claim is picked out by pattern rather than through org.json: it is one flat string field
 * of a token we issue ourselves, and org.json is a stub in local unit tests.
 */
public enum JwtRole {

    ADMIN(4),
    USER(2),
    TRIAL_USER(1);

    private static final Pattern ROLE_CLAIM = Pattern.compile("\"role\"\s*:\s*\"\s*([A-Za-z_]+)\s*\"");

    private final int sessions;

    JwtRole(int sessions) {
        this.sessions = sessions;
    }

    /** Parallel connections this role may hold. */
    public int sessions() {
        return sessions;
    }

    /** Role claim of an unverified token; USER for anything missing, unknown or unparseable. */
    public static JwtRole fromToken(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return USER;
        }
        String[] parts = jwt.split("[.]");
        if (parts.length < 2) {
            return USER;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher m = ROLE_CLAIM.matcher(payload);
            if (!m.find()) {
                return USER;
            }
            return valueOf(m.group(1).toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }
}
