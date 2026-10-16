package modkeel.companion.core;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Incremental world backups, chunk by chunk. Each backup is a small manifest
 * ({@code <stamp>.snap}) beside the zips of older versions; the data lives in pack files
 * under {@code store/}, one per backup, holding only what changed since the one before:
 * whole files, and for region files ({@code r.X.Z.mca}) single chunks. A chunk whose slot,
 * save time, length and CRC match the last backup is not stored again, so a backup after an
 * hour of play writes a few MB even for a world of many GB.
 *
 * <p>Region files are rebuilt on restore: same chunks and save times, sectors laid out anew.
 * A region file that does not parse (empty, truncated, bad offsets) is kept whole, as is.
 */
public final class Snapshots {
    public static final String EXT = ".snap";
    private static final String HEADER = "modkeel-snapshot 1";
    private static final int TREE_MAGIC = 0x4D4B5452; // "MKTR"
    private static final int SECTOR = 4096;
    private static final long MAX_REGION = 256L << 20;
    private static final Pattern REGION = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.mca");

    private Snapshots() {
    }

    /** One line of a manifest: a file, or a region file whose chunk list is a stored tree. */
    record Entry(char kind, long size, long mtime, long crc, String packs, String pack, long off,
                 long len, String path) {
        String line() {
            return kind + "\t" + size + "\t" + mtime + "\t" + crc + "\t" + packs + "\t" + pack + "\t"
                   + off + "\t" + len + "\t" + path;
        }

        static Entry parse(String line) {
            String[] p = line.split("\t", 9);
            return new Entry(p[0].charAt(0), Long.parseLong(p[1]), Long.parseLong(p[2]),
                    Long.parseLong(p[3]), p[4], p[5], Long.parseLong(p[6]), Long.parseLong(p[7]), p[8]);
        }
    }

    /** One chunk of a region file: its slot (0..1023), save time, and where its bytes are. */
    record Chunk(int slot, int time, int len, int crc, String pack, long off) {
    }

    public record Taken(Path snap, long written, long files, long reused, boolean full) {
    }

    /**
     * A backup under way: bytes of the world gone through out of its size. Written by the
     * thread taking the backup, read by any other (the loading screen draws it).
     */
    public static final class Progress {
        public final String world;
        public final long total;
        public final long started = System.currentTimeMillis();
        /** Test hook: the backup takes at least this long, so its progress can be seen. */
        private final long slowMs;
        private volatile long done;

        public Progress(String world, long total, long slowMs) {
            this.world = world;
            this.total = Math.max(1, total);
            this.slowMs = slowMs;
        }

        public long done() {
            return Math.min(done, total);
        }

        public int percent() {
            return (int) (done() * 100 / total);
        }

        /** Only the backup's own thread calls this. */
        void add(long bytes) {
            done += bytes;
            if (slowMs > 0) {
                long wait = started + slowMs * done() / total - System.currentTimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        /** "840 KB", "312 MB", "2.9 GB". */
        public static String size(long bytes) {
            if (bytes < 1L << 20) {
                return Math.max(1, bytes >> 10) + " KB";
            }
            if (bytes < 1L << 30) {
                return (bytes >> 20) + " MB";
            }
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
    }

    public static boolean is(Path p) {
        return p.getFileName().toString().endsWith(EXT);
    }

    /**
     * Back up {@code world} into {@code dir} as {@code <stamp>.snap}, reusing what the newest
     * snapshot there already holds. {@code full} stores everything again (dead space bound).
     */
    public static Taken take(Path world, Path dir, String stamp, boolean full) throws IOException {
        return take(world, dir, stamp, full, null);
    }

    /** The same, adding each file's size to {@code progress} (may be null) once it is stored. */
    public static Taken take(Path world, Path dir, String stamp, boolean full, Progress progress)
            throws IOException {
        Files.createDirectories(dir.resolve("store"));
        clean(dir);
        Map<String, Entry> prev = new HashMap<>();
        Path last = full ? null : newest(dir);
        if (last != null) {
            for (Entry e : read(last)) {
                prev.put(e.path, e);
            }
        }
        Path packFile = dir.resolve("store").resolve(stamp + ".pack");
        Path packPart = packFile.resolveSibling(packFile.getFileName() + ".part");
        List<String> lines = new ArrayList<>();
        long[] stats = new long[2]; // files, reused
        try (Reader packs = new Reader(dir);
             FileChannel out = FileChannel.open(packPart, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            Files.walkFileTree(world, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs)
                        throws IOException {
                    if (!d.equals(world)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                            if (!ds.iterator().hasNext()) {
                                lines.add("d\t" + rel(world, d));
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = rel(world, file);
                    if (rel.equals("session.lock")) {
                        return FileVisitResult.CONTINUE;
                    }
                    stats[0]++;
                    long size = attrs.size();
                    long mtime = attrs.lastModifiedTime().toMillis();
                    Entry old = prev.get(rel);
                    if (old != null && old.size == size && old.mtime == mtime) {
                        lines.add(old.line());
                        stats[1]++;
                        tick(size);
                        return FileVisitResult.CONTINUE;
                    }
                    Entry e = null;
                    if (REGION.matcher(file.getFileName().toString()).matches() && size <= MAX_REGION) {
                        e = region(file, rel, size, mtime, old, packs, out, stamp);
                    }
                    if (e == null) {
                        e = whole(file, rel, size, mtime, old, out, stamp);
                    }
                    if (old != null && e.pack.equals(old.pack) && e.off == old.off) {
                        stats[1]++;
                    }
                    lines.add(e.line());
                    tick(size);
                    return FileVisitResult.CONTINUE;
                }

                private void tick(long size) {
                    if (progress != null) {
                        progress.add(size);
                    }
                }
            });
            out.force(true);
        }
        long written = Files.size(packPart);
        if (written == 0) {
            Files.delete(packPart);
        } else {
            Files.move(packPart, packFile, StandardCopyOption.REPLACE_EXISTING);
        }
        Path snap = dir.resolve(stamp + EXT);
        Path part = snap.resolveSibling(snap.getFileName() + ".part");
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(part)),
                StandardCharsets.UTF_8)) {
            w.write(HEADER + "\n");
            for (String l : lines) {
                w.write(l + "\n");
            }
        }
        Files.move(part, snap, StandardCopyOption.REPLACE_EXISTING);
        return new Taken(snap, written, stats[0], stats[1], full);
    }

    /** A region file as its chunks; null when it does not parse (then it is kept whole). */
    private static Entry region(Path file, String rel, long size, long mtime, Entry old,
                                Reader packs, FileChannel out, String stamp) throws IOException {
        byte[] b = Files.readAllBytes(file);
        List<int[]> slots = parseRegion(b);
        if (slots == null) {
            return null;
        }
        Map<Integer, Chunk> before = new HashMap<>();
        if (old != null && old.kind == 'r') {
            for (Chunk c : readTree(packs.bytes(old.pack, old.off, old.len))) {
                before.put(c.slot, c);
            }
        }
        List<Chunk> chunks = new ArrayList<>();
        for (int[] s : slots) {
            int slot = s[0];
            int time = s[1];
            int start = s[2];
            int len = s[3];
            CRC32 crc = new CRC32();
            crc.update(b, start, len);
            int c = (int) crc.getValue();
            Chunk was = before.get(slot);
            if (was != null && was.time == time && was.len == len && was.crc == c) {
                chunks.add(was);
            } else {
                long off = out.position();
                write(out, ByteBuffer.wrap(b, start, len));
                chunks.add(new Chunk(slot, time, len, c, stamp, off));
            }
        }
        byte[] tree = tree(chunks);
        Set<String> used = new TreeSet<>();
        chunks.forEach(c -> used.add(c.pack));
        long off = out.position();
        write(out, ByteBuffer.wrap(tree));
        used.add(stamp);
        return new Entry('r', size, mtime, 0, String.join(",", used), stamp, off, tree.length, rel);
    }

    /** A whole file; when it matches the last backup's copy byte for byte, that copy is kept. */
    private static Entry whole(Path file, String rel, long size, long mtime, Entry old,
                               FileChannel out, String stamp) throws IOException {
        long off = out.position();
        CRC32 crc = new CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
                write(out, ByteBuffer.wrap(buf, 0, n));
            }
        }
        long len = out.position() - off;
        if (old != null && old.kind == 'f' && old.len == len && old.crc == crc.getValue()) {
            out.truncate(off);
            out.position(off);
            return new Entry('f', size, mtime, old.crc, old.pack, old.pack, old.off, old.len, rel);
        }
        return new Entry('f', size, mtime, crc.getValue(), stamp, stamp, off, len, rel);
    }

    /**
     * The chunks of a region file as {slot, save time, data start, data length} (the data
     * being the compression byte and the payload), or null when the file is not a sound one.
     */
    static List<int[]> parseRegion(byte[] b) {
        if (b.length < 2 * SECTOR) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(b);
        List<int[]> out = new ArrayList<>();
        for (int slot = 0; slot < 1024; slot++) {
            int loc = buf.getInt(slot * 4);
            if (loc == 0) {
                continue;
            }
            long sector = loc >>> 8;
            int count = loc & 0xFF;
            long at = sector * SECTOR;
            if (sector < 2 || count == 0 || at + 5 > b.length) {
                return null;
            }
            int len = buf.getInt((int) at);
            if (len < 1 || len + 4L > (long) count * SECTOR || at + 4 + len > b.length) {
                return null;
            }
            out.add(new int[] {slot, buf.getInt(SECTOR + slot * 4), (int) at + 4, len});
        }
        return out;
    }

    private static byte[] tree(List<Chunk> chunks) throws IOException {
        List<String> packs = new ArrayList<>(new LinkedHashSet<>(chunks.stream().map(Chunk::pack).toList()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(bytes))) {
            d.writeInt(TREE_MAGIC);
            d.writeShort(packs.size());
            for (String p : packs) {
                d.writeUTF(p);
            }
            d.writeShort(chunks.size());
            for (Chunk c : chunks) {
                d.writeShort(c.slot);
                d.writeInt(c.time);
                d.writeInt(c.len);
                d.writeInt(c.crc);
                d.writeShort(packs.indexOf(c.pack));
                d.writeLong(c.off);
            }
        }
        return bytes.toByteArray();
    }

    static List<Chunk> readTree(byte[] tree) throws IOException {
        try (DataInputStream d = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(tree)))) {
            if (d.readInt() != TREE_MAGIC) {
                throw new IOException("not a chunk list");
            }
            String[] packs = new String[d.readUnsignedShort()];
            for (int i = 0; i < packs.length; i++) {
                packs[i] = d.readUTF();
            }
            int n = d.readUnsignedShort();
            List<Chunk> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(new Chunk(d.readUnsignedShort(), d.readInt(), d.readInt(), d.readInt(),
                        packs[d.readUnsignedShort()], d.readLong()));
            }
            return out;
        }
    }

    /** Rebuild the world of {@code snap} into {@code dest} (which must not exist yet). */
    public static void restore(Path snap, Path dest) throws IOException {
        Path root = dest.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (Reader packs = new Reader(snap.getParent())) {
            for (Entry e : read(snap)) {
                Path out = root.resolve(e.path).normalize();
                if (!out.startsWith(root) || out.equals(root)) {
                    throw new IOException("bad path " + e.path);
                }
                if (e.kind == 'd') {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                if (e.kind == 'f') {
                    try (FileChannel w = FileChannel.open(out, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                        packs.copy(e.pack, e.off, e.len, w);
                    }
                } else {
                    writeRegion(out, readTree(packs.bytes(e.pack, e.off, e.len)), packs);
                }
                Files.setLastModifiedTime(out, FileTime.fromMillis(e.mtime));
            }
        }
    }

    private static void writeRegion(Path out, List<Chunk> chunks, Reader packs) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(2 * SECTOR);
        try (FileChannel w = FileChannel.open(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long sector = 2;
            w.position(2L * SECTOR);
            for (Chunk c : chunks) {
                int count = (c.len + 4 + SECTOR - 1) / SECTOR;
                head.putInt(c.slot * 4, (int) (sector << 8) | count);
                head.putInt(SECTOR + c.slot * 4, c.time);
                w.position(sector * SECTOR);
                ByteBuffer len = ByteBuffer.allocate(4).putInt(0, c.len);
                write(w, len);
                packs.copy(c.pack, c.off, c.len, w);
                int pad = count * SECTOR - c.len - 4;
                write(w, ByteBuffer.allocate(pad));
                sector += count;
            }
            w.position(0);
            write(w, head);
        }
    }

    /** The manifest's entries, in world order. */
    static List<Entry> read(Path snap) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(snap)), StandardCharsets.UTF_8))) {
            if (!HEADER.equals(r.readLine())) {
                throw new IOException("not a Modkeel snapshot: " + snap);
            }
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("d\t")) {
                    out.add(new Entry('d', 0, 0, 0, "-", "-", 0, 0, l.substring(2)));
                } else if (!l.isEmpty()) {
                    out.add(Entry.parse(l));
                }
            }
        }
        return out;
    }

    /** Packs no remaining snapshot uses are deleted; returns the bytes freed. */
    public static long gc(Path dir) {
        Set<String> live = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path snap : ds) {
                for (Entry e : read(snap)) {
                    if (e.kind != 'd') {
                        live.add(e.pack);
                        live.addAll(List.of(e.packs.split(",")));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("cannot read the backups of " + dir + "; nothing deleted", e);
            return 0;
        }
        long freed = 0;
        Path store = dir.resolve("store");
        if (Files.isDirectory(store)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(store, "*.pack")) {
                for (Path p : ds) {
                    if (!live.contains(p.getFileName().toString().replaceAll("\\.pack$", ""))) {
                        freed += Files.size(p);
                        Files.delete(p);
                    }
                }
            } catch (IOException e) {
                Log.warn("cannot clean " + store, e);
            }
        }
        return freed;
    }

    /** Bytes the store holds, live or not. */
    public static long storeSize(Path dir) {
        Path store = dir.resolve("store");
        try {
            return Files.isDirectory(store) ? Backups.size(store) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** Leftovers of a backup cut short (the game killed mid-copy). */
    private static void clean(Path dir) throws IOException {
        for (Path d : new Path[] {dir, dir.resolve("store")}) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.part")) {
                for (Path p : ds) {
                    Files.delete(p);
                }
            }
        }
    }

    private static Path newest(Path dir) throws IOException {
        Path best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + EXT)) {
            for (Path p : ds) {
                if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) > 0) {
                    best = p;
                }
            }
        }
        return best;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static void write(FileChannel ch, ByteBuffer b) throws IOException {
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }

    /** Open packs, by name, for reading. */
    private static final class Reader implements AutoCloseable {
        private final Path store;
        private final Map<String, FileChannel> open = new LinkedHashMap<>();

        Reader(Path dir) {
            this.store = dir.resolve("store");
        }

        private FileChannel of(String pack) throws IOException {
            FileChannel ch = open.get(pack);
            if (ch == null) {
                ch = FileChannel.open(store.resolve(pack + ".pack"), StandardOpenOption.READ);
                open.put(pack, ch);
            }
            return ch;
        }

        byte[] bytes(String pack, long off, long len) throws IOException {
            ByteBuffer b = ByteBuffer.allocate((int) len);
            FileChannel ch = of(pack);
            while (b.hasRemaining()) {
                if (ch.read(b, off + b.position()) < 0) {
                    throw new IOException("pack " + pack + " is cut short");
                }
            }
            return b.array();
        }

        void copy(String pack, long off, long len, FileChannel to) throws IOException {
            FileChannel ch = of(pack);
            long done = 0;
            while (done < len) {
                long n = ch.transferTo(off + done, len - done, to);
                if (n <= 0) {
                    throw new IOException("pack " + pack + " is cut short");
                }
                done += n;
            }
        }

        @Override
        public void close() throws IOException {
            for (FileChannel ch : open.values()) {
                ch.close();
            }
        }
    }
}
