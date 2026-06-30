package su.grinev.model;

/**
 * Response status, mirroring the server's enum. Serialized by name on the wire, so order is
 * irrelevant — but the full set is kept in sync so the client can recognise every status the
 * server may send (e.g. {@code OUTDATED} from HELLO, {@code UNSUPPORTED} from capability checks).
 */
public enum Status {
    OK,
    UNAUTHORIZED,
    TUN_IO_ERROR,
    BANNED,
    INTERNAL,
    FORBIDDEN,
    RESERVED_IP_OCCUPIED,
    UNSUPPORTED,
    OUTDATED,
    NOT_FOUND,
    IO_ERROR
}
