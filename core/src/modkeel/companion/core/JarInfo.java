package modkeel.companion.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What a mod jar declares and contains: mod id and name (Fabric, Quilt, NeoForge or Forge
 * metadata), its class packages and its mixin config files. Nested jars (jar-in-jar) are read
 * as mods of their own, with {@link #bundledIn} set.
 */
public final class JarInfo {
    private static final Pattern JSON_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TOML_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JSON_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MANIFEST_VERSION = Pattern.compile("(?m)^Implementation-Version:\\s*(\\S+)");
    private static final Pattern JSON_DEPENDS =Pattern.compile("\"depends\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern JSON_KEY = Pattern.compile("\"([^\"]+)\"\\s*:");
    private static final Pattern MIXIN_CONFIG = Pattern.compile("(?i)[^/]*mixin[^/]*\\.json");

    public String id;
    public String name;
    /** Declared version ("${file.jarVersion}" resolved from the manifest), or null. */
    public String version;
    /** Content hash of a nested jar; null for a top-level one (see {@link ModSet}). */
    public String sha1;
    public final String file;
    public final String bundledIn;
    /** False for a plain library with no mod metadata. */
    public boolean declared;
    public final Set<String> packages = new LinkedHashSet<>();
    public final Set<String> mixinConfigs = new LinkedHashSet<>();
    /** Required mod ids (Fabric/Quilt "depends"). */
    public final Set<String> depends = new LinkedHashSet<>();

    private JarInfo(String file, String bundledIn) {
        this.file = file;
        this.bundledIn = bundledIn;
    }

    public String displayName() {
        return name != null ? name : id != null ? id : file;
    }

    /** The jar and every jar nested in it, one JarInfo each. */
    public static List<JarInfo> read(Path jar) throws IOException {
        List<JarInfo> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(jar)) {
            read(in, jar.getFileName().toString(), null, out, 0);
        }
        return out;
    }

    private static void read(InputStream raw, String file, String bundledIn, List<JarInfo> out,
                             int depth) throws IOException {
        JarInfo info = new JarInfo(file, bundledIn);
        out.add(info);
        String manifestVersion = null;
        ZipInputStream zip = new ZipInputStream(raw);
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String n = e.getName();
            if (n.endsWith(".class")) {
                int slash = n.lastIndexOf('/');
                if (slash > 0 && !n.startsWith("META-INF/")) {
                    info.packages.add(n.substring(0, slash).replace('/', '.'));
                }
            } else if (n.equals("fabric.mod.json") || n.equals("quilt.mod.json")) {
                String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                info.id = first(JSON_ID, json, info.id);
                info.name = first(JSON_NAME, json, info.name);
                info.version = first(JSON_VERSION, json, info.version);
                Matcher dep = JSON_DEPENDS.matcher(json);
                if (dep.find()) {
                    Matcher key = JSON_KEY.matcher(dep.group(1));
                    while (key.find()) {
                        info.depends.add(key.group(1));
                    }
                }
            } else if (n.equals("META-INF/neoforge.mods.toml") || n.equals("META-INF/mods.toml")) {
                String toml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                info.id = first(TOML_ID, toml, info.id);
                info.name = first(TOML_NAME, toml, info.name);
                info.version = first(TOML_VERSION, toml, info.version);
            } else if (n.equals("META-INF/MANIFEST.MF")) {
                manifestVersion = first(MANIFEST_VERSION,
                        new String(zip.readAllBytes(), StandardCharsets.UTF_8), null);
            } else if (n.endsWith(".jar") && depth < 2) {
                byte[] nested = zip.readAllBytes();
                int at = out.size();
                read(new ByteArrayInputStream(nested), n.substring(n.lastIndexOf('/') + 1),
                     bundledIn != null ? bundledIn : file, out, depth + 1);
                out.get(at).sha1 = ModSet.sha1(nested);
            } else if (!e.isDirectory()) {
                Matcher m = MIXIN_CONFIG.matcher(n.substring(n.lastIndexOf('/') + 1));
                if (m.matches()) {
                    info.mixinConfigs.add(m.group());
                }
            }
        }
        info.declared = info.id != null;
        if (info.version != null && info.version.startsWith("${")) {
            info.version = manifestVersion;
        }
        if (info.id == null) {
            // a plain library: name it after its file
            info.id = file.replaceAll("(?i)\\.jar$", "");
        }
    }

    private static String first(Pattern p, String text, String fallback) {
        if (fallback != null) {
            return fallback;
        }
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
