package su.grinev.model;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VpnForwardPacketRequestDto {

    private byte[] packet;

}

