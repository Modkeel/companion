package modkeel.companion.game;

import modkeel.companion.core.CrashWatch;
import modkeel.companion.core.Log;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Hears Minecraft log a crash, the line it writes before the report, for {@link CrashWatch}.
 * Every Minecraft version logs through Log4j, so this needs no mixin.
 */
final class CrashLog extends AbstractAppender {
    private final CrashWatch watch;

    private CrashLog(CrashWatch watch) {
        super("modkeel-crash", null, null, true, Property.EMPTY_ARRAY);
        this.watch = watch;
    }

    static void install(CrashWatch watch) {
        try {
            CrashLog appender = new CrashLog(watch);
            appender.start();
            ((Logger) LogManager.getRootLogger()).addAppender(appender);
        } catch (LinkageError | RuntimeException e) {
            Log.warn("cannot watch the log for crashes", e);
        }
    }

    @Override
    public void append(LogEvent event) {
        Throwable error = event.getThrown();
        if (error != null && CrashWatch.isFatal(event.getMessage().getFormattedMessage())) {
            watch.crashed(error, event.getThreadName());
        }
    }
}
