package su.grinev.model;

/**
 * Client OS platform reported in the pre-auth HELLO ({@link HelloDto#platformType}) for
 * server-side analytics by platform (server spec §18.2). Serialized by name on the wire. Mirrors
 * the server's enum; the server maps an unknown value to {@code UNKNOWN} (forward-compat).
 */
public enum PlatformType {
    LINUX,
    WINDOWS,
    MAC,
    ANDROID,
    IOS,
    UNKNOWN
}
