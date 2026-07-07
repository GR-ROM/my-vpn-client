package su.grinev.myvpn;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Last-resort diagnostics: logs every uncaught exception (thread name + full stack) through
 * {@link DebugLog} — and therefore into the daily {@link FileLogger} file — before handing off to
 * the platform default handler, which kills the process. Turns an intermittent "crashes on
 * disconnect" report into an exact stack trace in the next pulled log.
 */
public final class CrashLogger {

    private static final AtomicBoolean installed = new AtomicBoolean(false);

    private CrashLogger() {
    }

    public static void install() {
        if (!installed.compareAndSet(false, true)) {
            return;
        }
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                DebugLog.log("[CRASH] Uncaught exception on thread '" + thread.getName() + "': "
                        + Log.getStackTraceString(e));
                // FileLogger drains its queue on its own thread — give it a moment to reach disk
                // before the default handler kills the process.
                Thread.sleep(300);
            } catch (Throwable ignored) {
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, e);
                }
            }
        });
    }
}
