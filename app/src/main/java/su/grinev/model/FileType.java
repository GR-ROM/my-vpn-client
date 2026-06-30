package su.grinev.model;

/** Kind of uploaded file — must match the server enum (serialized by name on the wire). */
public enum FileType {
    LOG,
    BINARY
}
