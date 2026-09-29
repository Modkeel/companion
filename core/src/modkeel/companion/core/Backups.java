package modkeel.companion.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** World backups as zip files under {@code <game>/modkeel/backups/<world>/}. */
public final class Backups {
    private Backups() {
    }

    public static Path dir(Path gameDir, String world) {
        return gameDir.resolve("modkeel").resolve("backups").resolve(world);
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
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zip")) {
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
        Path dir = gameDir.resolve("modkeel").resolve("backups");
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

    public static void prune(Path gameDir, String world, int keep) {
        List<Path> all = list(gameDir, world);
        for (int i = keep; i < all.size(); i++) {
            try {
                Files.delete(all.get(i));
            } catch (IOException e) {
                Log.warn("cannot delete " + all.get(i), e);
            }
        }
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
        unzip(zip, staging);
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
