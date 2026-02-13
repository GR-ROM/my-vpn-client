package su.grinev.myvpn;

public enum State {
    SHUTDOWN,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LOGIN,
    AWAITING_LOGIN_RESPONSE,
    LIVE,
    WAITING,
    ERROR,
    SLEEPING
}
