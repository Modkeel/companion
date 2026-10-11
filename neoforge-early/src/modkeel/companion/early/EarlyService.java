package modkeel.companion.early;

import java.util.List;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoForge loads this with its own services, before it looks for mods and before it builds the
 * modules they run in: the one moment a check can still keep two jars with one package from
 * stopping the start ({@link ModuleCheck}). Transforms nothing.
 */
public final class EarlyService implements ITransformationService {
    static final Logger LOG = LogManager.getLogger("modkeel");

    @Override
    public String name() {
        return "modkeel";
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
    }

    /** After the launch arguments are read (the game folder), before any mod is looked for. */
    @Override
    public void initialize(IEnvironment env) {
        env.getProperty(IEnvironment.Keys.GAMEDIR.get()).ifPresent(dir -> {
            try {
                ModuleCheck.run(dir, "META-INF/neoforge.mods.toml", LOG::info);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("[modkeel] cannot check the last start: " + e);
            }
        });
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return List.of();
    }
}
