package su.grinev.myvpn;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public final class DebugLog {

    private static final StringBuilder BUFFER = new StringBuilder();
    private static final List<LogListener> LISTENERS = new ArrayList<>();

    public interface LogListener {
        void onLogUpdate(String text);
    }

    public static synchronized void log(String msg) {
        String line = "[" + System.currentTimeMillis() + "] " + msg + "\n";
        BUFFER.append(line);

        // лог в Logcat
        Log.d("MyVPN", msg);

        // оповещаем UI
        for (LogListener l : LISTENERS) {
            l.onLogUpdate(BUFFER.toString());
        }
    }

    public static synchronized void observe(LogListener listener) {
        LISTENERS.add(listener);
        listener.onLogUpdate(BUFFER.toString());
    }
}
