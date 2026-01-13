package su.grinev.model;

import annotation.BsonType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Packet<T> {
    private String ver;
    private Instant timestamp;

    @BsonType(discriminator = "_payloadType")
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
