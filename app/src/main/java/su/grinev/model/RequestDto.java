package su.grinev.model;

import annotation.BsonType;
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
public class RequestDto<T> {
    private int seq;
    private Command command;
    private boolean responseRequired;
    @BsonType(discriminator = "_dataType")
    private T data;

    public static <T> RequestDto<T> wrap(Command command, T data) {
        return RequestDto.<T>builder()
                .command(command)
                .data(data)
                .build();
    }

    public static <T> RequestDto<T> wrap(Command command) {
        return RequestDto.<T>builder()
                .command(command)
                .data(null)
                .build();
    }
}