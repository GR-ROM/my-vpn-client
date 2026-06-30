package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;

/** UPLOAD_FILE_CHUNK body — write {@code chunkData} at {@code offset} of upload {@code uploadId}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadFileChunkDto {
    @Tag(0)
    private long uploadId;
    @Tag(1)
    private long offset;
    @Tag(2)
    private ByteBuffer chunkData;
}
