package su.grinev.model;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class VpnIpResponseDto {
    private int ipAddress;
    private int gatewayIpAddress;
}
