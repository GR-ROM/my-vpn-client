package su.grinev.model;

import annotation.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Body of the {@link Command#FLOW_CONTROL} request (protocol 0.2): pause/resume the downlink via
 * {@link FlowAction}. Mirrors the server's FlowControlRequestDto on the wire.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class FlowControlRequestDto {
    @Tag(0)
    private FlowAction action;
}
