package modkeel.companion.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * After Minecraft logs a crash it should write its report and close within seconds. Some mods
 * deadlock it on the way out (Xaero's World Map 1.39 on Forge 1.20.1: the server thread waits
 * on a lock a map thread holds, which waits on the render thread, which waits for the server
 * thread to stop): the window stops answering forever and no report is ever written. This
 * watches the game threads after a crash; once they stop moving, it writes the report itself,
 * naming the mod they are stuck in, and ends the process.
 */
public final class CrashWatch {
    /** The game threads stuck on the same stacks this long: they will not move again. */
    public long quietMs = Long.getLong("modkeel.crash.quiet_ms", 30_000);
    public long checkMs = 1000;
    /** Frames of each thread that tell a stuck thread from one still working. */
    static final int SIGNATURE_FRAMES = 12;
    static final String SERVER_THREAD = "Server thread";

    private final Path gameDir;
    private final Supplier<Owners> owners;
    /** Ends the process; tests replace it. */
    Runnable halt = () -> Runtime.getRuntime().halt(-1);
    /** The threads' stacks; tests replace it. */
    Supplier<Map<Thread, StackTraceElement[]>> stacks = Thread::getAllStackTraces;
    private volatile Thread watcher;

    public CrashWatch(Path gameDir, Supplier<Owners> owners) {
        this.gameDir = gameDir;
        this.owners = owners;
    }

    /** The line Minecraft logs right before a crash's trace and report. */
    public static boolean isFatal(String message) {
        return message != null && CrashReport.FATAL.matcher(message).find();
    }

    /** Minecraft logged this crash on that thread: watch it close. Only the first one counts. */
    public synchronized void crashed(Throwable error, String threadName) {
        if (watcher != null) {
            return;
        }
        Log.info("crash logged on " + threadName + ": watching the game close");
        Thread w = new Thread(() -> watch(error, threadName), "modkeel-crash-watch");
        w.setDaemon(true);
        watcher = w;
        w.start();
    }

    private void watch(Throwable error, String threadName) {
        String last = null;
        long since = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(checkMs);
            } catch (InterruptedException e) {
                return;
            }
            Map<Thread, StackTraceElement[]> game = gameThreads(stacks.get(), threadName);
            if (game.isEmpty()) {
                return;
            }
            String now = signature(game);
            if (!now.equals(last)) {
                last = now;
                since = System.nanoTime();
            } else if ((System.nanoTime() - since) / 1_000_000 >= quietMs) {
                hung(error, game);
                return;
            }
        }
    }

    /** The thread that crashed and the integrated server's, the two that close the game. */
    static Map<Thread, StackTraceElement[]> gameThreads(Map<Thread, StackTraceElement[]> all,
                                                        String threadName) {
        Map<Thread, StackTraceElement[]> game = new LinkedHashMap<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            String name = e.getKey().getName();
            if (name.equals(threadName) || name.equals(SERVER_THREAD)) {
                game.put(e.getKey(), e.getValue());
            }
        }
        return game;
    }

    /** State and innermost frames of each thread: the same while they are stuck. */
    static String signature(Map<Thread, StackTraceElement[]> game) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : game.entrySet()) {
            StackTraceElement[] s = e.getValue();
            b.append(e.getKey().getName()).append(' ').append(e.getKey().getState())
                    .append(Arrays.asList(s).subList(0, Math.min(s.length, SIGNATURE_FRAMES)))
                    .append('\n');
        }
        return b.toString();
    }

    private void hung(Throwable error, Map<Thread, StackTraceElement[]> game) {
        Owners o = owners.get();
        List<String> stuckIn = new ArrayList<>();
        for (StackTraceElement[] s : game.values()) {
            String owner = Spikes.owner(s, o);
            if (!owner.equals(Spikes.VANILLA) && !stuckIn.contains(owner)) {
                stuckIn.add(owner);
            }
        }
        try {
            Path file = write(gameDir, report(error, game, stuckIn, LocalDateTime.now()));
            Log.info("the game hung after its crash" + (stuckIn.isEmpty() ? "" : " in " + stuckIn)
                     + ": wrote " + file.getFileName() + " and closed it");
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot write the crash report", e);
        }
        halt.run();
    }

    /** A report in Minecraft's own format, so the next start reads it like any other. */
    static String report(Throwable error, Map<Thread, StackTraceElement[]> game,
                         List<String> stuckIn, LocalDateTime time) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        StringBuilder b = new StringBuilder()
                .append("---- Minecraft Crash Report ----\n")
                .append("// Written by Modkeel: the game hung after this crash, before Minecraft"
                        + " could write it.\n\n")
                .append("Time: ")
                .append(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\nDescription: Unexpected error\n\n")
                .append(trace.toString().replace("\r\n", "\n"))
                .append("\n\nA detailed walkthrough of the error, its code path and all known"
                        + " details is as follows:\n")
                .append("---------------------------------------------------------------------"
                        + "------------------\n\n")
                .append("-- Hang after the crash --\nDetails:\n\tStuck in mods: ")
                .append(stuckIn.isEmpty() ? "none found" : String.join(", ", stuckIn))
                .append("\nStuck threads:\n");
        for (Map.Entry<Thread, StackTraceElement[]> e : game.entrySet()) {
            b.append('"').append(e.getKey().getName()).append("\" ")
                    .append(e.getKey().getState()).append('\n');
            for (StackTraceElement f : e.getValue()) {
                b.append("\tat ").append(f).append('\n');
            }
        }
        return b.toString();
    }

    static Path write(Path gameDir, String report) throws IOException {
        Path dir = gameDir.resolve("crash-reports");
        Files.createDirectories(dir);
        Path file = dir.resolve("crash-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + "-client.txt");
        Files.write(file, report.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
