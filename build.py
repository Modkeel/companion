"""Build the Modkeel Companion mod with javac, no Gradle (remap.py handles obfuscated versions).

    python companion/build.py              # core tests + mod jar + test crasher mod, for 26.2
    python companion/build.py --mc 1.21.1  # for another Minecraft version
    python companion/build.py --mc 1.21.1 --loader neoforge
    python companion/build.py --mc 1.20.1 --loader forge
    python companion/build.py --all        # every released target -> companion/build/release/

Outputs in companion/build/: modkeel-companion-<version>-<loader>-<variant>.jar and the test mods.
game/ holds what only needs Minecraft (screens, backups and ticks), shared by every loader;
fabric/, neoforge/ and forge/ hold each loader's entrypoints. In each, src is shared by all Minecraft
versions and versions/<variant> holds what changed between them.
The core compiles with --release 17 so adapters for older Java can share it.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import remap
import toolchain as tc

HERE = Path(__file__).resolve().parent

VERSION = "0.1.0"
# what a release ships: one jar per loader and variant, each built against this game version
TARGETS = [("26.2", "fabric"), ("1.21.11", "fabric"), ("1.21.1", "fabric"),
           ("1.21.1", "neoforge"), ("1.20.1", "fabric"), ("1.20.1", "forge")]
BUILD = HERE / "build"
# NeoForge and Forge read this from the public repo (updateJSONURL) and flag a newer release in
# their mods list; Fabric players get the same from Mod Menu once the mod is on Modrinth
UPDATES = HERE / "updates.json"
UPDATES_URL = "https://raw.githubusercontent.com/Modkeel/companion/main/updates.json"
FABRIC_MODULES = "lifecycle-events-v1|screen-api-v1|api-base"


def javac(release: int, cp: list[Path], sources: list[Path], out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    cmd = [tc.jdk_tool("javac"), "--release", str(release), "-proc:none", "-encoding", "UTF-8",
           "-Xlint:-options", "-d", str(out)]
    if cp:
        cmd += ["-cp", tc.SEP.join(map(str, cp))]
    subprocess.run(cmd + [str(s) for s in sources], check=True)


def variant(mc: str) -> str:
    """Which */versions/<variant> sources fit this Minecraft version."""
    if not remap.obfuscated(mc):
        return "26"
    if mc == "1.21.11":
        return "1.21.11"
    return "1.20.1" if mc.startswith("1.20") else "1.21.1"


# a variant that only differs from another in a few files keeps just those
BASE = {"1.21.11": "26"}


def version_sources(root: Path, v: str) -> list[Path]:
    """root/versions/<v> sources, plus those of its base that it does not replace."""
    own = {f.relative_to(root / "versions" / v): f for f in (root / "versions" / v).rglob("*.java")}
    if v in BASE:
        base = root / "versions" / BASE[v]
        own = {**{f.relative_to(base): f for f in base.rglob("*.java")}, **own}
    return list(own.values())


MINECRAFT_RANGE = {"26": ">=26.1", "1.21.11": "~1.21.11", "1.21.1": ">=1.21 <1.21.2",
                   "1.20.1": ">=1.20 <1.20.2"}
# NeoForge and Forge: Maven ranges for the game and for the loader itself
LOADER_RANGES = {"neoforge": {"1.21.1": ("[1.21,1.21.2)", "[21.1,21.2)")},
                 "forge": {"1.20.1": ("[1.20,1.20.2)", "[47,)")}}
# where each loader reads mod metadata from
TOML = {"neoforge": "META-INF/neoforge.mods.toml", "forge": "META-INF/mods.toml"}
# Forge 1.20.1 warns on start about any mod jar without a pack.mcmeta (resource pack format)
PACK_FORMAT = {"forge": {"1.20.1": 15}}


def loader_metadata(loader: str, v: str, toml: str, name: str) -> dict[str, str]:
    meta = {TOML[loader]: toml}
    if v in PACK_FORMAT.get(loader, {}):
        meta["pack.mcmeta"] = json.dumps(
            {"pack": {"description": name, "pack_format": PACK_FORMAT[loader][v]}}, indent=2)
    return meta


def mod_jar(mc: str, loader: str = "fabric") -> Path:
    return BUILD / f"modkeel-companion-{VERSION}-{loader}-{variant(mc)}.jar"


def test_jars(mc: str, loader: str = "fabric") -> list[Path]:
    """The crasher first, then any helper the test needs on that loader."""
    if loader == "fabric":
        return [BUILD / "mfcrash.jar"]
    return [BUILD / f"mfcrash-{loader}.jar", BUILD / f"mfidle-{loader}.jar"]


def build_core(tmp: Path) -> Path:
    out = tmp / "core"
    javac(17, [], sorted((HERE / "core" / "src").rglob("*.java")), out)
    return out


def test_core(core: Path, tmp: Path) -> None:
    out = tmp / "core-test"
    javac(17, [core], sorted((HERE / "core" / "test").rglob("*.java")), out)
    subprocess.run([tc.jdk_tool("java"), "-cp", tc.SEP.join([str(core), str(out)]),
                    "modkeel.companion.core.CoreTest", str(HERE / "core" / "test" / "fixtures")],
                   check=True)


# Minecraft falls back to English, not to a sibling language: each regional variant needs its file
LANG_ALIASES = {
    "es_es.json": [f"es_{r}.json" for r in ("ar", "cl", "ec", "mx", "uy", "ve")],
    "pt_br.json": ["pt_pt.json"],
    "de_de.json": ["de_at.json", "de_ch.json"],
    "fr_fr.json": ["fr_ca.json"],
}


def write_jar(out: Path, metadata: dict[str, str], class_dirs: list[Path],
              resources: list[Path]) -> None:
    """`metadata`: loader metadata files (path in the jar -> text), written first."""
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name, text in metadata.items():
            z.writestr(name, text)
        for d in class_dirs:
            for f in sorted(d.rglob("*.class")):
                z.write(f, f.relative_to(d).as_posix())
        for root in resources:
            for f in sorted(root.rglob("*")):
                name = f.relative_to(root).as_posix()
                if f.is_file() and name not in metadata:
                    z.write(f, name)
                    for alias in LANG_ALIASES.get(f.name, []):
                        z.write(f, name[: -len(f.name)] + alias)
        license_file = HERE / "LICENSE"
        if license_file.exists():
            z.write(license_file, "LICENSE_modkeel")


def test_mod_toml(mod_id: str, name: str, mc: str, loader: str) -> str:
    """Metadata for a client-only test mod."""
    game, _ = LOADER_RANGES[loader][variant(mc)]
    # Forge marks a required dependency with `mandatory`, NeoForge with `type`
    required = 'type = "required"' if loader == "neoforge" else "mandatory = true"
    return "\n".join([
        'modLoader = "javafml"', 'loaderVersion = "[1,)"', 'license = "MIT"', "",
        "[[mods]]", f'modId = "{mod_id}"', 'version = "1.0"', f'displayName = "{name}"', "",
        f"[[dependencies.{mod_id}]]", 'modId = "minecraft"', required,
        f'versionRange = "{game}"', 'side = "CLIENT"', ""])


def build(mc: str, loader: str = "fabric", run_tests: bool = True) -> dict[str, Path]:
    v = variant(mc)
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        core = build_core(tmp)
        if run_tests:
            test_core(core, tmp)
        game = [*(HERE / "game" / "src").rglob("*.java"), *version_sources(HERE / "game", v)]
        adapter = [*(HERE / loader / "src").rglob("*.java"), *version_sources(HERE / loader, v)]
        resources = [HERE / "game" / "resources", HERE / loader / "resources"]
        mod = mod_jar(mc, loader)
        if loader == "fabric":
            classes = remap.compile_fabric(mc, sorted(game + adapter), tmp / "classes",
                                           FABRIC_MODULES, [core])
            meta = json.loads((HERE / "fabric" / "resources" / "fabric.mod.json")
                              .read_text(encoding="utf-8"))
            meta["version"] = VERSION
            meta["depends"]["minecraft"] = MINECRAFT_RANGE[v]
            write_jar(mod, {"fabric.mod.json": json.dumps(meta, indent=2)}, [core, classes],
                      resources)
            crash_out = remap.compile_fabric(
                mc, sorted((HERE / "testmods" / "mfcrash").rglob("*.java")), tmp / "mfcrash",
                "lifecycle-events-v1|api-base")
            write_jar(BUILD / "mfcrash.jar", {"fabric.mod.json": json.dumps(
                {"schemaVersion": 1, "id": "mfcrash", "version": "1.0",
                 "name": "Crash Test Mod", "environment": "client",
                 "entrypoints": {"client": ["modkeel.testmods.MfCrash"]},
                 "depends": {"fabric-lifecycle-events-v1": "*"}})}, [crash_out], [])
        else:
            compile_ = remap.compile_neoforge if loader == "neoforge" else remap.compile_forge
            classes = compile_(mc, sorted(game + adapter), tmp / "classes", [core])
            game_range, loader_range = LOADER_RANGES[loader][v]
            toml = (HERE / loader / "resources" / TOML[loader]).read_text(
                encoding="utf-8").replace("${version}", VERSION).replace(
                "${updates}", UPDATES_URL).replace("${minecraft}", game_range).replace("${" + loader + "}", loader_range)
            write_jar(mod, loader_metadata(loader, v, toml, "Modkeel Companion"), [core, classes],
                      resources)
            for test_mod, name in (("mfcrash", "Crash Test Mod"),
                                   ("mfidle", "Mouse Release Test Mod")):
                out = compile_(mc, sorted((HERE / "testmods" / f"{test_mod}-{loader}").rglob(
                    "*.java")), tmp / test_mod)
                write_jar(BUILD / f"{test_mod}-{loader}.jar",
                          loader_metadata(loader, v, test_mod_toml(test_mod, name, mc, loader),
                                          name), [out], [])
    print(f"built {mod.name} ({mod.stat().st_size // 1024} KB) and the test mods")
    return {"mod": mod, "tests": test_jars(mc, loader)}


def game_versions(maven_range: str) -> list[str]:
    """Every release a Maven range like "[1.21,1.21.2)" admits: 1.21 and 1.21.1."""
    low, high = maven_range.strip("[)").split(",")
    last = int(high.split(".")[2]) if high.count(".") == 2 else 1
    return [low] + [f"{low}.{n}" for n in range(1, last)]


def announce() -> None:
    """Point NeoForge and Forge players at VERSION. Run once it is downloadable."""
    data = {"homepage": "https://modkeel.com", "promos": {}}
    for loader, ranges in LOADER_RANGES.items():
        for game_range, _ in ranges.values():
            for mc in game_versions(game_range):
                data["promos"][f"{mc}-latest"] = VERSION
                data["promos"][f"{mc}-recommended"] = VERSION
    UPDATES.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"{UPDATES.name}: {VERSION} for Minecraft " + ", ".join(
        k[:-len("-latest")] for k in data["promos"] if k.endswith("-latest")))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", default="26.2")
    ap.add_argument("--loader", default="fabric", choices=["fabric", "neoforge", "forge"])
    ap.add_argument("--no-tests", action="store_true")
    ap.add_argument("--all", action="store_true", help="every target in TARGETS, for a release")
    ap.add_argument("--announce", action="store_true",
                    help="after uploading a release: write updates.json so loaders offer it")
    args = ap.parse_args()
    if args.announce:
        announce()
        return
    for old in BUILD.glob("*.jar"):  # logs, shots and test output stay
        old.unlink()
    if not args.all:
        build(args.mc, args.loader, run_tests=not args.no_tests)
        return
    release = BUILD / "release"
    if release.exists():
        shutil.rmtree(release)
    release.mkdir(parents=True)
    for i, (mc, loader) in enumerate(TARGETS):
        print(f"== {loader} {mc}")
        jar = build(mc, loader, run_tests=i == 0 and not args.no_tests)["mod"]
        shutil.copy2(jar, release / jar.name)
    print(f"{len(TARGETS)} jars in {release}")


if __name__ == "__main__":
    main()
