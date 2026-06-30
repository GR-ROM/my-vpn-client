package su.grinev.myvpn;

import android.content.Context;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Persists {@link DebugLog} lines to a per-day file with N-day retention, so the user can later
 * pick a date and upload that day's log to the server. Writes happen on a single background thread;
 * {@link #append} never blocks the caller (drops if the 10k backlog is full). Daily rollover and
 * retention cleanup run lazily on the writer thread. Files: {@code <filesDir>/logs/myvpn-YYYY-MM-DD.log}.
 */
public final class FileLogger {

    private static final String PREFIX = "myvpn-";
    private static final String EXT = ".log";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<>(10_000);

    private static volatile File logDir;
    private static volatile int retentionDays = 7;
    private static volatile boolean started = false;

    private FileLogger() {
    }

    public static synchronized void init(Context ctx, int retentionDays) {
        if (started) {
            return;
        }
        FileLogger.retentionDays = retentionDays;
        File dir = new File(ctx.getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        logDir = dir;
        started = true;
        Thread writer = new Thread(FileLogger::runWriter, "FileLogger");
        writer.setDaemon(true);
        writer.start();
    }

    /** Enqueue an already-formatted line (with timestamp). No-op before {@link #init}. */
    public static void append(String line) {
        if (started) {
            queue.offer(line);
        }
    }

    public static File logDirectory() {
        return logDir;
    }

    public static File fileFor(LocalDate date) {
        return new File(logDir, PREFIX + DATE.format(date) + EXT);
    }

    /** Dates (newest first) that currently have a non-empty log file. */
    public static List<LocalDate> availableDates() {
        List<LocalDate> dates = new ArrayList<>();
        File dir = logDir;
        if (dir == null) {
            return dates;
        }
        File[] files = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(EXT));
        if (files == null) {
            return dates;
        }
        for (File f : files) {
            if (f.length() == 0) {
                continue;
            }
            LocalDate date = parseDate(f.getName());
            if (date != null) {
                dates.add(date);
            }
        }
        dates.sort(Collections.reverseOrder());
        return dates;
    }

    private static void runWriter() {
        LocalDate current = null;
        BufferedWriter out = null;
        try {
            cleanupOldFiles();
            while (true) {
                String line = queue.take();
                LocalDate today = LocalDate.now();
                if (out == null || !today.equals(current)) {
                    closeQuietly(out);
                    current = today;
                    out = new BufferedWriter(new FileWriter(fileFor(today), true));
                    cleanupOldFiles();
                }
                out.write(line);
                if (!line.endsWith("\n")) {
                    out.write('\n');
                }
                if (queue.isEmpty()) {
                    out.flush();   // keep the file fresh between bursts (uploads read it directly)
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // give up file logging on IO error; logcat + the in-memory buffer still work
        } finally {
            closeQuietly(out);
        }
    }

    private static void cleanupOldFiles() {
        File dir = logDir;
        if (dir == null) {
            return;
        }
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays - 1L);   // keep today + (N-1) days
        File[] files = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(EXT));
        if (files == null) {
            return;
        }
        for (File f : files) {
            LocalDate date = parseDate(f.getName());
            if (date != null && date.isBefore(cutoff)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static LocalDate parseDate(String fileName) {
        try {
            String ds = fileName.substring(PREFIX.length(), fileName.length() - EXT.length());
            return LocalDate.parse(ds, DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private static void closeQuietly(BufferedWriter out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }
}
