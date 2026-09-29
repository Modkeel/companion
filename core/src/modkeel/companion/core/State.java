package modkeel.companion.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Small persistent state in {@code <game>/modkeel/state.properties}. */
public final class State {
    private final Path file;
    private final Properties props = new Properties();

    private State(Path file) {
        this.file = file;
    }

    public static State load(Path gameDir) {
        State s = new State(gameDir.resolve("modkeel").resolve("state.properties"));
        if (Files.exists(s.file)) {
            try (Reader r = Files.newBufferedReader(s.file, StandardCharsets.UTF_8)) {
                s.props.load(r);
            } catch (IOException e) {
                Log.warn("cannot read " + s.file, e);
            }
        }
        return s;
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, "Modkeel companion state");
            }
        } catch (IOException e) {
            Log.warn("cannot write " + file, e);
        }
    }

    public String get(String key, String fallback) {
        return props.getProperty(key, fallback);
    }

    public long getLong(String key, long fallback) {
        try {
            return Long.parseLong(props.getProperty(key, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void set(String key, Object value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value.toString());
        }
    }

    /** A list stored as one "|"-separated value. */
    public List<String> getList(String key) {
        String v = props.getProperty(key, "");
        return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(v.split("\\|")));
    }

    public void setList(String key, List<String> values) {
        set(key, values.isEmpty() ? null : String.join("|", values));
    }
}
