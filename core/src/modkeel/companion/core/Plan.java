package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * File operations on the mods folder that must wait until the game has exited (Windows keeps
 * loaded jars locked). Stored as one "op<TAB>from<TAB>to" line each and run by {@link Apply}.
 */
public final class Plan {
    public static final class Op {
        public final String kind;
        public final Path from;
        public final Path to;

        Op(String kind, Path from, Path to) {
            this.kind = kind;
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return kind + " " + from.getFileName() + " -> " + to.getFileName();
        }
    }

    public final List<Op> ops = new ArrayList<>();
    /** Shown to the player and stored as the last action. */
    public String title = "";

    public Plan move(Path from, Path to) {
        ops.add(new Op("move", from, to));
        return this;
    }

    public Plan copy(Path from, Path to) {
        ops.add(new Op("copy", from, to));
        return this;
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    public void write(Path file) throws IOException {
        StringBuilder sb = new StringBuilder("#").append(title.replace('\n', ' ')).append('\n');
        for (Op op : ops) {
            sb.append(op.kind).append('\t').append(op.from.toAbsolutePath()).append('\t')
              .append(op.to.toAbsolutePath()).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Plan read(Path file) throws IOException {
        Plan p = new Plan();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                p.title = line.substring(1);
                continue;
            }
            String[] f = line.split("\t");
            if (f.length == 3) {
                p.ops.add(new Op(f[0], Paths.get(f[1]), Paths.get(f[2])));
            }
        }
        return p;
    }

    /** "x.jar" to "x.jar.disabled", or "x.jar.2.disabled" when that name is taken. */
    public static Path disabledName(Path jar) {
        Path p = jar.resolveSibling(jar.getFileName() + ".disabled");
        for (int i = 2; Files.exists(p); i++) {
            p = jar.resolveSibling(jar.getFileName() + "." + i + ".disabled");
        }
        return p;
    }
}
