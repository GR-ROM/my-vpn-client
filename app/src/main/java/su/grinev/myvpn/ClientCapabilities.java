package su.grinev.myvpn;

import su.grinev.model.CapabilityDto;
import su.grinev.model.Command;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Protocol 0.2 capability contract advertised by this Android client (server spec §18).
 * {@link #helloPreAuth()} is sent in the pre-auth HELLO (supported LOGIN versions only);
 * {@link #full()} is sent with the LOGIN once HELLO is acknowledged. {@link #parse} and
 * {@link #maxCommonVersion} negotiate the wire format of a command against the server's contract.
 */
public final class ClientCapabilities {

    public static final CapabilityDto.Version V0_1 = new CapabilityDto.Version(0, 1);
    public static final CapabilityDto.Version V0_2 = new CapabilityDto.Version(0, 2);

    private static final List<CapabilityDto> FULL = List.of(
            cap(Command.LOGIN, V0_1, V0_2),
            cap(Command.HELLO, V0_2),
            cap(Command.GET_IP, V0_1),
            cap(Command.FORWARD_PACKET, V0_2),
            cap(Command.PING, V0_1),
            cap(Command.DISCONNECT, V0_1),
            cap(Command.FLOW_CONTROL, V0_2),
            cap(Command.REQUEST_LOGS, V0_1)
    );

    // Full contract PLUS the CONTROL_CONN marker — advertised by the single connection this client
    // designates as its control channel (ep0 model). A server that supports it makes that connection
    // the sole FLOW_CONTROL channel; an older server ignores the unknown capability.
    private static final List<CapabilityDto> FULL_WITH_CONTROL;
    static {
        ArrayList<CapabilityDto> l = new ArrayList<>(FULL);
        l.add(cap(Command.CONTROL_CONN, V0_2));
        FULL_WITH_CONTROL = List.copyOf(l);
    }

    private static final List<CapabilityDto> HELLO_PRE_AUTH = List.of(
            cap(Command.LOGIN, V0_1, V0_2)
    );

    private ClientCapabilities() {
    }

    public static List<CapabilityDto> full() {
        return FULL;
    }

    /** Full contract plus CONTROL_CONN — sent in HELLO by the connection chosen as the control channel. */
    public static List<CapabilityDto> fullWithControl() {
        return FULL_WITH_CONTROL;
    }

    public static List<CapabilityDto> helloPreAuth() {
        return HELLO_PRE_AUTH;
    }

    public static List<CapabilityDto.Version> versionsOf(Command command) {
        for (CapabilityDto c : FULL) {
            if (c.getName().equals(command.name())) {
                return c.getVersion();
            }
        }
        return List.of();
    }

    /** Parse a wire capability list into a {@code Command -> supported versions} contract; unknown names ignored. */
    public static Map<Command, List<CapabilityDto.Version>> parse(List<CapabilityDto> capabilities) {
        Map<Command, List<CapabilityDto.Version>> contract = new EnumMap<>(Command.class);
        if (capabilities == null) {
            return contract;
        }
        for (CapabilityDto cap : capabilities) {
            Command command;
            try {
                command = Command.valueOf(cap.getName());
            } catch (IllegalArgumentException | NullPointerException ignored) {
                continue;
            }
            contract.put(command, cap.getVersion() == null ? List.of() : List.copyOf(cap.getVersion()));
        }
        return contract;
    }

    /** Highest version of {@code command} supported by both this client and the given peer contract, or null. */
    public static CapabilityDto.Version maxCommonVersion(Command command, Map<Command, List<CapabilityDto.Version>> peerContract) {
        if (peerContract == null) {
            return null;
        }
        List<CapabilityDto.Version> peer = peerContract.get(command);
        if (peer == null || peer.isEmpty()) {
            return null;
        }
        CapabilityDto.Version best = null;
        for (CapabilityDto.Version v : versionsOf(command)) {
            if (peer.contains(v) && (best == null || compare(v, best) > 0)) {
                best = v;
            }
        }
        return best;
    }

    private static int compare(CapabilityDto.Version a, CapabilityDto.Version b) {
        int cmp = Integer.compare(a.major(), b.major());
        return cmp != 0 ? cmp : Integer.compare(a.minor(), b.minor());
    }

    private static CapabilityDto cap(Command command, CapabilityDto.Version... versions) {
        return CapabilityDto.builder().name(command.name()).version(List.of(versions)).build();
    }
}
