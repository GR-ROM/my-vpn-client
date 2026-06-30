package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * LOGIN body. Carries only the JWT — the capability contract travels in the pre-auth HELLO
 * (the server builds the session contract from HELLO caps and ignores anything sent at login).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class VpnLoginRequestDto {

    @Tag(0)
    private String jwt;

}
