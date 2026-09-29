package modkeel.companion.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Who owns a class package, a mixin config or a mod id, across every jar in a mods folder. */
public final class Owners {
    private final Map<String, JarInfo> byPackage = new HashMap<>();
    private final Map<String, JarInfo> byMixinConfig = new HashMap<>();
    private final Map<String, JarInfo> byId = new HashMap<>();
    /** Every jar read, top-level ones first. */
    public final List<JarInfo> all = new ArrayList<>();

    public void add(JarInfo info) {
        all.add(info);
        byId.putIfAbsent(info.id, info);
        for (String p : info.packages) {
            byPackage.putIfAbsent(p, info);
        }
        for (String c : info.mixinConfigs) {
            byMixinConfig.putIfAbsent(c, info);
        }
    }

    public static Owners scan(Path modsDir) {
        Owners owners = new Owners();
        if (!Files.isDirectory(modsDir)) {
            return owners;
        }
        List<JarInfo> nested = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
            for (Path jar : ds) {
                try {
                    for (JarInfo info : JarInfo.read(jar)) {
                        if (info.bundledIn == null) {
                            owners.add(info);
                        } else {
                            nested.add(info);
                        }
                    }
                } catch (IOException e) {
                    Log.warn("cannot read " + jar.getFileName(), e);
                }
            }
        } catch (IOException e) {
            Log.warn("cannot list " + modsDir, e);
        }
        // after every top-level jar: a bundled copy never shadows the real mod
        nested.forEach(owners::add);
        return owners;
    }

    /** Owner of a fully qualified class name, by its package. */
    public JarInfo ofClass(String className) {
        int dot = className.lastIndexOf('.');
        return dot > 0 ? byPackage.get(className.substring(0, dot)) : null;
    }

    public JarInfo ofMixinConfig(String config) {
        return byMixinConfig.get(config);
    }

    public JarInfo ofId(String id) {
        return byId.get(id);
    }
}
