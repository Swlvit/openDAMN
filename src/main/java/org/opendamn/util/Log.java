package org.opendamn.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory application log shown in the log viewer. */
public final class Log {

    public enum Level { DEBUG, FINE, INFO, WARNING, SEVERE }

    public record Entry(LocalTime time, Level level, String message) {
        @Override
        public String toString() {
            return time.format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + level + " " + message;
        }
    }

    private static final int MAX = 20_000;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final List<Consumer<Entry>> LISTENERS = new CopyOnWriteArrayList<>();

    private Log() {}

    public static void log(Level level, String msg) {
        Entry e = new Entry(LocalTime.now(), level, msg);
        synchronized (ENTRIES) {
            ENTRIES.add(e);
            if (ENTRIES.size() > MAX) ENTRIES.subList(0, ENTRIES.size() - MAX).clear();
        }
        if (level.compareTo(Level.WARNING) >= 0) System.err.println(e);
        for (Consumer<Entry> l : LISTENERS) l.accept(e);
    }

    public static void debug(String m) { log(Level.DEBUG, m); }
    public static void fine(String m) { log(Level.FINE, m); }
    public static void info(String m) { log(Level.INFO, m); }
    public static void warn(String m) { log(Level.WARNING, m); }
    public static void severe(String m) { log(Level.SEVERE, m); }

    public static void severe(String m, Throwable t) {
        log(Level.SEVERE, m + ": " + t);
    }

    public static List<Entry> entries() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    public static void addListener(Consumer<Entry> l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Consumer<Entry> l) {
        LISTENERS.remove(l);
    }
}
