package modkeel.companion.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * World backups under {@code <game>/modkeel/backups/<world>/}, or under a folder the player
 * chose ({@link #moveTo}): chunk-level snapshots ({@link Snapshots}, {@code .snap}) and the
 * whole-world zips older versions made.
 */
public final class Backups {
    /** The chosen folder, one absolute path; absent for the game folder. */
    static final String FOLDER_FILE = "backup-folder.txt";

    private Backups() {
    }

    public static Path dir(Path gameDir, String world) {
        return root(gameDir).resolve(world);
    }

    /**
     * Where this game's backups live: {@code <folder>/<instance>} in the chosen folder, or the
     * game folder's {@code modkeel/backups} when none was chosen or it is gone (a cloud drive
     * not mounted, a USB disk out).
     */
    public static Path root(Path gameDir) {
        Path folder = chosenFolder(gameDir);
        if (folder != null) {
            if (Files.isDirectory(folder)) {
                return folder.resolve(instanceName(gameDir));
            }
            Log.info("backup folder " + folder + " not found; backing up in the game folder");
        }
        return local(gameDir);
    }

    private static Path local(Path gameDir) {
        return gameDir.resolve("modkeel").resolve("backups");
    }

    /** The folder the player chose, or null for the game folder. */
    public static Path chosenFolder(Path gameDir) {
        Path f = gameDir.resolve("modkeel").resolve(FOLDER_FILE);
        try {
            if (Files.exists(f)) {
                String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim();
                return s.isEmpty() ? null : Paths.get(s);
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot read " + f, e);
        }
        return null;
    }

    /**
     * This game's folder inside a shared backup folder: the instance's name (the folder above
     * {@code .minecraft} in launchers that nest it) and a hash of its path, so two instances
     * with a world of the same name never share snapshots.
     */
    static String instanceName(Path gameDir) {
        Path abs = gameDir.toAbsolutePath().normalize();
        Path name = abs.getFileName();
        String n = name == null ? "minecraft" : name.toString();
        if ((n.equals(".minecraft") || n.equals("minecraft")) && abs.getParent() != null
                && abs.getParent().getFileName() != null) {
            n = abs.getParent().getFileName().toString();
        }
        n = n.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
        return (n.isEmpty() ? "minecraft" : n) + "-"
               + String.format("%08x", abs.toString().toLowerCase(Locale.ROOT).hashCode());
    }

    /**
     * Keep backups in {@code folder} from now on (null: the game folder again), bringing the
     * existing ones along: copied, then the setting switched, then the old ones deleted, so a
     * failure halfway leaves every backup where it was. Returns the new root.
     */
    public static Path moveTo(Path gameDir, Path folder) throws IOException {
        synchronized (Backups.class) {
            Path from = root(gameDir);
            Path to;
            if (folder == null) {
                to = local(gameDir);
            } else {
                folder = folder.toAbsolutePath().normalize();
                if (!Files.isDirectory(folder)) {
                    throw new IOException("not a folder: " + folder);
                }
                Path saves = gameDir.toAbsolutePath().normalize().resolve("saves");
                if (folder.startsWith(saves)) {
                    throw new IOException("inside the worlds folder: " + folder);
                }
                to = folder.resolve(instanceName(gameDir));
            }
            from = from.toAbsolutePath().normalize();
            to = to.toAbsolutePath().normalize();
            if (!to.equals(from) && (to.startsWith(from) || from.startsWith(to))) {
                throw new IOException("one folder inside the other: " + from + ", " + to);
            }
            List<Path> copied = new ArrayList<>();
            if (!to.equals(from) && Files.isDirectory(from)) {
                Files.createDirectories(to);
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(from, Files::isDirectory)) {
                    for (Path w : ds) {
                        Path dest = to.resolve(w.getFileName().toString());
                        if (Files.exists(dest)) {
                            throw new IOException("backups of " + w.getFileName() + " already in " + to);
                        }
                        copied.add(dest);
                        copyTree(w, dest);
                    }
                } catch (IOException e) {
                    for (Path d : copied) {
                        deleteTree(d);
                    }
                    throw e;
                }
            }
            Path f = gameDir.resolve("modkeel").resolve(FOLDER_FILE);
            if (folder == null) {
                Files.deleteIfExists(f);
            } else {
                Files.createDirectories(f.getParent());
                Files.write(f, folder.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!to.equals(from) && Files.isDirectory(from)) {
                for (Path d : copied) {
                    try {
                        deleteTree(from.resolve(d.getFileName().toString()));
                    } catch (IOException e) {
                        Log.warn("copied, but cannot delete the old backups in " + from, e);
                    }
                }
            }
            Log.info("backups now in " + to + " (" + copied.size() + " worlds moved from " + from + ")");
            return to;
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        try (Stream<Path> s = Files.walk(src)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path d = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(d);
                } else {
                    Files.copy(p, d, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
    }

    /** Zip {@code world} into {@code zip}. The world's session.lock is skipped (it is locked). */
    public static void zip(Path world, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        Path tmp = zip.resolveSibling(zip.getFileName() + ".part");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            out.setLevel(Deflater.BEST_SPEED);
            byte[] buf = new byte[1 << 16];
            Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = world.relativize(file).toString().replace('\\', '/');
                    if (rel.equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }
                    out.putNextEntry(new ZipEntry(rel));
                    try (InputStream in = Files.newInputStream(file)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    out.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        Files.move(tmp, zip, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Backups of a world, newest first. */
    public static List<Path> list(Path gameDir, String world) {
        List<Path> out = new ArrayList<>();
        Path dir = dir(gameDir, world);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.{zip,snap}")) {
                ds.forEach(out::add);
            } catch (IOException e) {
                Log.warn("cannot list " + dir, e);
            }
        }
        out.sort(Collections.reverseOrder());
        return out;
    }

    /** Every world with at least one backup. */
    public static List<String> worlds(Path gameDir) {
        List<String> out = new ArrayList<>();
        Path dir = root(gameDir);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
                ds.forEach(p -> out.add(p.getFileName().toString()));
            } catch (IOException e) {
                Log.warn("cannot list " + dir, e);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Why a backup was made, beside it as a {@link Msg} ("before playing with 2 new mods"). */
    public static void setReason(Path zip, String msg) throws IOException {
        Files.write(reasonFile(zip), msg.getBytes(StandardCharsets.UTF_8));
    }

    /** The stored reason, or null (backups from before reasons were kept). */
    public static String reason(Path zip) {
        try {
            Path f = reasonFile(zip);
            return Files.exists(f) ? new String(Files.readAllBytes(f), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            Log.warn("cannot read the reason of " + zip, e);
            return null;
        }
    }

    private static Path reasonFile(Path zip) {
        return zip.resolveSibling(zip.getFileName().toString().replaceAll("\\.(zip|snap)$", "") + ".txt");
    }

    /** The reason of a backup made before playing with the same mods as last time. */
    public static final String START = "modkeel.copy.start";

    public static void prune(Path gameDir, String world, int keep) {
        prune(gameDir, world, keep, keep);
    }

    /**
     * Keep the newest {@code keep} backups made for other reasons (mods changed, first copy) and,
     * besides them, the newest {@code keepStarts} made on a start with the same mods: playing
     * many times never pushes out the copy from before a mod change.
     */
    public static void prune(Path gameDir, String world, int keep, int keepStarts) {
        List<Path> all = list(gameDir, world);
        int others = 0;
        int starts = 0;
        for (Path b : all) {
            boolean start = START.equals(reason(b));
            if (start ? ++starts <= keepStarts : ++others <= keep) {
                continue;
            }
            try {
                Files.deleteIfExists(reasonFile(b));
                Files.delete(b);
            } catch (IOException e) {
                Log.warn("cannot delete " + b, e);
            }
        }
        Snapshots.gc(dir(gameDir, world));
    }

    public static long size(Path dir) throws IOException {
        long[] total = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    /**
     * Replace {@code savesDir/world} with the backup. The current world is moved aside to
     * {@code <game>/modkeel/replaced/<world>-<stamp>}, never deleted. Returns that path, or
     * null when there was no current world.
     */
    public static Path restore(Path gameDir, Path savesDir, String world, Path zip) throws IOException {
        Path target = savesDir.resolve(world);
        Path staging = savesDir.resolve(world + ".modkeel-restore");
        deleteTree(staging);
        if (Snapshots.is(zip)) {
            Snapshots.restore(zip, staging);
        } else {
            unzip(zip, staging);
        }
        Path aside = null;
        if (Files.exists(target)) {
            aside = gameDir.resolve("modkeel").resolve("replaced").resolve(world + "-" + stamp());
            Files.createDirectories(aside.getParent());
            Files.move(target, aside);
        }
        Files.move(staging, target);
        return aside;
    }

    static void unzip(Path zip, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("bad entry " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (OutputStream o = Files.newOutputStream(out)) {
                    in.transferTo(o);
                }
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
