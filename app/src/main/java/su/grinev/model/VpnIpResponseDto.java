package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class VpnIpResponseDto {
    @Tag(0)
    private int ipAddress;
    @Tag(1)
    private int gatewayIpAddress;
    @Tag(2)
    private int dnsServer;
}
