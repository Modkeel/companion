package modkeel.companion.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anonymous reports to the Modkeel API (docs/reports-api.md). Nothing is built or sent without
 * the player's click. A report goes to {@code <home>/outbox} first and leaves it after the
 * server took it, so a closed game or no network loses nothing; sent ones are kept in
 * {@code <home>/sent} for the player to read.
 */
public final class Reports {
    public static final String DEFAULT_API = "https://api.modkeel.com";
    private static final long RETRY_MS = 10 * 60_000;
    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int KEEP_SENT = 50;
    /** Room for the mods list: the server takes at most 64 KB. */
    private static final int MAX_BYTES = 60 * 1024;
    private static final int MAX_MODS = 2000;

    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_.\\-]{1,64}");
    private static final Pattern FRAME = Pattern.compile("[A-Za-z0-9_.$<>]{1,200}");
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern INSTALL_FIELD = Pattern.compile("\"install\":\"[0-9a-f]*\"");
    private static final Pattern INSTALL_ANSWER = Pattern.compile("\"install\"\\s*:\\s*\"([0-9a-f]{32})\"");
    private static final Pattern REPORT_ANSWER = Pattern.compile("\"answer\":\\{\"id\":\"([0-9a-f]{24})\"");

    /** A fix as the server counts it: a stable title and the mod ids it turned off. */
    public static final class Fix {
        public final String title;
        public final List<String> disabled;

        public Fix(String title, List<String> disabled) {
            this.title = title;
            this.disabled = disabled;
        }
    }

    private final Guardian g;
    /** Base URL; empty turns sending off. */
    public final String api;
    public final Path outbox;
    public final Path sent;
    private final Path tokenFile;
    private final AtomicBoolean sending = new AtomicBoolean();
    private HttpClient http;
    /** Run after a send pass that delivered something: answers can unlock new reports. */
    public volatile Runnable afterSend;

    public Reports(Guardian g) {
        this(g, System.getProperty("modkeel.api", DEFAULT_API));
    }

    public Reports(Guardian g, String api) {
        this.g = g;
        this.api = api.replaceAll("/+$", "");
        this.outbox = g.home.resolve("outbox");
        this.sent = g.home.resolve("sent");
        this.tokenFile = g.home.resolve("install.txt");
    }

    public boolean enabled() {
        return !api.isEmpty();
    }

    // ---- payloads ------------------------------------------------------------------------

    public Fix disableFix(Diagnosis.Suspect s) {
        return new Fix("disable:" + s.id, List.of(s.id));
    }

    public Fix revertFix() {
        List<String> ids = new ArrayList<>();
        for (ModSet.Jar j : g.changedSinceGood()) {
            for (JarInfo info : g.owners().all) {
                if (info.bundledIn == null && info.declared && info.file.equals(j.file)
                        && modId(info.id) != null && ids.size() < 20) {
                    ids.add(modId(info.id));
                }
            }
        }
        return new Fix("revert", ids);
    }

    /** The crash report as sent, with an empty install token until the send fills it in. */
    public String crash(Diagnosis d, Fix fix) {
        StringBuilder head = new StringBuilder("{\"v\":1,\"install\":\"\",\"env\":").append(env());
        StringBuilder tail = new StringBuilder();
        tail.append(",\"signature\":").append(q(d.signature));
        tail.append(",\"kind\":").append(q(d.kind.name()));
        tail.append(",\"exception\":").append(q(FRAME.matcher(d.exception).matches() ? d.exception : "unknown"));
        List<String> frames = new ArrayList<>();
        for (String f : d.frames) {
            if (FRAME.matcher(f).matches()) {
                frames.add(f);
            }
        }
        tail.append(",\"frames\":").append(list(frames));
        List<String> suspects = new ArrayList<>();
        for (Diagnosis.Suspect s : d.suspects) {
            String id = modId(s.id);
            if (id != null && suspects.size() < 10) {
                suspects.add(id);
            }
        }
        tail.append(",\"suspects\":").append(list(suspects));
        tail.append(",\"fix\":").append(fix == null ? "null" : "{\"title\":" + q(fix.title)
                + ",\"disabled\":" + list(fix.disabled) + "}");
        tail.append('}');
        head.append(",\"mods\":").append(mods(MAX_BYTES - head.length() - tail.length()));
        return head.append(tail).toString();
    }

    /** How a shared fix went ({@code status} as the API names it). */
    public String outcome(Outcomes.Fix f) {
        // activity null: nothing to judge by (no suspect left enabled, or play not measured)
        String activity = f.measured && !f.suspects.isEmpty() ? f.played.only(f.suspects).json() : "null";
        return "{\"v\":1,\"install\":\"\",\"report\":" + q(f.report) + ",\"status\":" + q(f.status.wire)
                + ",\"ticks\":" + Math.min(Math.max(f.ticks, 0), Integer.MAX_VALUE)
                + ",\"active_minutes\":" + Math.min(Activity.minutes(Math.min(f.active, f.ticks)), Integer.MAX_VALUE)
                + ",\"activity\":" + activity + "}";
    }

    /** The id the server gave a crash report sent from {@code outbox/<name>}, or null. */
    public String reportId(String name) {
        Path f = sent.resolve(name);
        try {
            if (Files.exists(f)) {
                Matcher m = REPORT_ANSWER.matcher(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                return m.find() ? m.group(1) : null;
            }
        } catch (IOException e) {
            Log.warn("cannot read " + f, e);
        }
        return null;
    }

    String env() {
        long ram = Math.min(Runtime.getRuntime().maxMemory() >> 20, 1 << 22);
        JarInfo self = g.owners().ofId("modkeel");
        String companion = self == null || self.version == null ? "0" : self.version;
        return "{\"mc\":" + q(g.mcVersion) + ",\"loader\":" + q(g.loader)
                + ",\"loader_version\":" + q(text(g.loaderVersion))
                + ",\"companion\":" + q(companion.matches("[0-9a-z.\\-+]{1,32}") ? companion : "0")
                + ",\"java\":" + Runtime.version().feature() + ",\"os\":" + q(os())
                + ",\"ram_mb\":" + ram + "}";
    }

    /**
     * Every declared mod, bundled ones after the top-level jars, while they fit in
     * {@code budget} bytes. File names stay out: they can hold a user name.
     */
    String mods(int budget) {
        Map<String, String> sha1ByFile = new HashMap<>();
        for (ModSet.Jar j : g.current().jars) {
            sha1ByFile.put(j.file, j.sha1);
        }
        Map<String, String> idByFile = new HashMap<>();
        for (JarInfo info : g.owners().all) {
            if (info.bundledIn == null && info.declared) {
                idByFile.putIfAbsent(info.file, modId(info.id));
            }
        }
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (JarInfo info : g.owners().all) {
            String id = modId(info.id);
            String sha1 = info.bundledIn == null ? sha1ByFile.get(info.file) : info.sha1;
            if (!info.declared || id == null || sha1 == null) {
                continue;
            }
            String in = info.bundledIn == null ? null : idByFile.get(info.bundledIn);
            String entry = "{\"id\":" + q(id) + ",\"version\":" + q(text(info.version))
                    + ",\"sha1\":" + q(sha1) + ",\"in\":" + (in == null ? "null" : q(in)) + "}";
            if (sb.length() + entry.length() + 2 > budget || n == MAX_MODS) {
                break;
            }
            sb.append(n++ == 0 ? "" : ",").append(entry);
        }
        return sb.append(']').toString();
    }

    static String modId(String id) {
        if (id == null) {
            return null;
        }
        String low = id.toLowerCase(Locale.ROOT);
        return MOD_ID.matcher(low).matches() ? low : null;
    }

    /** Printable ASCII, 1 to 64 characters, as the server takes free text. */
    private static String text(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        String t = s.replaceAll("[^\\x20-\\x7e]", "?");
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    private static String os() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? "windows" : os.contains("mac") ? "macos"
                : os.contains("linux") ? "linux" : "other";
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String list(List<String> items) {
        List<String> quoted = new ArrayList<>();
        for (String s : items) {
            quoted.add(q(s));
        }
        return "[" + String.join(",", quoted) + "]";
    }

    /** A payload for people: one field per line, the install token shown as it will be sent. */
    public String readable(String json) {
        String token = readToken();
        if (token != null) {
            json = INSTALL_FIELD.matcher(json).replaceFirst("\"install\":\"" + token + "\"");
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        int inline = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\') {
                    sb.append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    sb.append(c);
                    break;
                case '{':
                case '[':
                    // short objects (one mod) stay on one line
                    if (inline > i) {
                        sb.append(c);
                        break;
                    }
                    int close = json.indexOf(c == '{' ? '}' : ']', i);
                    if (close > 0 && close - i < 110 && json.substring(i + 1, close).indexOf(c) < 0) {
                        inline = close;
                        sb.append(c);
                        break;
                    }
                    depth++;
                    sb.append(c).append('\n').append("  ".repeat(depth));
                    break;
                case '}':
                case ']':
                    if (i <= inline) {
                        sb.append(c);
                        inline = i == inline ? -1 : inline;
                        break;
                    }
                    depth--;
                    sb.append('\n').append("  ".repeat(Math.max(depth, 0))).append(c);
                    break;
                case ',':
                    // spaces inside a short object, so a narrow screen can wrap it
                    sb.append(inline > i ? ", " : ",\n" + "  ".repeat(depth));
                    break;
                case ':':
                    sb.append(": ");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- outbox --------------------------------------------------------------------------

    /** Store a payload to send ({@code kind} names the endpoint: crash, outcome, session). */
    public Path queue(String kind, String json) {
        try {
            Files.createDirectories(outbox);
            long t = System.currentTimeMillis();
            Path f;
            while (Files.exists(f = outbox.resolve(t + "-" + kind + ".json"))) {
                t++;
            }
            Files.write(f, json.getBytes(StandardCharsets.UTF_8));
            return f;
        } catch (IOException e) {
            Log.warn("cannot queue a report", e);
            return null;
        }
    }

    public List<Path> pending() {
        return files(outbox);
    }

    /** Send in the background until the outbox is empty, retrying every 10 minutes. */
    public void sendLater() {
        if (!enabled() || pending().isEmpty() || !sending.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                // a pass that left nothing to retry goes again at once for reports queued meanwhile
                while (!pending().isEmpty()) {
                    if (!send()) {
                        Thread.sleep(RETRY_MS);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sending.set(false);
            }
        }, "modkeel-reports");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One pass over the outbox. A 2xx moves the report to {@code sent}; a rejected payload
     * (4xx) is dropped at once; a busy server or no network keeps the rest for later. Returns
     * true when nothing is left to retry.
     */
    public synchronized boolean send() {
        int ok = 0;
        int dropped = 0;
        int kept = 0;
        List<Path> files = pending();
        String token = null;
        for (Path f : files) {
            try {
                if (System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() > MAX_AGE_MS) {
                    Files.delete(f);
                    dropped++;
                    continue;
                }
                if (token == null) {
                    token = token();
                }
                String name = f.getFileName().toString();
                String kind = name.substring(name.indexOf('-') + 1, name.length() - ".json".length());
                String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                json = INSTALL_FIELD.matcher(json).replaceFirst("\"install\":\"" + token + "\"");
                HttpResponse<String> r = post("/v1/" + kind, json, name);
                int status = r.statusCode();
                if (status / 100 == 2) {
                    Files.createDirectories(sent);
                    Files.write(sent.resolve(name), ("{\"sent\":" + json + ",\"answer\":" + r.body() + "}")
                            .getBytes(StandardCharsets.UTF_8));
                    Files.delete(f);
                    ok++;
                } else if (status == 401) {
                    Files.deleteIfExists(tokenFile); // the server forgot this install: a new one next time
                    kept = files.size() - ok - dropped;
                    break;
                } else if (status == 429 || status >= 500) {
                    kept = files.size() - ok - dropped;
                    break;
                } else {
                    Log.info("report " + name + " refused (" + status + "): " + r.body());
                    Files.delete(f);
                    dropped++;
                }
            } catch (IOException e) {
                kept = files.size() - ok - dropped;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                kept = files.size() - ok - dropped;
                break;
            }
        }
        if (!files.isEmpty()) {
            Log.info("reports: " + ok + " sent, " + kept + " kept for later, " + dropped + " dropped");
        }
        Runnable after = afterSend;
        if (ok > 0 && after != null) {
            after.run();
        }
        prune();
        return kept == 0;
    }

    /** The install token, asked for once and kept in the home folder. */
    private String token() throws IOException, InterruptedException {
        String t = readToken();
        if (t != null) {
            return t;
        }
        HttpResponse<String> r = post("/v1/install", "", null);
        Matcher m = INSTALL_ANSWER.matcher(r.body());
        if (r.statusCode() != 200 || !m.find()) {
            throw new IOException("no install token (" + r.statusCode() + ")");
        }
        Files.createDirectories(tokenFile.getParent());
        Files.write(tokenFile, m.group(1).getBytes(StandardCharsets.UTF_8));
        return m.group(1);
    }

    private String readToken() {
        try {
            if (Files.exists(tokenFile)) {
                String t = new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
                return TOKEN.matcher(t).matches() ? t : null;
            }
        } catch (IOException e) {
            Log.warn("cannot read " + tokenFile, e);
        }
        return null;
    }

    /** {@code key} (null: none) names the report: a resend after a lost answer is stored once. */
    private HttpResponse<String> post(String path, String json, String key) throws IOException, InterruptedException {
        if (http == null) {
            http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(api + path))
                .timeout(Duration.ofSeconds(20))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (key != null) {
            req.header("idempotency-key", key);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void prune() {
        List<Path> done = files(sent);
        for (int i = 0; i < done.size() - KEEP_SENT; i++) {
            try {
                Files.delete(done.get(i));
            } catch (IOException e) {
                Log.warn("cannot delete " + done.get(i), e);
            }
        }
    }

    /** *.json in {@code dir}, oldest first (names start with their creation time). */
    private static List<Path> files(Path dir) {
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
                ds.forEach(out::add);
            } catch (IOException e) {
                Log.warn("cannot list " + dir, e);
            }
        }
        Collections.sort(out);
        return out;
    }
}
