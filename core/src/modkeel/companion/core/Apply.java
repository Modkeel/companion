package modkeel.companion.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Helper process: waits for the game to exit, then runs a {@link Plan}.
 * {@code java -cp modkeel.jar modkeel.companion.core.Apply <pid> <plan> <result>}
 */
public final class Apply {
    private Apply() {
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path planFile = Paths.get(args[1]);
        Path resultFile = Paths.get(args[2]);
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            // the game exits by itself right after starting us; never wait forever
            try {
                game.get().onExit().get(10, java.util.concurrent.TimeUnit.MINUTES);
            } catch (java.util.concurrent.TimeoutException e) {
                write(resultFile, List.of("#" + Plan.read(planFile).title, "fail game still running"));
                return;
            }
        }
        List<String> result = run(Plan.read(planFile));
        write(resultFile, result);
        Files.deleteIfExists(planFile);
    }

    /** Runs every op with retries; returns one "ok ..." or "fail ..." line per op. */
    public static List<String> run(Plan plan) {
        List<String> out = new ArrayList<>();
        out.add("#" + plan.title);
        for (Plan.Op op : plan.ops) {
            IOException last = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    Files.createDirectories(op.to.getParent());
                    if (op.kind.equals("move")) {
                        Files.move(op.from, op.to);
                    } else {
                        Files.copy(op.from, op.to, StandardCopyOption.REPLACE_EXISTING);
                    }
                    last = null;
                    break;
                } catch (IOException e) {
                    last = e;
                    // a jar can stay locked for a moment after the game exits
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            out.add((last == null ? "ok " : "fail ") + op + (last == null ? "" : " (" + last + ")"));
        }
        return out;
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
