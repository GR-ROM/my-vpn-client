package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /** Server's post-auth capability contract (server @Tag(3)); read for negotiation/diagnostics. */
    @Tag(3)
    private List<CapabilityDto> capabilities;
    /** VPN subnet prefix length for the TUN address (server @Tag(4)); 0/absent on legacy → default /16. */
    @Tag(4)
    private int prefixLength;
}
