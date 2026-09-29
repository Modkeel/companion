package modkeel.companion.core;

import java.util.function.Consumer;

/** One-line log messages with a fixed prefix, so tests and players can find them. */
public final class Log {
    /**
     * Where lines go. The game sets its own logger: NeoForge leaves System.out out of
     * latest.log, which is the file players share when asking for help.
     */
    public static volatile Consumer<String> sink = System.out::println;

    private Log() {
    }

    public static void info(String message) {
        sink.accept("[modkeel] " + message);
    }

    public static void warn(String message, Throwable error) {
        sink.accept("[modkeel] " + message + ": " + error);
    }
}
