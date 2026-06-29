package su.grinev.model;

import annotation.Tag;
import lombok.*;

import java.util.List;

/**
 * One entry of the protocol 0.2 capability contract (see myvpn server spec §18): a command/feature
 * id (a {@link Command} name, e.g. "FORWARD_PACKET") plus the list of supported versions.
 * Mirrors the server's CapabilityDto on the wire (SIMPLE_NAME discriminator, fixmap {0:int,1:int}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class CapabilityDto {
    @Tag(0)
    private String name;
    @Tag(1)
    private List<Version> version;

    /**
     * Version pair. A PLAIN CLASS on purpose, not a record: Android desugars records and
     * {@code Class.isRecord()} is false at runtime (and unsupported below API 33), so JBson's record
     * path doesn't apply and it falls back to the no-arg-ctor + getter/field path — which a record
     * lacks (its only constructor is canonical and its accessors are {@code major()}, not
     * {@code getMajor()}), causing a NoSuchMethodException. A @NoArgsConstructor @Data class
     * binds/unbinds reliably on every Android API level. The wire form is identical to the server's
     * {@code record Version(int major, int minor)}. The {@code major()}/{@code minor()} accessors are
     * kept so call sites read the same as against the server's record.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    public static class Version {
        @Tag(0)
        private int major;
        @Tag(1)
        private int minor;

        public int major() {
            return major;
        }

        public int minor() {
            return minor;
        }
    }
}
