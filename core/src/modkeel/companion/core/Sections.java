package modkeel.companion.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which part of Minecraft a stack is in (entities, chunks, rendering...) and which resource it
 * waits on (the graphics card, the disk), for the lag spike breakdown. The table is
 * {@code modkeel/sections.txt}; Fabric before 26.1 adds {@code modkeel/classnames.tsv}, its
 * intermediary class names already resolved to a section.
 */
public final class Sections {
    /** What a busy sample was doing; WAIT is blocked inside the game, on its worker threads. */
    public enum Resource { CPU, GPU, DISK, WAIT }

    private static final class Row {
        final String prefix;
        final String section;
        final Set<String> refines;

        Row(String prefix, String section, Set<String> refines) {
            this.prefix = prefix;
            this.section = section;
            this.refines = refines;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Map<String, Set<String>> refines = new HashMap<>();
    /** Runtime class name -> section, for obfuscated names. */
    private final Map<String, String> direct = new HashMap<>();
    /** Sampler thread only. */
    private final Map<String, String> cache = new HashMap<>();
    private static final String NONE = "";

    public static Sections load() {
        Sections s = new Sections();
        s.read(lines("/modkeel/sections.txt"), lines("/modkeel/classnames.tsv"));
        return s;
    }

    public static Sections parse(List<String> table, List<String> classNames) {
        Sections s = new Sections();
        s.read(table, classNames);
        return s;
    }

    private void read(List<String> table, List<String> classNames) {
        for (String line : table) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t");
            Set<String> outer = p.length > 2 ? new HashSet<>(Arrays.asList(p[2].split(",")))
                                             : new HashSet<>();
            rows.add(new Row(p[0], p[1], outer));
            refines.computeIfAbsent(p[1], k -> new HashSet<>()).addAll(outer);
        }
        // the longest prefix wins
        rows.sort((a, b) -> b.prefix.length() - a.prefix.length());
        for (String line : classNames) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                direct.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
    }

    private static List<String> lines(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = Sections.class.getResourceAsStream(resource)) {
            if (in == null) {
                return out;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                out.add(line);
            }
        } catch (IOException e) {
            Log.warn("cannot read " + resource, e);
        }
        return out;
    }

    /** The section of one class, or null. */
    String ofClass(String className) {
        int inner = className.indexOf('$');
        String outer = inner < 0 ? className : className.substring(0, inner);
        String s = cache.get(outer);
        if (s == null) {
            s = direct.get(outer);
            if (s == null) {
                s = NONE;
                for (Row r : rows) {
                    if (outer.startsWith(r.prefix)) {
                        s = r.section;
                        break;
                    }
                }
            }
            cache.put(outer, s);
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * The part of Minecraft this stack is in, read from the outside in: the outermost section
     * frame, replaced by a deeper one only when that one refines it. Null when none matches.
     */
    public String of(StackTraceElement[] stack) {
        String current = null;
        for (int i = stack.length - 1; i >= 0; i--) {
            String s = ofClass(stack[i].getClassName());
            if (s == null || s.equals(current)) {
                continue;
            }
            if (current == null || refines.getOrDefault(s, Set.of()).contains(current)) {
                current = s;
            }
        }
        return current;
    }

    /**
     * What the innermost frames wait on: native graphics calls (a GPU or driver stall), file
     * I/O (the disk), or neither (the processor).
     */
    public static Resource resource(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (c.startsWith("org.lwjgl.opengl.") || c.startsWith("org.lwjgl.glfw.")
                    || c.startsWith("org.lwjgl.vulkan.")) {
                return Resource.GPU;
            }
            if (c.startsWith("sun.nio.ch.FileChannelImpl") || c.startsWith("sun.nio.ch.FileDispatcher")
                    || c.startsWith("java.io.RandomAccessFile") || c.startsWith("java.io.FileOutputStream")
                    || c.startsWith("java.io.FileInputStream") || c.startsWith("sun.nio.fs.")
                    || c.startsWith("java.io.WinNTFileSystem") || c.startsWith("java.io.UnixFileSystem")) {
                return Resource.DISK;
            }
            // only the platform's own frames on top can be a wait; game code means the processor
            if (!(c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")
                    || c.startsWith("com.sun.") || c.startsWith("org.lwjgl."))) {
                return Resource.CPU;
            }
        }
        return Resource.CPU;
    }
}
