package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * REQUEST_LOGS body (server → client): the server asks this client to upload its log file for
 * {@code date} (ISO yyyy-MM-dd; empty/null = today). Mirrors the server DTO. The client uploads only
 * in response to this — never unsolicited.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLogsDto {
    @Tag(0)
    private String date;
}
