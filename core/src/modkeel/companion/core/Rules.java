package modkeel.companion.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules bundle shipped in the jar ({@code modkeel/rules.tsv}, built from Modkeel lab data):
 * what an exact jar did in our lab, and which mods ran together. Offline. Official releases carry
 * it; a build from source has none and diagnoses crashes without lab facts.
 */
public final class Rules {
    public enum Status { SERVER, CLIENT, CRASHES, CLASH }

    /** A lab fact: about one jar (b is null) or about two mods. */
    public static final class Rule {
        public final String a;
        public final String b;
        public final String mc;
        public final Status status;
        /** The lab test that backs it, such as "perf_bench". */
        public final String source;

        Rule(String a, String b, String mc, Status status, String source) {
            this.a = a;
            this.b = b;
            this.mc = mc;
            this.status = status;
            this.source = source;
        }
    }

    private static final Pattern MC_IN_NAME = Pattern.compile(
            "(?:mc|\\+)(1\\.\\d{2}(?:\\.\\d{1,2})?)|(?<![\\d.])(2[6-9]\\.\\d(?:\\.\\d{1,2})?)(?!\\d)");

    private final Map<String, Rule> jars = new HashMap<>();
    private final Map<String, Rule> pairs = new HashMap<>();
    public int size;

    public static Rules load() {
        try (InputStream in = Rules.class.getResourceAsStream("/modkeel/rules.tsv")) {
            if (in == null) {
                return new Rules();
            }
            List<String> lines = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                lines.add(line);
            }
            return parse(lines);
        } catch (IOException e) {
            Log.warn("cannot read the rules bundle", e);
            return new Rules();
        }
    }

    public static Rules parse(List<String> lines) {
        Rules rules = new Rules();
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t");
            try {
                String source = p[5].split(";")[0].trim();
                if (p[0].equals("jar")) {
                    // jar sha1 id mc status detail
                    rules.jars.put(p[1] + "|" + p[3], new Rule(p[2], null, p[3], status(p[4]), source));
                } else if (p[0].equals("pair")) {
                    rules.pairs.put(p[1] + "|" + p[2] + "|" + p[3],
                            new Rule(p[1], p[2], p[3], status(p[4]), source));
                } else {
                    continue;
                }
                rules.size++;
            } catch (RuntimeException e) {
                Log.info("skipping a bad rule: " + line);
            }
        }
        return rules;
    }

    private static Status status(String s) {
        return Status.valueOf(s.toUpperCase(java.util.Locale.ROOT));
    }

    /** What the lab saw this exact file do on this Minecraft version, or null. */
    public Rule jar(String sha1, String mc) {
        return jars.get(sha1 + "|" + mc);
    }

    public Rule pair(String a, String b, String mc) {
        return a.compareTo(b) <= 0 ? pairs.get(a + "|" + b + "|" + mc) : pairs.get(b + "|" + a + "|" + mc);
    }

    /**
     * The Minecraft version a jar's file name says it is for, when that differs from the
     * running one ("lithium-fabric-0.15.4+mc1.21.1.jar" on 26.2 gives "1.21.1"); else null.
     */
    public static String otherVersionInName(String file, String mc) {
        if (mc == null || mc.isEmpty()) {
            return null;
        }
        Matcher m = MC_IN_NAME.matcher(file);
        String other = null;
        while (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            if (v.equals(mc) || mc.startsWith(v + ".")) {
                return null; // it names the running version
            }
            if (other == null) {
                other = v;
            }
        }
        return other;
    }
}
