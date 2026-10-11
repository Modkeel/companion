package modkeel.companion.early;

import java.util.List;
import java.util.Set;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

/**
 * Forge loads this with its own services, before it looks for mods: the one moment a check can
 * still keep a broken jar from stopping the start ({@link ModuleCheck}: two jars with one
 * package; {@link AccessCheck}: an access transformer Forge cannot read). Transforms nothing.
 */
public final class EarlyService implements ITransformationService {
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
                ModuleCheck.run(dir, "META-INF/mods.toml", AccessCheck.LOG::info);
            } catch (RuntimeException | LinkageError e) {
                AccessCheck.LOG.warn("[modkeel] cannot check the last start: " + e);
            }
            try {
                AccessCheck.run(dir);
            } catch (RuntimeException | LinkageError e) {
                AccessCheck.LOG.warn("[modkeel] cannot check the access transformers: " + e);
            }
        });
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<ITransformer> transformers() {
        return List.of();
    }
}
