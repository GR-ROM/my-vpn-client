package su.grinev.model;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class VpnLoginRequestDto {

    private String jwt;

}
