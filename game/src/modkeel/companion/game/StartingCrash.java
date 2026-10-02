package modkeel.companion.game;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import modkeel.companion.core.Diagnosis;
import modkeel.companion.core.Guardian;
import modkeel.companion.core.Log;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/**
 * A crash that stopped the last start before the game finished loading will likely stop this
 * one too, before any screen can show. So the fix is offered at once, in a native dialog
 * (LWJGL's tinyfd, shipped with every Minecraft version), while the game is still starting.
 */
final class StartingCrash {
    private StartingCrash() {
    }

    static void offer() {
        Guardian g = Common.guardian;
        Diagnosis d = g.crash;
        if (!g.crashedStarting || d == null || d.top() == null || d.top().file == null
                || d.confidence == Diagnosis.Confidence.LOW
                || d.confidence == Diagnosis.Confidence.NONE) {
            return;
        }
        Diagnosis.Suspect s = d.top();
        // tests answer with -Dmodkeel.test.starting=yes|no instead of the dialog
        String answer = System.getProperty("modkeel.test.starting");
        boolean yes;
        if (answer != null) {
            yes = answer.equals("yes");
        } else {
            try {
                JsonObject lang = lang(g.gameDir);
                String text = String.format(text(lang, "modkeel.starting.text"), s.name);
                // tinyfd drops quotes on some systems
                yes = ask(text(lang, "modkeel.starting.title"),
                        text.replace("\"", "").replace("'", "’"));
            } catch (Throwable e) {
                Log.warn("cannot show the starting crash dialog", e);
                return;
            }
        }
        Log.info("starting crash: offered to disable " + s.id + ", "
                 + (yes ? "accepted" : "declined"));
        if (!yes) {
            return;
        }
        g.applyCrashFix(g.disablePlan(s), Common.selfJar());
        Log.info("closing the game so the change takes effect on the next start");
        System.exit(0);
    }

    /** A yes/no question; LWJGL 3.4 (Minecraft 26) answers with an int, older ones a boolean. */
    private static boolean ask(String title, String text) throws Exception {
        for (Method m : TinyFileDialogs.class.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("tinyfd_messageBox") || p.length != 5
                    || p[0] != CharSequence.class) {
                continue;
            }
            Object yes = p[4] == boolean.class ? (Object) true : (Object) 1;
            Object r = m.invoke(null, title, text, "yesno", "question", yes);
            return r instanceof Boolean ? (Boolean) r : ((Number) r).intValue() == 1;
        }
        throw new NoSuchMethodException("tinyfd_messageBox");
    }

    /** The player's language from options.txt; resources are not loaded this early. */
    private static JsonObject lang(Path gameDir) {
        String code = "en_us";
        try {
            for (String line : Files.readAllLines(gameDir.resolve("options.txt"), StandardCharsets.UTF_8)) {
                if (line.startsWith("lang:")) {
                    code = line.substring(5).trim().toLowerCase();
                }
            }
        } catch (Exception e) {
            // no options.txt yet: English
        }
        JsonObject out = read("en_us");
        if (!code.equals("en_us")) {
            JsonObject own = read(code);
            own.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static JsonObject read(String code) {
        try (InputStream in = StartingCrash.class.getResourceAsStream(
                "/assets/modkeel/lang/" + code + ".json")) {
            if (in == null) {
                return new JsonObject();
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String text(JsonObject lang, String key) {
        return lang.has(key) ? lang.get(key).getAsString() : key;
    }
}
