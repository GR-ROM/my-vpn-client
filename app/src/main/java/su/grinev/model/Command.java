package su.grinev.model;

import java.util.Map;
import java.util.Optional;

public enum Command {
    LOGIN(1000),
    GET_IP(1010),
    RESPONSE_IP(1011),
    FORWARD_PACKET(1020),
    DISCONNECT(1030),
    START_FLOW(1040),
    STOP_FLOW(1050),
    PING(1060),
    PONG(1070);

    private final int value;
    private static final Map<Integer, Command> commandMap;

    Command(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    static {
        commandMap = Map.ofEntries(
                Map.entry(LOGIN.value, LOGIN),
                Map.entry(GET_IP.value, GET_IP),
                Map.entry(FORWARD_PACKET.value, FORWARD_PACKET),
                Map.entry(DISCONNECT.value, DISCONNECT),
                Map.entry(START_FLOW.value, START_FLOW),
                Map.entry(STOP_FLOW.value, STOP_FLOW),
                Map.entry(PING.value, PING),
                Map.entry(PONG.value, PONG)
        );
    }

    public static Command of(int value) {
        return Optional.ofNullable(commandMap.get(value)).orElseThrow(() -> new IllegalArgumentException("Unknown command"));
    }
}
