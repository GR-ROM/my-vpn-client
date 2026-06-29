package su.grinev.model;

import annotation.Tag;
import lombok.*;

import java.util.List;

/**
 * Body of the pre-auth HELLO exchange (protocol 0.2, server spec §18). Client sends its app
 * {@code version} and supported LOGIN versions; the server replies with the LOGIN versions it
 * supports (and omits its own version pre-auth). Mirrors the server's HelloDto on the wire.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class HelloDto {
    @Tag(0)
    private String version;
    @Tag(1)
    private List<CapabilityDto> capabilities;
    /** Client OS platform for server-side analytics (server spec §18.2). Serialized by name. */
    @Tag(2)
    private PlatformType platformType;
}
