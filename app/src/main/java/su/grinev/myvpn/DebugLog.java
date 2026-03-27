package su.grinev.myvpn;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DebugLog {

    public interface Listener {
        void onLog(String text);
    }

    private static final StringBuilder buffer = new StringBuilder(16_384);
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final String SPLASH = """
                
                __  ___     _    ______  _   __
               /  |/  /_  _| |  / / __ \\/ | / /
              / /|_/ / / / / | / / /_/ /  |/ /\s
             / /  / / /_/ /| |/ / ____/ /|  / \s
            /_/  /_/\\__, / |___/_/   /_/ |_/  \s
                   /____/:: by e4syJet v%s ::   \s
            """;

    public static void printSplash(String version) {
        String[] lines = String.format(SPLASH, version).split("\n");
        new Thread(() -> {
            try {
                for (int i = lines.length - 1; i >= 0; i--) {
                    synchronized (buffer) {
                        buffer.insert(0, lines[i] + "\n");
                    }
                    notifyListeners();
                    Thread.sleep(200);
                }
                Thread.sleep(2500);
                for (int i = 0; i < lines.length; i++) {
                    synchronized (buffer) {
                        int firstNewline = buffer.indexOf("\n");
                        if (firstNewline >= 0) {
                            buffer.delete(0, firstNewline + 1);
                        }
                    }
                    notifyListeners();
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "SplashThread").start();
    }

    private static void notifyListeners() {
        for (Listener l : listeners) {
            l.onLog(buffer.toString());
        }
    }

    // Reusable Date object and StringBuilder for log formatting — guarded by 'buffer' lock
    private static final Date reusableDate = new Date();
    private static final StringBuilder lineBuilder = new StringBuilder(256);

    public static void log(String msg) {
        Log.d("MyVPN", msg);

        synchronized (buffer) {
            reusableDate.setTime(System.currentTimeMillis());
            lineBuilder.setLength(0);
            lineBuilder.append('[');
            lineBuilder.append(timeFormat.format(reusableDate));
            lineBuilder.append("] ");
            lineBuilder.append(msg);
            lineBuilder.append('\n');
            buffer.append(lineBuilder);
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

