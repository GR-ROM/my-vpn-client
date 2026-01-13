package su.grinev.model;

import annotation.BsonType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResponseDto<T> {
    private Status status;
    @BsonType(discriminator = "_dataType")
    private T data;

    public static <T> ResponseDto<T> wrap(Status status, T data) {
        return ResponseDto.<T>builder()
                .status(status)
                .data(data)
                .build();
    }
}
