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
    """The crasher first, then any helper the test needs on that loader. Named per version
    like the mod jar: --all builds every target into the same folder."""
    tail = f"{loader}-{variant(mc)}.jar"
    if loader == "fabric":
        return [BUILD / f"mfcrash-{tail}"]
    return [BUILD / f"mfcrash-{tail}", BUILD / f"mfidle-{tail}"]


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


# Reproducible jars: the same sources give the same bytes on any machine, so anyone can rebuild
# a release and compare its SHA-256. No file dates, one entry order, Unix line endings in text
# (a Windows checkout has CRLF) and the same zip header fields everywhere.
JAR_DATE = (1980, 2, 1, 0, 0, 0)
TEXT = {".json", ".toml", ".mcmeta", ".tsv", ".txt", ".md", ".cfg", ".properties", ""}


def _entry(z: zipfile.ZipFile, name: str, data: str | bytes) -> None:
    info = zipfile.ZipInfo(name, JAR_DATE)
    info.compress_type = zipfile.ZIP_STORED
    info.create_system = 3
    info.external_attr = 0o100644 << 16
    z.writestr(info, data)


def _text(f: Path) -> bytes:
    data = f.read_bytes()
    text = f.suffix in TEXT or f.parent.name == "services"  # META-INF/services/<interface>
    return data.replace(b"\r\n", b"\n") if text else data


def _files(root: Path, pattern: str) -> list[tuple[str, Path]]:
    """Files under `root` by their path in the jar, sorted the same on every OS."""
    return sorted((f.relative_to(root).as_posix(), f) for f in root.rglob(pattern) if f.is_file())


def write_jar(out: Path, metadata: dict[str, str | bytes], class_dirs: list[Path],
              resources: list[Path]) -> None:
    """`metadata`: loader metadata files (path in the jar -> content), written first."""
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w") as z:
        for name, text in metadata.items():
            _entry(z, name, text)
        for d in class_dirs:
            for name, f in _files(d, "*.class"):
                _entry(z, name, f.read_bytes())
        for root in resources:
            for name, f in _files(root, "*"):
                if name not in metadata:
                    _entry(z, name, _text(f))
                    for alias in LANG_ALIASES.get(f.name, []):
                        _entry(z, name[: -len(f.name)] + alias, _text(f))
        license_file = HERE / "LICENSE"
        if license_file.exists():
            _entry(z, "LICENSE_modkeel", _text(license_file))


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


def class_sections(mc: str, out: Path) -> Path:
    """modkeel/classnames.tsv under `out`: each intermediary class whose Mojang name is in
    sections.txt, with its section, so the lag spike breakdown reads obfuscated Fabric stacks."""
    rows = []
    for line in (HERE / "game" / "resources" / "modkeel" / "sections.txt").read_text(
            encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            prefix, section = line.split("\t")[:2]
            rows.append((prefix, section))
    rows.sort(key=lambda r: -len(r[0]))
    lines = []
    for line in remap.merged_mappings(mc).read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if parts[0] != "c" or "$" in parts[2]:
            continue
        named = parts[3].replace("/", ".")
        section = next((s for p, s in rows if named.startswith(p)), None)
        if section:
            lines.append(f"{parts[2].replace('/', '.')}\t{section}")
    dest = out / "modkeel" / "classnames.tsv"
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(sorted(lines)) + "\n", encoding="utf-8")
    return out


def early(mc: str, out: Path, meta: dict[str, str | bytes], inner: Path, tmp: Path) -> None:
    """Forge: the published jar is forge-early's service, run before Forge looks for mods, with
    the mod inside it. Its metadata stays outside too, for the sites that read it; Forge skips
    a jar with services when it looks for mods, so only the inner mod loads."""
    cp, _ = remap.forge_classpath(mc)
    classes = tmp / "classes"
    javac(remap.java_release(mc), cp, sorted((HERE / "forge-early" / "src").rglob("*.java")),
          classes)
    manifest = "Manifest-Version: 1.0\nAutomatic-Module-Name: modkeel.companion.early\n"
    write_jar(out, {"META-INF/MANIFEST.MF": manifest, **meta,
                    "META-INF/modkeel/companion.jar": inner.read_bytes()},
              [classes], [HERE / "forge-early" / "resources"])


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
        if loader == "fabric" and remap.obfuscated(mc):
            resources.append(class_sections(mc, tmp / "generated"))
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
            write_jar(test_jars(mc, loader)[0], {"fabric.mod.json": json.dumps(
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
                "${updates}", UPDATES_URL).replace("${minecraft}", game_range).replace(
                "${" + loader + "}", loader_range)
            meta = loader_metadata(loader, v, toml, "Modkeel Companion")
            if loader == "forge":
                inner = tmp / "inner.jar"
                write_jar(inner, meta, [core, classes], resources)
                early(mc, mod, meta, inner, tmp / "early")
            else:
                write_jar(mod, meta, [core, classes], resources)
            for (test_mod, name), jar in zip((("mfcrash", "Crash Test Mod"),
                                              ("mfidle", "Mouse Release Test Mod")),
                                             test_jars(mc, loader)):
                out = compile_(mc, sorted((HERE / "testmods" / f"{test_mod}-{loader}").rglob(
                    "*.java")), tmp / test_mod)
                write_jar(jar,
                          loader_metadata(loader, v, test_mod_toml(test_mod, name, mc, loader),
                                          name), [out], [])
    print(f"built {mod.name} ({mod.stat().st_size // 1024} KB) and the test mods")
    return {"mod": mod, "tests": test_jars(mc, loader)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", default="26.2")
    ap.add_argument("--loader", default="fabric", choices=["fabric", "neoforge", "forge"])
    ap.add_argument("--no-tests", action="store_true")
    ap.add_argument("--all", action="store_true", help="every target in TARGETS, for a release")
    args = ap.parse_args()
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
