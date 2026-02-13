package su.grinev.model;

import java.nio.ByteBuffer;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VpnForwardPacketRequestDto {

    @Tag(0)
    private ByteBuffer packet;

}

