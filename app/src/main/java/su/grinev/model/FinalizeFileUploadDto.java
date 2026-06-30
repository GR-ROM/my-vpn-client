package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** FINALIZE_FILE_UPLOAD body — signal the upload is complete; the server flushes & closes. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalizeFileUploadDto {
    @Tag(0)
    private long uploadId;
}
