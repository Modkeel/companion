package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a Minecraft crash report (or a log with a stack trace) that point at a mod:
 * the exception chain with its frames, and the lines where the loader names a mod directly.
 */
public final class CrashReport {
    private static final Pattern FRAME = Pattern.compile("^\\s*at ([^\\s(]+)\\(");
    private static final Pattern EXCEPTION = Pattern.compile(
            "^(?:Caused by: )?((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Error|Exception|Throwable)"
            + "[\\w$]*)(?::\\s*(.*))?$");
    private static final Pattern FROM_MOD = Pattern.compile("\\bfrom mod ([a-z0-9_.-]+)");
    private static final Pattern MIXIN_CONFIG = Pattern.compile("\\bin ([\\w.-]+\\.json):");
    private static final Pattern LOADING_ISSUE = Pattern.compile("-- Mod loading issue for: ([\\w.-]+) --");
    private static final Pattern MIXIN_HANDLER = Pattern.compile(
            "^(?:handler|redirect|modify|localvar|wrapOperation|wrapWithCondition|"
            + "modifyExpressionValue|modifyReturnValue|modifyReceiver|wrapMethod)\\$[^$]*\\$([a-z0-9_]+)\\$");

    /** One stack frame: module id (NeoForge "id@version/"), class and method. */
    public static final class Frame {
        public final String module;
        public final String className;
        public final String method;

        Frame(String module, String className, String method) {
            this.module = module;
            this.className = className;
            this.method = method;
        }

        /** Mod id carried by a merged mixin handler name, such as handler$abc000$lithium$tick. */
        public String mixinHandlerMod() {
            Matcher m = MIXIN_HANDLER.matcher(method);
            return m.find() ? m.group(1) : null;
        }
    }

    /** One exception of the chain. */
    public static final class Cause {
        public final String type;
        public final String message;
        public final List<Frame> frames = new ArrayList<>();

        Cause(String type, String message) {
            this.type = type;
            this.message = message == null ? "" : message;
        }

        public String simpleType() {
            return type.substring(type.lastIndexOf('.') + 1);
        }
    }

    public String time = "";
    public String description = "";
    /** Outermost first; the last one is the root cause. */
    public final List<Cause> causes = new ArrayList<>();
    public final Set<String> fromMod = new LinkedHashSet<>();
    public final Set<String> mixinConfigs = new LinkedHashSet<>();
    public final Set<String> loadingIssues = new LinkedHashSet<>();
    /** Read from a log where Minecraft reported the crash: the game was running, not starting. */
    public boolean whilePlaying;
    /** ...and when, as the log wrote it: "16:00:07". */
    public String logTime = "";

    public Cause root() {
        return causes.isEmpty() ? null : causes.get(causes.size() - 1);
    }

    public static CrashReport read(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    /** A log line from the game's logger: "[12:00:00] [main/INFO]: ...". */
    private static final Pattern LOG_LINE = Pattern.compile("^\\[[\\d:.]+\\] \\[");
    private static final Pattern LOG_TIME = Pattern.compile("^\\[(\\d\\d:\\d\\d:\\d\\d)");
    /** Lines a stack trace is made of, after its first. */
    private static final Pattern TRACE_LINE = Pattern.compile(
            "^(\\s+at |\\s*\\.\\.\\. \\d+ more|Caused by: |\\s+Suppressed: |\\s+Caused by: |\\s*$)");
    /** Log lines a crash may still write after its stack trace. */
    private static final int TAIL_LINES = 5;
    /** Minecraft logs this right before the trace of a crash, then writes the report. */
    static final Pattern FATAL =Pattern.compile("(Unreported|Reported) exception thrown!$");

    /**
     * Some crashes stop the game before Minecraft can write a report (a mixin that fails while
     * the first game classes load): only the log keeps them, as a stack trace at its very end.
     * Others hang the game while it stops, before the report (a mod deadlocked on the way out):
     * Minecraft logged the crash, and its trace sits anywhere in the log.
     * The trace is read as a report; null when the log ends any other way (the game closed).
     */
    public static CrashReport fromLog(String text) {
        String[] lines = text.split("\\r?\\n");
        int start = -1;
        int end = -1;
        int fatalStart = -1;
        int fatalEnd = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("Caused by: ") && EXCEPTION.matcher(lines[i]).matches()) {
                start = i;
                end = i + 1;
                while (end < lines.length && TRACE_LINE.matcher(lines[end]).find()) {
                    end++;
                }
                if (i > 0 && FATAL.matcher(lines[i - 1]).find()) {
                    fatalStart = start;
                    fatalEnd = end;
                }
                i = end - 1;
            }
        }
        if (start < 0) {
            return null;
        }
        boolean running = fatalStart >= 0;
        if (running) {
            // the crash Minecraft logged, not what mods logged while the game stopped
            start = fatalStart;
            end = fatalEnd;
        }
        int tail = 0;
        for (int i = end; i < lines.length; i++) {
            if (lines[i].contains("Stopping!") || (!running && ++tail > TAIL_LINES)) {
                return null;
            }
        }
        StringBuilder trace = new StringBuilder();
        for (int i = start; i < end; i++) {
            trace.append(lines[i]).append('\n');
        }
        CrashReport r = parse(trace.toString());
        r.whilePlaying = running;
        if (running) {
            Matcher m = LOG_TIME.matcher(lines[start - 1]);
            r.logTime = m.find() ? m.group(1) : "";
        }
        return r;
    }

    public static CrashReport parse(String text) {
        CrashReport r = new CrashReport();
        String[] lines = text.split("\\r?\\n");
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("---- Minecraft Crash Report ----")) {
                start = i;
                break;
            }
        }
        boolean inHead = true;
        Cause current = null;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("Time: ") && r.time.isEmpty()) {
                r.time = line.substring(6).trim();
            } else if (line.startsWith("Description: ") && r.description.isEmpty()) {
                r.description = line.substring(13).trim();
            }
            if (line.startsWith("A detailed walkthrough of the error")) {
                // the head exception is complete; later sections repeat frames
                inHead = false;
            }
            Matcher m;
            m = FROM_MOD.matcher(line);
            while (m.find()) {
                r.fromMod.add(m.group(1));
            }
            m = MIXIN_CONFIG.matcher(line);
            while (m.find()) {
                r.mixinConfigs.add(m.group(1));
            }
            m = LOADING_ISSUE.matcher(line);
            if (m.find()) {
                r.loadingIssues.add(m.group(1));
            }
            if (!inHead) {
                continue;
            }
            m = FRAME.matcher(line);
            if (m.find()) {
                if (current != null) {
                    Frame f = frame(m.group(1));
                    if (f != null) {
                        current.frames.add(f);
                    }
                }
                continue;
            }
            m = EXCEPTION.matcher(line.trim());
            if (m.matches() && (current == null || line.startsWith("Caused by: "))) {
                current = new Cause(m.group(1), m.group(2));
                r.causes.add(current);
            }
        }
        return r;
    }

    /** "knot//a.b.C.m", "TRANSFORMER/id@1.0/a.b.C.m", "java.base/a.b.C.m" or "a.b.C.m". */
    static Frame frame(String token) {
        String module = null;
        String[] parts = token.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            int at = parts[i].indexOf('@');
            if (at > 0) {
                module = parts[i].substring(0, at);
            }
        }
        String qualified = parts[parts.length - 1];
        int dot = qualified.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return new Frame(module, qualified.substring(0, dot), qualified.substring(dot + 1));
    }
}
