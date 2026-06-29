package su.grinev.model;

/**
 * Action for the {@link Command#FLOW_CONTROL} command (protocol 0.2): pause ({@code STOP}) or
 * resume ({@code START}) the server→client downlink without re-login. Serialized by name on the
 * wire; mirrors the server's enum.
 */
public enum FlowAction {
    START,
    STOP
}
