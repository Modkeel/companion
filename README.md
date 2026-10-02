# Modkeel Companion

A safety net for modded Minecraft. It works on its own, with nothing to set up.

- **World backups when your mods change.** Before a world loads with a different set of mods,
  Modkeel backs it up. Restoring keeps the current world in `modkeel/replaced`; nothing is deleted.
- **Crash diagnosis.** After a crash, the next start shows which mod most likely caused it, with
  a confidence level and the reason (its mixin failed, its code is in the stack trace, it calls
  code that does not exist in this game...).
- **One-click fixes you can undo.** Disable the suspect mod (and see which mods depend on it), or
  go back to the last set of mods that played without problems. Every fix is watched: Modkeel
  tells you whether it held or the same crash came back.
- **No mixins.** Modkeel does not patch the game, so it cannot be the reason a pack breaks.
- **Nothing leaves your computer.** The mod makes no network requests.

Everything lives under the Modkeel button on the title screen.

## Supported versions

| Minecraft | Fabric | NeoForge | Forge |
|-----------|--------|----------|-------|
| 26.x      | yes    |          |       |
| 1.21.11   | yes    |          |       |
| 1.21.1    | yes    | yes      |       |
| 1.20.1    | yes    |          | yes   |

Fabric needs [Fabric API](https://modrinth.com/mod/fabric-api).

## Languages

English, Spanish, Brazilian Portuguese, German, French, Russian and Simplified Chinese, with
regional variants (Spanish for Latin America, European Portuguese, Austrian and Swiss German,
Canadian French) using the closest one. Every text is in `game/resources/assets/modkeel/lang/`,
so a resource pack can add or override a language with its own `assets/modkeel/lang/<code>.json`.

## Building

No Gradle: `build.py` compiles with `javac` and remaps with
[tiny-remapper](https://github.com/FabricMC/tiny-remapper) on its own. It needs Python 3.10+ and
JDK 25 (set `MODKEEL_JDK` if it is not your `JAVA_HOME`).

```bash
python build.py                                # Fabric, Minecraft 26.2 (runs the core tests first)
python build.py --mc 1.21.1 --loader neoforge
python build.py --mc 1.20.1 --loader forge
python build.py --all                          # every released jar -> build/release/
```

Releases are on the [releases page](https://github.com/Modkeel/companion/releases).
NeoForge and Forge check `updates.json` in this repo and mark a newer Modkeel in their mods
list; the loader makes that request, not Modkeel. On Fabric, Mod Menu does the same through
Modrinth.

The first build of a version downloads the game, its mappings and the loader (for NeoForge and
Forge, through their official installers) into `~/.modkeel` (`MODKEEL_HOME`).

## Layout

| Path | What |
|------|------|
| `core/` | Loader-agnostic logic: mod sets, world backups, crash diagnosis, fixes. Plain Java 17, with its own tests |
| `game/` | What only needs Minecraft: screens, backup and tick hooks, translations |
| `fabric/`, `neoforge/`, `forge/` | Thin entrypoints per loader |
| `forge-early/` | Forge only: a service that runs before Forge reads the mods, turns off a jar whose access transformer would close the game, and carries the mod inside |
| `*/versions/<v>/` | What changed between Minecraft versions |
| `testmods/` | Mods that crash on demand, for end-to-end tests |
| `build.py`, `remap.py`, `toolchain.py` | The build |

Sources use Mojang names. For versions before 26.1 they are remapped to intermediary (Fabric)
or SRG (Forge) names at build time.

## License

MIT. See [LICENSE](LICENSE). More at [modkeel.com](https://modkeel.com).
