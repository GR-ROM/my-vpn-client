package su.grinev.myvpn;

public enum State {
    SHUTDOWN,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LOGIN,
    AWAITING_LOGIN_RESPONSE,
    GET_IP,
    AWAITING_RESPONSE_IP,
    IP_ACQUIRED,
    LIVE,
    WAITING,
    ERROR
}
