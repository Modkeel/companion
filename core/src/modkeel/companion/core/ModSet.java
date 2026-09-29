package modkeel.companion.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The jars in a mods folder, identified by content (sha1). Two sets with the same jars have
 * the same fingerprint whatever the file names or order.
 */
public final class ModSet {
    /** One jar: file name and content hash. */
    public static final class Jar {
        public final String file;
        public final String sha1;

        public Jar(String file, String sha1) {
            this.file = file;
            this.sha1 = sha1;
        }
    }

    public final List<Jar> jars;

    public ModSet(List<Jar> jars) {
        List<Jar> sorted = new ArrayList<>(jars);
        sorted.sort((a, b) -> a.file.compareTo(b.file));
        this.jars = Collections.unmodifiableList(sorted);
    }

    /**
     * Scan *.jar directly inside {@code modsDir}. Hashes are cached by (name, size, mtime) in
     * {@code cacheFile}, so a startup with 300 unchanged mods reads no jar.
     */
    public static ModSet scan(Path modsDir, Path cacheFile) throws IOException {
        Properties cache = new Properties();
        if (Files.exists(cacheFile)) {
            try (Reader r = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
                cache.load(r);
            }
        }
        Properties fresh = new Properties();
        List<Jar> jars = new ArrayList<>();
        if (Files.isDirectory(modsDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path p : ds) {
                    if (!Files.isRegularFile(p)) {
                        continue;
                    }
                    String key = p.getFileName() + "|" + Files.size(p) + "|"
                            + Files.getLastModifiedTime(p).toMillis();
                    String sha1 = cache.getProperty(key);
                    if (sha1 == null) {
                        sha1 = sha1(p);
                    }
                    fresh.setProperty(key, sha1);
                    jars.add(new Jar(p.getFileName().toString(), sha1));
                }
            }
        }
        Files.createDirectories(cacheFile.getParent());
        try (Writer w = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8)) {
            fresh.store(w, "Modkeel jar hash cache");
        }
        return new ModSet(jars);
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public String fingerprint() {
        List<String> hashes = new ArrayList<>();
        for (Jar j : jars) {
            hashes.add(j.sha1);
        }
        Collections.sort(hashes);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(String.join("\n", hashes).getBytes(StandardCharsets.UTF_8));
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Jar> bySha1() {
        Map<String, Jar> m = new LinkedHashMap<>();
        for (Jar j : jars) {
            m.put(j.sha1, j);
        }
        return m;
    }

    /** Jars in this set whose content is not in {@code other}. */
    public List<Jar> notIn(ModSet other) {
        Map<String, Jar> theirs = other.bySha1();
        List<Jar> out = new ArrayList<>();
        for (Jar j : jars) {
            if (!theirs.containsKey(j.sha1)) {
                out.add(j);
            }
        }
        return out;
    }

    /** Text form: "sha1<TAB>file" per line. */
    public void write(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Jar j : jars) {
            sb.append(j.sha1).append('\t').append(j.file).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static ModSet read(Path file) throws IOException {
        List<Jar> jars = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                jars.add(new Jar(line.substring(tab + 1), line.substring(0, tab)));
            }
        }
        return new ModSet(jars);
    }
}
