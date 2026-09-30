package modkeel.companion.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** What went wrong in a crash, which mods are behind it, and how sure we are. */
public final class Diagnosis {
    public enum Kind { MIXIN_FAILED, MISSING_CLASS, OUT_OF_MEMORY, MOD_LOADING, GENERIC }

    public enum Confidence { HIGH, MEDIUM, LOW, NONE }

    /** A mod blamed for the crash. {@code file} is the top-level jar to disable, or null. */
    public static final class Suspect {
        public final String id;
        public final String name;
        public final String file;
        public int score;
        public final List<String> reasons = new ArrayList<>();

        Suspect(String id, String name, String file) {
            this.id = id;
            this.name = name;
            this.file = file;
        }
    }

    /** Platform ids never blamed: a crash passes through them, it rarely starts there. */
    private static final Set<String> PLATFORM = new HashSet<>(Arrays.asList(
            "minecraft", "java", "fabricloader", "fabric", "fabric-api", "neoforge", "forge", "fml",
            "mixinextras", "quilt_loader", "quilted_fabric_api"));

    private static final String[] MISSING = {
        "NoClassDefFoundError", "ClassNotFoundException", "NoSuchMethodError", "NoSuchFieldError",
        "IncompatibleClassChangeError", "AbstractMethodError", "IllegalAccessError",
        "ClassCastException", "VerifyError"};

    public Kind kind = Kind.GENERIC;
    public Confidence confidence = Confidence.NONE;
    public final List<Suspect> suspects = new ArrayList<>();
    /** Root exception, as "SimpleType: message". */
    public String error = "";
    public String description = "";
    public String time = "";
    /**
     * Stable id of this kind of crash: the root exception type and its first frames, without
     * line numbers, lambda counters or mixin hashes. The same bug gives the same signature on
     * every machine.
     */
    public String signature = "";

    public Suspect top() {
        return suspects.isEmpty() ? null : suspects.get(0);
    }

    /** Evidence from outside the report (lab rules, file names). */
    public static final class Hint {
        final String id;
        final int score;
        final String reason;
        /** Only adds to a mod the report already implicates. */
        final boolean onlyIfSuspect;

        public Hint(String id, int score, String reason, boolean onlyIfSuspect) {
            this.id = id;
            this.score = score;
            this.reason = reason;
            this.onlyIfSuspect = onlyIfSuspect;
        }
    }

    public static Diagnosis of(CrashReport report, Owners owners) {
        return of(report, owners, new ArrayList<>());
    }

    public static Diagnosis of(CrashReport report, Owners owners, List<Hint> hints) {
        Diagnosis d = new Diagnosis();
        d.description = report.description;
        d.time = report.time;
        CrashReport.Cause root = report.root();
        if (root != null) {
            d.error = root.simpleType() + (root.message.isEmpty() ? "" : ": " + root.message);
        }
        d.kind = kind(report);
        d.signature = signature(report, d.kind);

        Map<String, Suspect> byId = new LinkedHashMap<>();
        for (String id : report.loadingIssues) {
            d.blame(byId, owners, id, 100, Msg.of("modkeel.reason.load_failed"));
        }
        for (String id : report.fromMod) {
            d.blame(byId, owners, id, 100, Msg.of("modkeel.reason.mixin"));
        }
        for (String config : report.mixinConfigs) {
            JarInfo owner = owners.ofMixinConfig(config);
            if (owner != null) {
                d.blame(byId, owners, owner.id, 90, Msg.of("modkeel.reason.mixin_config", config));
            }
        }
        for (int c = 0; c < report.causes.size(); c++) {
            boolean isRoot = c == report.causes.size() - 1;
            List<CrashReport.Frame> frames = report.causes.get(c).frames;
            for (int i = 0; i < frames.size(); i++) {
                CrashReport.Frame f = frames.get(i);
                // the first frames of the root cause are where it broke
                int weight = isRoot ? Math.max(12 - i, 3) : Math.max(6 - i, 1);
                // a merged mixin handler is that mod's code, whatever class it sits in
                String handler = f.mixinHandlerMod();
                if (handler != null) {
                    d.blame(byId, owners, handler, weight, Msg.of("modkeel.reason.mixin_trace"));
                    continue;
                }
                String id = f.module;
                if (id == null) {
                    JarInfo owner = owners.ofClass(f.className);
                    id = owner != null ? owner.id : null;
                }
                if (id != null) {
                    d.blame(byId, owners, id, weight, Msg.of("modkeel.reason.trace"));
                }
            }
        }
        if (d.kind == Kind.MISSING_CLASS && root != null) {
            // a missing class of another mod points at that mod too (a version mismatch)
            String missing = root.message.replace('/', '.').replaceAll("^.*?([\\w$]+(?:\\.[\\w$]+)+).*$", "$1");
            JarInfo owner = owners.ofClass(missing);
            if (owner != null) {
                d.blame(byId, owners, owner.id, 8, Msg.of("modkeel.reason.version"));
            }
        }

        for (Hint h : hints) {
            if (!h.onlyIfSuspect || byId.containsKey(h.id.toLowerCase(Locale.ROOT))) {
                d.blame(byId, owners, h.id, h.score, h.reason);
            }
        }

        List<Suspect> ranked = new ArrayList<>(byId.values());
        ranked.sort((a, b) -> b.score - a.score);
        if (!ranked.isEmpty()) {
            int best = ranked.get(0).score;
            for (Suspect s : ranked) {
                if (s.score * 3 >= best && d.suspects.size() < 3) {
                    d.suspects.add(s);
                }
            }
            int second = ranked.size() > 1 ? ranked.get(1).score : 0;
            if (best >= 90) {
                d.confidence = Confidence.HIGH;
            } else if (best >= 20 && best >= 2 * second) {
                d.confidence = Confidence.MEDIUM;
            } else {
                d.confidence = Confidence.LOW;
            }
        }
        return d;
    }

    private static boolean platform(String id) {
        return PLATFORM.contains(id) || id.startsWith("fabric-") || id.startsWith("fabric_");
    }

    /** A declared mod that is not part of the platform: one a player can act on. */
    static boolean blameable(JarInfo info) {
        return info.declared && info.id != null && !platform(info.id.toLowerCase(Locale.ROOT));
    }

    private void blame(Map<String, Suspect> byId, Owners owners, String rawId, int score,
                       String reason) {
        String id = rawId.toLowerCase(Locale.ROOT);
        if (platform(id)) {
            return;
        }
        Suspect s = byId.get(id);
        if (s == null) {
            JarInfo info = owners.ofId(id);
            if (info != null && !info.declared) {
                return; // a plain library: it breaks because a mod calls it wrong
            }
            String file = info == null ? null : info.bundledIn != null ? info.bundledIn : info.file;
            s = new Suspect(id, info == null ? id : info.displayName(), file);
            byId.put(id, s);
        }
        s.score += score;
        if (!s.reasons.contains(reason)) {
            s.reasons.add(reason);
        }
    }

    static String signature(CrashReport report, Kind kind) {
        StringBuilder sb = new StringBuilder(kind.name());
        CrashReport.Cause root = report.root();
        if (root != null) {
            sb.append('|').append(root.type);
            int n = 0;
            for (CrashReport.Frame f : root.frames) {
                if (n++ == 6) {
                    break;
                }
                String method = f.method.replaceAll("\\$[0-9a-f]{6}\\$", "\\$").replaceAll("\\$\\d+", "\\$");
                sb.append('|').append(f.className).append('.').append(method);
            }
        }
        for (String id : report.fromMod) {
            sb.append("|mod:").append(id);
        }
        for (String c : report.mixinConfigs) {
            sb.append("|mixin:").append(c);
        }
        for (String id : report.loadingIssues) {
            sb.append("|loading:").append(id);
        }
        return ModSet.sha1(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static Kind kind(CrashReport report) {
        String all = "";
        for (CrashReport.Cause c : report.causes) {
            all += c.type + " " + c.message + "\n";
        }
        if (all.contains("OutOfMemoryError")) {
            return Kind.OUT_OF_MEMORY;
        }
        if (!report.fromMod.isEmpty() || !report.mixinConfigs.isEmpty()
                || all.contains("org.spongepowered.asm.mixin")) {
            return Kind.MIXIN_FAILED;
        }
        for (String m : MISSING) {
            if (all.contains(m)) {
                return Kind.MISSING_CLASS;
            }
        }
        if (!report.loadingIssues.isEmpty() || report.description.contains("Mod loading")) {
            return Kind.MOD_LOADING;
        }
        return Kind.GENERIC;
    }
}
