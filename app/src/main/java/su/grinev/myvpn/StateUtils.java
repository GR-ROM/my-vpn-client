package su.grinev.myvpn;

import java.util.Set;

public class StateUtils {

    public static State advanceStateIfTrueOrElse(
            boolean condition,
            State newState,
            State elseState,
            State currentState,
            Set<State> allowedState) {
        if (condition) {
            if (allowedState.contains(currentState)) {
                return newState;
            } else {
                throw new IllegalStateException("Unexpected state, expected " + allowedState + " got: " + currentState);
            }
        } else {
            return elseState;
        }
    }

}
