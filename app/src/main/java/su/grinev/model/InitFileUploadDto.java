package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** INIT_FILE_UPLOAD body — mirrors the server DTO (size / display name / kind). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitFileUploadDto {
    @Tag(0)
    private long size;
    @Tag(1)
    private String name;
    @Tag(2)
    private FileType type;
}
