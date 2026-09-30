package modkeel.companion.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lag spikes and the mods behind them, with no mixins. A game thread beats once per tick; a
 * watchdog thread notices when the beats stop, samples that thread's stack until they resume,
 * and names the mod whose code the thread was running. Samples no mod owns are split by the
 * part of Minecraft they are in ({@link Sections}); every busy sample also says what it waited
 * on (the processor, the graphics card, the disk, the game's worker threads).
 */
public final class Spikes {
    /** Which thread stalled: the render thread (the picture froze) or the server thread (the world stopped). */
    public enum Where { FRAME, WORLD }

    /** Id of the samples that no mod owns. */
    public static final String VANILLA = "minecraft";

    /**
     * One mod's share of a spike. For {@link #VANILLA}, {@code name} is null and {@code section}
     * is the part of Minecraft ("entities"), or null when no part matched.
     */
    public static final class Share {
        public final String id;
        public final String name;
        public final String section;
        public final int percent;

        Share(String id, String name, String section, int percent) {
            this.id = id;
            this.name = name;
            this.section = section;
            this.percent = percent;
        }

        String key() {
            return section == null ? id : id + ":" + section;
        }
    }

    /** How many entities of one type the world held right after a spike. */
    public static final class Crowd {
        /** The entity type's translation key. */
        public final String type;
        public final int count;

        Crowd(String type, int count) {
            this.type = type;
            this.count = count;
        }

        @Override
        public String toString() {
            return count + " " + type;
        }
    }

    public static final class Spike {
        public final Where where;
        /** When it ended, epoch ms. */
        public final long at;
        public final long millis;
        /** Share of the spike spent in garbage collection, 0-100. */
        public final int gcPercent;
        /** Mods by share, largest first, each at least {@link #MIN_SHARE} percent. */
        public final List<Share> shares;
        /** Share of the busy samples waiting on the graphics card or driver, 0-100. */
        public final int gpuPercent;
        /** Share of the busy samples waiting on the disk, 0-100. */
        public final int diskPercent;
        /** Share of the busy samples blocked on the game's worker threads (chunks), 0-100. */
        public final int waitPercent;
        /** The processor was nearly full and mostly with other programs. */
        public final boolean otherPrograms;
        /**
         * The most common entity types in the world right after a world spike, largest first;
         * filled a moment after the spike, from the server thread.
         */
        public volatile List<Crowd> crowds = List.of();

        Spike(Where where, long at, long millis, int gcPercent, List<Share> shares, int gpuPercent,
              int diskPercent, int waitPercent, boolean otherPrograms) {
            this.where = where;
            this.at = at;
            this.millis = millis;
            this.gcPercent = gcPercent;
            this.shares = shares;
            this.gpuPercent = gpuPercent;
            this.diskPercent = diskPercent;
            this.waitPercent = waitPercent;
            this.otherPrograms = otherPrograms;
        }

        /** Keeps the biggest crowds from a count of entities per type. */
        public void count(Map<String, Integer> perType) {
            crowds = perType.entrySet().stream()
                    .filter(e -> e.getValue() >= CROWD)
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(CROWDS)
                    .map(e -> new Crowd(e.getKey(), e.getValue()))
                    .collect(Collectors.toList());
            if (!crowds.isEmpty()) {
                Log.info("lag spike entities: " + crowds);
            }
        }

        public Share top() {
            return shares.isEmpty() ? null : shares.get(0);
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(where.name().toLowerCase(Locale.ROOT))
                    .append(' ').append(millis).append(" ms");
            if (gcPercent > 0) {
                b.append(", gc ").append(gcPercent).append('%');
            }
            for (Share s : shares) {
                b.append(", ").append(s.key()).append(' ').append(s.percent).append('%');
            }
            b.append("; gpu ").append(gpuPercent).append("%, disk ").append(diskPercent)
                    .append("%, wait ").append(waitPercent).append('%');
            if (otherPrograms) {
                b.append(", other programs busy");
            }
            return b.toString();
        }
    }

    /** A thread that beats once per tick. */
    public final class Watch {
        final Thread thread;
        final Where where;
        private volatile long beat;
        // sampler thread only
        private long stalledSince;
        private final Map<String, Integer> counts = new HashMap<>();
        private int busy;
        private int idle;
        private final int[] resources = new int[Sections.Resource.values().length];
        private long gcAtStart;

        Watch(Thread thread, Where where) {
            this.thread = thread;
            this.where = where;
        }

        public void beat() {
            beat = System.nanoTime();
        }

        /** Stop watching until the next beat (no world open): a stall now is not a spike. */
        public void pause() {
            beat = 0;
        }
    }

    public static final int MIN_SHARE = 10;
    public static final int KEEP = 30;
    /** Entity types at least this common are worth naming after a spike, at most {@link #CROWDS}. */
    public static final int CROWD = 50;
    public static final int CROWDS = 3;

    /** A beat this late starts sampling. */
    public long startMs = Long.getLong("modkeel.spikes.start_ms", 150);
    /** A stall at least this long is recorded. */
    public long reportMs = Long.getLong("modkeel.spikes.report_ms", 300);
    public long sampleMs = 10;
    /** Called on the sampler thread for each recorded spike. */
    public volatile Consumer<Spike> listener;
    /** Called on the sampler thread for each world spike, to count its entities. */
    public volatile Consumer<Spike> counter;

    /** System processor load at least this, with the game using under half of it: other programs. */
    static final double BUSY_SYSTEM = 0.9;

    private final Supplier<Owners> owners;
    private final Sections sections;
    private final List<Watch> watches = new CopyOnWriteArrayList<>();
    /**
     * {system, this process} processor load over the last {@link #LOAD_MS}, from its own thread:
     * the first reading takes most of a second and later ones tens of ms, too slow to sample.
     */
    private volatile double[] load;
    static final long LOAD_MS = 2000;
    private final Deque<Spike> recent = new ArrayDeque<>();
    private Thread sampler;

    public Spikes(Supplier<Owners> owners, Sections sections) {
        this.owners = owners;
        this.sections = sections;
    }

    /** The watch of the current thread, created on its first call. */
    public Watch watch(Where where) {
        Thread t = Thread.currentThread();
        for (Watch w : watches) {
            if (w.thread == t) {
                return w;
            }
        }
        Watch w = new Watch(t, where);
        watches.add(w);
        return w;
    }

    public synchronized void start() {
        if (sampler != null) {
            return;
        }
        sampler = new Thread(this::loop, "modkeel-spikes");
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MAX_PRIORITY);
        sampler.start();
        Thread sampler = this.sampler;
        Thread loads = new Thread(() -> {
            while (sampler.isAlive() && !sampler.isInterrupted()) {
                load = cpuLoad();
                try {
                    Thread.sleep(LOAD_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "modkeel-load");
        loads.setDaemon(true);
        loads.setPriority(Thread.MIN_PRIORITY);
        loads.start();
    }

    public synchronized void stop() {
        if (sampler != null) {
            sampler.interrupt();
            sampler = null;
        }
    }

    /** Recorded spikes, newest first. */
    public List<Spike> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(sampleMs);
            } catch (InterruptedException e) {
                return;
            }
            for (Watch w : watches) {
                if (!w.thread.isAlive()) {
                    watches.remove(w);
                    continue;
                }
                try {
                    check(w);
                } catch (RuntimeException e) {
                    Log.warn("lag spike check failed", e);
                }
            }
        }
    }

    private void check(Watch w) {
        long beat = w.beat;
        if (beat == 0) {
            w.stalledSince = 0;
            return;
        }
        long now = System.nanoTime();
        if ((now - beat) / 1_000_000 >= startMs) {
            if (w.stalledSince != beat) {
                w.stalledSince = beat;
                w.counts.clear();
                w.busy = 0;
                w.idle = 0;
                Arrays.fill(w.resources, 0);
                w.gcAtStart = gcMillis();
            }
            sample(w);
        } else if (w.stalledSince != 0) {
            long millis = (beat - w.stalledSince) / 1_000_000;
            // mostly waiting (a paused game, a thread parked between ticks) is not lag
            if (millis >= reportMs && w.busy > w.idle) {
                record(w, millis);
            }
            w.stalledSince = 0;
        }
    }

    private void sample(Watch w) {
        Thread.State state = w.thread.getState();
        StackTraceElement[] stack = w.thread.getStackTrace();
        if (stack.length == 0) {
            w.idle++;
            return;
        }
        String section = sections.of(stack);
        Sections.Resource resource;
        if (state == Thread.State.RUNNABLE) {
            resource = Sections.resource(stack);
        } else if (section != null) {
            // blocked in the middle of the game's work (a chunk it needs now): its worker threads
            resource = Sections.Resource.WAIT;
        } else {
            // parked between ticks or frames
            w.idle++;
            return;
        }
        w.busy++;
        String owner = owner(stack, owners.get());
        if (owner.equals(VANILLA) && section != null) {
            owner = VANILLA + ":" + section;
        }
        w.counts.merge(owner, 1, Integer::sum);
        w.resources[resource.ordinal()]++;
    }

    private void record(Watch w, long millis) {
        long gc = Math.max(0, gcMillis() - w.gcAtStart);
        int gcPercent = (int) Math.min(100, gc * 100 / Math.max(1, millis));
        Owners o = owners.get();
        List<Share> shares = new ArrayList<>();
        w.counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    int pct = e.getValue() * 100 / w.busy;
                    if (pct >= MIN_SHARE) {
                        String key = e.getKey();
                        if (key.equals(VANILLA) || key.startsWith(VANILLA + ":")) {
                            String section = key.length() > VANILLA.length()
                                    ? key.substring(VANILLA.length() + 1) : null;
                            shares.add(new Share(VANILLA, null, section, pct));
                        } else {
                            JarInfo info = o.ofId(key);
                            shares.add(new Share(key, info == null ? key : info.displayName(),
                                    null, pct));
                        }
                    }
                });
        int gpu = w.resources[Sections.Resource.GPU.ordinal()] * 100 / w.busy;
        int disk = w.resources[Sections.Resource.DISK.ordinal()] * 100 / w.busy;
        int wait = w.resources[Sections.Resource.WAIT.ordinal()] * 100 / w.busy;
        double[] load = this.load;
        boolean others = load != null && load[0] >= BUSY_SYSTEM && load[1] < load[0] / 2;
        Spike s = new Spike(w.where, System.currentTimeMillis(), millis, gcPercent, shares, gpu,
                disk, wait, others);
        synchronized (recent) {
            recent.addFirst(s);
            while (recent.size() > KEEP) {
                recent.removeLast();
            }
        }
        Log.info("lag spike: " + s);
        Consumer<Spike> c = counter;
        if (c != null && s.where == Where.WORLD) {
            c.accept(s);
        }
        Consumer<Spike> l = listener;
        if (l != null) {
            l.accept(s);
        }
    }

    /**
     * The mod running this stack: the innermost frame a mod owns, by merged mixin handler name,
     * by class package or by module. {@link #VANILLA} when no mod is on the stack.
     */
    static String owner(StackTraceElement[] stack, Owners owners) {
        for (StackTraceElement e : stack) {
            CrashReport.Frame f = new CrashReport.Frame(null, e.getClassName(), e.getMethodName());
            String handler = f.mixinHandlerMod();
            JarInfo info = handler != null ? owners.ofId(handler) : owners.ofClass(e.getClassName());
            if (info == null && e.getModuleName() != null) {
                info = owners.ofId(e.getModuleName());
            }
            if (info != null && Diagnosis.blameable(info)) {
                return info.id;
            }
        }
        return VANILLA;
    }

    /**
     * {system, this process} processor load since the previous call, 0-1, or null when the JVM
     * cannot tell (or has no reading yet).
     */
    private static double[] cpuLoad() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
                double system = os.getCpuLoad();
                double process = os.getProcessCpuLoad();
                return system < 0 || process < 0 ? null : new double[] {system, process};
            }
        } catch (LinkageError | RuntimeException e) {
            // not every JVM has the com.sun extension
        }
        return null;
    }

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }
}
