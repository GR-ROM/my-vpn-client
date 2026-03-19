package su.grinev.model;

import java.time.Instant;

import annotation.Tag;
import annotation.Type;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Packet<T> {
    @Tag(0)
    private String ver;
    @Tag(1)
    private Instant timestamp;
    @Tag(2)
    @Type(discriminator = 1488)
    private T payload;

    public static Packet<RequestDto<?>> ofRequest(RequestDto<?> requestDto) {
        return Packet.<RequestDto<?>>builder()
                .ver("0.1")
                .payload(requestDto)
                .timestamp(Instant.now())
                .build();
    }

    public static Packet<ResponseDto<?>> ofResponse(ResponseDto<?> responseDto) {
        return Packet.<ResponseDto<?>>builder()
                .ver("0.1")
                .payload(responseDto)
                .timestamp(Instant.now())
                .build();
    }
}
