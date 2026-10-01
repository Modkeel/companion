package modkeel.companion.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What is sent" in plain words: one line per part of a crash or session report, as a
 * translation key and its arguments. Read from the payload itself, so it can not drift from it.
 */
public final class Sent {
    /** A line to show: {@code modkeel.sent.<key>} with {@code args}. */
    public static final class Line {
        public final String key;
        public final String[] args;

        Line(String key, String... args) {
            this.key = "modkeel.sent." + key;
            this.args = args;
        }
    }

    private Sent() {
    }

    public static List<Line> lines(String json) {
        List<Line> out = new ArrayList<>();
        Object parsed;
        try {
            parsed = new Parser(json).value();
        } catch (RuntimeException e) {
            return out;
        }
        if (!(parsed instanceof Map)) {
            return out;
        }
        Map<?, ?> p = (Map<?, ?>) parsed;
        if (p.get("env") instanceof Map && !((Map<?, ?>) p.get("env")).isEmpty()) {
            Map<?, ?> env = (Map<?, ?>) p.get("env");
            out.add(new Line("setup", str(env.get("mc")), loader(str(env.get("loader"))),
                    str(env.get("loader_version")), str(env.get("java")), os(str(env.get("os"))),
                    gigabytes(env.get("ram_mb"))));
        }
        if (p.get("mods") instanceof List) {
            out.add(new Line("mods", String.valueOf(((List<?>) p.get("mods")).size())));
        } else if (p.containsKey("mods")) {
            out.add(new Line("mods_known"));
        }
        if (p.containsKey("exception")) {
            out.add(new Line("error", shortName(str(p.get("exception")))));
            out.add(new Line("frames", String.valueOf(size(p.get("frames")))));
            List<?> suspects = p.get("suspects") instanceof List ? (List<?>) p.get("suspects") : List.of();
            out.add(suspects.isEmpty() ? new Line("suspects_none")
                    : new Line("suspects", join(suspects)));
            if (p.get("fix") instanceof Map) {
                Map<?, ?> fix = (Map<?, ?>) p.get("fix");
                List<?> off = fix.get("disabled") instanceof List ? (List<?>) fix.get("disabled") : List.of();
                out.add("revert".equals(fix.get("title")) ? new Line("fix_revert")
                        : new Line("fix_disable", join(off)));
            } else {
                out.add(new Line("fix_later"));
            }
        }
        if (p.containsKey("minutes")) {
            out.add(new Line("minutes", str(p.get("minutes")), str(p.get("active_minutes"))));
            out.add(new Line(Boolean.TRUE.equals(p.get("crashed")) ? "crashed" : "not_crashed"));
            out.add(new Line("spikes", String.valueOf(size(p.get("spikes")))));
            out.add(new Line("activity", String.valueOf(size(p.get("activity")))));
        }
        if (p.containsKey("install")) {
            out.add(new Line("install"));
        }
        return out;
    }

    private static String str(Object o) {
        if (o instanceof Double && (Double) o == Math.rint((Double) o)) {
            return String.valueOf(((Double) o).longValue());
        }
        return o == null ? "?" : String.valueOf(o);
    }

    private static int size(Object o) {
        return o instanceof List ? ((List<?>) o).size() : o instanceof Map ? ((Map<?, ?>) o).size() : 0;
    }

    private static String join(List<?> items) {
        List<String> s = new ArrayList<>();
        for (Object o : items) {
            s.add(str(o));
        }
        return String.join(", ", s);
    }

    private static String loader(String id) {
        switch (id) {
            case "fabric": return "Fabric";
            case "neoforge": return "NeoForge";
            case "forge": return "Forge";
            case "quilt": return "Quilt";
            default: return id;
        }
    }

    private static String os(String id) {
        switch (id) {
            case "windows": return "Windows";
            case "macos": return "macOS";
            case "linux": return "Linux";
            default: return id;
        }
    }

    private static String gigabytes(Object mb) {
        if (!(mb instanceof Double)) {
            return "?";
        }
        return String.valueOf(Math.max(1, Math.round((Double) mb / 1024)));
    }

    /** java.lang.NullPointerException: NullPointerException. */
    private static String shortName(String exception) {
        return exception.substring(exception.lastIndexOf('.') + 1);
    }

    /** Just enough JSON for the payloads Reports and Sessions build: numbers come back as Double. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    skip();
                    String k = string();
                    skip();
                    expect(':');
                    m.put(k, value());
                    skip();
                    if (s.charAt(i++) == '}') {
                        return m;
                    }
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    skip();
                    if (s.charAt(i++) == ']') {
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    if (e == 'u') {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else {
                        sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i++) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
