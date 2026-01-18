package su.grinev.myvpn;

import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DebugLog {

    public interface Listener {
        void onLog(String text);
    }

    private static final StringBuilder buffer = new StringBuilder(16_384);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void log(String msg) {
        String line = System.currentTimeMillis() + " " + msg + "\n";
        Log.d("MyVPN", msg);

        synchronized (buffer) {
            buffer.append(line);
            if (buffer.length() > 64_000) {
                buffer.delete(0, buffer.length() - 32_000);
            }
        }

        for (Listener l : listeners) {
            l.onLog(buffer.toString());
        }
    }

    public static void observe(Listener listener) {
        listeners.add(listener);
        synchronized (buffer) {
            listener.onLog(buffer.toString());
        }
    }

    public static void remove(Listener listener) {
        listeners.remove(listener);
    }
}

