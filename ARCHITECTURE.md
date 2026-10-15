# Architecture

A map of the Modkeel Companion's code, for people and AI assistants reading it. What the mod
does for players is in [README.md](README.md); how to build and verify a release is there too.

## The shape

```
loader entrypoint (fabric/ neoforge/ forge/)       thin: hooks the loader's events
        |
        v
game/   Minecraft-only, shared by every loader      screens, backups at world load, ticks
        |
        v
core/   plain Java, no Minecraft or loader classes  mod set, backups, diagnosis, fixes, reports
```

`core/` never imports Minecraft, so all of its logic is unit-tested without a game
(`core/test/`, run by `build.py` before every build). `game/` turns what core decides into
screens and hooks. Each loader folder only connects its loader's events to `game/`.

There are **no mixins**: nothing patches Minecraft's code. Everything the mod sees comes from the
loader's public events, the files on disk and the game's own log.

## core/ (modkeel.companion.core)

| File | What it does |
|------|--------------|
| `Guardian.java` | The entry point every loader calls: backs up worlds when the mod set changes, remembers the last set that worked, diagnoses a crash on the next launch, applies reversible fixes |
| `ModSet.java` | The jars in the mods folder, identified by content (sha1), so a renamed jar is still the same mod |
| `JarInfo.java` | What a mod jar declares (id, version, dependencies) and contains (packages, mixin configs) |
| `Owners.java` | Which mod owns a class package, a mixin config or a mod id, across every jar |
| `CrashReport.java` | The parts of a crash report or log that point at a mod: the error, stack frames, "Suspected Mods" |
| `Diagnosis.java` | What went wrong, which mods are behind it and how sure Modkeel is (the confidence shown on screen) |
| `Rules.java` | Known problems from `game/resources/modkeel/rules.tsv` (lab data, CC BY 4.0): which exact jars crashed and which ran together. Offline; a build from source without the file still diagnoses |
| `Plan.java`, `Apply.java` | A fix as file operations on the mods folder; a helper process (`java -cp modkeel.jar ... Apply <pid>`) waits for the game to exit (Windows locks loaded jars), then runs them. Every operation can be undone |
| `Backups.java` | World copies as zip files under `<game>/modkeel/backups/<world>/`; going back deletes nothing |
| `Outcomes.java` | Whether a fix held, judged by play (time on the fixed set, the same crash or not) instead of asking |
| `CrashWatch.java` | When a mod deadlocks the game on its way out after a crash, writes the report itself, naming the stuck mod, and ends the process |
| `Spikes.java`, `Sections.java` | Lag spikes (reported from 300 ms) and which part of the game each was in: entities, chunks, rendering, the disk (sampled thread stacks, no mixins) |
| `Gpu.java`, `Memory.java` | Which graphics card draws the game and whether a faster one sits unused; whether Java got too little or too much memory for the pack |
| `Sessions.java`, `Activity.java` | The play session journal and per-mod play time |
| `Reports.java`, `Sent.java` | Anonymous reports, only after the player ticks "Help other players". A report waits in `<game>/modkeel/outbox` until the server takes it; sent ones stay in `sent/` for the player to read. `Sent.java` is the "What is sent" text, one line per field |
| `State.java`, `Msg.java`, `Log.java` | Small saved state, translatable messages, one-line log lines with a fixed prefix |

## game/ (modkeel.companion.game)

`src/` is shared by all Minecraft versions (Mojang names); `versions/<v>/` holds only what changed
between versions (`Compat`, `Stack`, `Scroll` and similar small adapters).

| File | What it does |
|------|--------------|
| `Common.java` | What every loader does on clients and servers: backups before a world loads, ticks for lag spikes and play time |
| `Client.java` | Client side: the crash screen before the title screen, the Modkeel button, lag spike alerts |
| `CrashLog.java`, `StartingCrash.java` | Hears Minecraft log a crash; handles a crash that stopped the last start before any screen could show |
| `CrashScreen.java`, `CrashOptionsScreen.java` | The likely mod and its one-click fix; more options (back to the mods that worked, another mod, the report) |
| `HealthScreen.java`, `ModsScreen.java`, `WorldsScreen.java`, `WorldScreen.java`, `SpikeScreen.java`, `SettingsScreen.java`, `ReportScreen.java`, `WelcomeScreen.java` | The other screens: home, mods, world copies, lag spikes, settings, "What is sent", first start |
| `Keel.java`, `Page.java`, `Card.java`, `Heading.java`, `Text.java`, `Paint.java`, `Edges.java`, `Backdrop.java` | The mod's own look (Keel): a page of cards; `Paint` is the two drawing calls its widgets need, over whatever Minecraft draws with |
| `Tracker.java`, `Graphics.java`, `MemoryCard.java`, `SpikeCard.java` | Read per-mod play, the graphics card and memory from what the game already keeps |
| `Tour.java` | Test driver for the screen tour; on only with `-Dmodkeel.test.tour=FILE` |
| `resources/assets/modkeel/lang/` | Every text, per language |

## Loaders

| Folder | What it does |
|--------|--------------|
| `fabric/` | `CompanionPreLaunch` (before Minecraft's first class), `CompanionMod`, `CompanionClient` |
| `neoforge/`, `forge/` | One `@Mod` entrypoint and its client side each |
| `forge-early/`, `neoforge-early/` | A loader service that runs before the loader reads the mods, and carries the mod inside it (`EmbeddedLocator`): it turns off a jar that would close the game before any mod runs |
| `early-common/` | What both services share: `ModuleCheck` (two jars with the same Java package, which (Neo)Forge refuses), `Note` |
| `fabric/versions/<v>/` | Per-version differences of the Fabric entrypoints |

## Build and tests

| File | What it does |
|------|--------------|
| `build.py` | `javac`, no Gradle: core tests, then one jar per loader and Minecraft version (`--all` builds every release jar, reproducibly) |
| `remap.py` | Mojang names to intermediary (Fabric) or SRG (Forge) for versions before 26.1 |
| `toolchain.py` | JDK, game and loader downloads, caches under `~/.modkeel` (`MODKEEL_HOME`) |
| `testmods/` | Mods that crash on demand, for end-to-end tests in a real client |
| `.github/workflows/` | `release.yml` (CI-built jars, SHA256SUMS, provenance), `codeql.yml`, `scorecard.yml` |

## Where to start reading

- A crash, from log line to fix: `game/CrashLog` + `core/CrashWatch` (the report gets written),
  then on the next start `core/Guardian` -> `core/CrashReport` -> `core/Diagnosis` ->
  `game/CrashScreen` -> `core/Plan` + `core/Apply`.
- A world backup: `game/Common` (world about to load) -> `core/Guardian` -> `core/Backups`.
- What leaves the computer, and when: `core/Reports` and `core/Sent`.
