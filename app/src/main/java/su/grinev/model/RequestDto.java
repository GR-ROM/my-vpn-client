package su.grinev.model;

import annotation.BsonType;
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
public class RequestDto<T> {
    @Tag(0)
    private int seq;
    @Tag(1)
    private Command command;
    @Tag(2)
    private boolean responseRequired;
    @Tag(3)
    @BsonType(discriminator = 1488)
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