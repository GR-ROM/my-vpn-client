package su.grinev.model;

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
public class ResponseDto<T> {
    @Tag(0)
    private int requestId;
    @Tag(1)
    private Status status;
    @Tag(2)
    @Type(discriminator = 1488)
    private T data;

    public static <T> ResponseDto<T> ofRequest(RequestDto<?> requestDto, Status status) {
        return ResponseDto.<T>builder()
                .requestId(requestDto.getSeq())
                .status(status)
                .build();
    }
}
