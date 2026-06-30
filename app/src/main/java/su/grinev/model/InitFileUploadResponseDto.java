package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** INIT_FILE_UPLOAD response — the server-assigned id to echo in every chunk/finalize. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitFileUploadResponseDto {
    @Tag(0)
    private long uploadId;
}
