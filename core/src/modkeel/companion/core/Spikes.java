package modkeel.companion.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lag spikes and the mods behind them, with no mixins. A game thread beats once per tick; a
 * watchdog thread notices when the beats stop, samples that thread's stack until they resume,
 * and names the mod whose code the thread was running.
 */
public final class Spikes {
    /** Which thread stalled: the render thread (the picture froze) or the server thread (the world stopped). */
    public enum Where { FRAME, WORLD }

    /** Id of the samples that no mod owns. */
    public static final String VANILLA = "minecraft";

    /** One mod's share of a spike. {@code name} is null for {@link #VANILLA}. */
    public static final class Share {
        public final String id;
        public final String name;
        public final int percent;

        Share(String id, String name, int percent) {
            this.id = id;
            this.name = name;
            this.percent = percent;
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

        Spike(Where where, long at, long millis, int gcPercent, List<Share> shares) {
            this.where = where;
            this.at = at;
            this.millis = millis;
            this.gcPercent = gcPercent;
            this.shares = shares;
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
                b.append(", ").append(s.id).append(' ').append(s.percent).append('%');
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

    /** A beat this late starts sampling. */
    public long startMs = Long.getLong("modkeel.spikes.start_ms", 150);
    /** A stall at least this long is recorded. */
    public long reportMs = Long.getLong("modkeel.spikes.report_ms", 300);
    public long sampleMs = 10;
    /** Called on the sampler thread for each recorded spike. */
    public volatile Consumer<Spike> listener;

    private final Supplier<Owners> owners;
    private final List<Watch> watches = new CopyOnWriteArrayList<>();
    private final Deque<Spike> recent = new ArrayDeque<>();
    private Thread sampler;

    public Spikes(Supplier<Owners> owners) {
        this.owners = owners;
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
        if (state != Thread.State.RUNNABLE || stack.length == 0) {
            w.idle++;
            return;
        }
        w.busy++;
        w.counts.merge(owner(stack, owners.get()), 1, Integer::sum);
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
                        JarInfo info = e.getKey().equals(VANILLA) ? null : o.ofId(e.getKey());
                        shares.add(new Share(e.getKey(),
                                info == null ? null : info.displayName(), pct));
                    }
                });
        Spike s = new Spike(w.where, System.currentTimeMillis(), millis, gcPercent, shares);
        synchronized (recent) {
            recent.addFirst(s);
            while (recent.size() > KEEP) {
                recent.removeLast();
            }
        }
        Log.info("lag spike: " + s);
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

    private static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }
}
