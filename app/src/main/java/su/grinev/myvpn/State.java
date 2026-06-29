package su.grinev.myvpn;

public enum State {
    SHUTDOWN,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    HELLO,
    AWAITING_HELLO_RESPONSE,
    LOGIN,
    AWAITING_LOGIN_RESPONSE,
    LIVE,
    WAITING,
    ERROR,
    SLEEPING
}
