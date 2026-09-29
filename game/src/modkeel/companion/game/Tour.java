package modkeel.companion.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import modkeel.companion.core.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Test driver for the screen tour (companion/tour.py), on only with -Dmodkeel.test.tour=FILE.
 *
 * FILE holds the buttons still to press, one per line, as a translation key ("gui.yes") or the
 * literal label ("Modkeel"); a leading "?" makes a step optional: skipped when this screen has no
 * such button (the welcome screen shows only on some starts). Each time a screen has been up for a moment, the driver logs
 * "tour: shot N", waits until tour.py writes N to FILE.ack (the capture is taken), then presses
 * the next button. A step is removed from FILE before it is pressed, so a fix that closes the
 * game resumes on the next launch with the steps after it.
 */
final class Tour {
    private static final long STABLE_MS = 1500;
    private static final long ACK_MS = 60_000;

    private Tour() {
    }

    static void start() {
        String file = System.getProperty("modkeel.test.tour");
        if (file == null) {
            return;
        }
        Thread t = new Thread(() -> run(Path.of(file)), "modkeel-tour");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Path file) {
        Minecraft mc = Minecraft.getInstance();
        Path ack = file.resolveSibling(file.getFileName() + ".ack");
        Screen handled = null;
        Screen seen = null;
        long since = 0;
        int shot = 0;
        while (true) {
            sleep(250);
            Screen s;
            try {
                s = Compat.screen(mc);
            } catch (RuntimeException e) {
                continue; // the client is still starting (no gui yet)
            }
            if (s != seen) {
                seen = s;
                since = System.currentTimeMillis();
                continue;
            }
            if (Compat.loading(mc)) {
                since = System.currentTimeMillis();
                continue;
            }
            if (s == null || s == handled || System.currentTimeMillis() - since < STABLE_MS) {
                continue;
            }
            handled = s;
            shot++;
            Log.info("tour: shot " + shot + " " + s.getClass().getSimpleName() + " | "
                    + s.getTitle().getString().replace('\n', ' '));
            if (!waitAck(ack, shot)) {
                Log.info("tour: no capture ack, stopping");
                return;
            }
            List<String> steps = read(file);
            AbstractButton b = null;
            String step = null;
            while (b == null && !steps.isEmpty()) {
                step = steps.remove(0);
                boolean optional = step.startsWith("?");
                step = optional ? step.substring(1) : step;
                b = find(s, step);
                if (b == null && !optional) {
                    Log.info("tour: missing " + step + " on " + s.getClass().getSimpleName());
                    return;
                }
            }
            if (b == null) {
                write(file, steps);
                Log.info("tour: done");
                return;
            }
            write(file, steps);
            Log.info("tour: press " + step + " | " + b.getMessage().getString());
            AbstractButton button = b;
            mc.execute(() -> Compat.press(button));
        }
    }

    /** The first active button whose label is `step`, searching nested containers too. */
    private static AbstractButton find(ContainerEventHandler parent, String step) {
        for (GuiEventListener e : new ArrayList<>(parent.children())) {
            if (e instanceof AbstractButton b && b.active && step.equals(key(b.getMessage()))) {
                return b;
            }
            if (e instanceof ContainerEventHandler c) {
                AbstractButton inner = find(c, step);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static String key(Component c) {
        return c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString();
    }

    private static boolean waitAck(Path ack, int shot) {
        long end = System.currentTimeMillis() + ACK_MS;
        while (System.currentTimeMillis() < end) {
            try {
                if (Files.exists(ack) && Files.readString(ack).trim().equals(String.valueOf(shot))) {
                    return true;
                }
            } catch (IOException ignored) {
                // tour.py is writing it: read again
            }
            sleep(200);
        }
        return false;
    }

    private static List<String> read(Path file) {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(line.trim());
                }
            }
        } catch (IOException e) {
            Log.warn("tour: cannot read " + file, e);
        }
        return out;
    }

    private static void write(Path file, List<String> steps) {
        try {
            Files.write(file, steps, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("tour: cannot write " + file, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
