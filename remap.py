"""Mappings toolchain for obfuscated Minecraft versions (before 26.1), without Gradle or Loom.

Adapters are written against Mojang names ("named"), the same names 26.x ships with. For an
obfuscated version:

  official jar  --(merged tiny: official, intermediary, named)-->  named client jar
  Fabric API    --(intermediary -> named)-->                       named API jars
  javac against the named jars, then remap our classes named -> intermediary for Fabric Loader.

NeoForge (1.20.5 and later) runs with Mojang names: no remapping, only its installer and a
classpath of its patched classes over the renamed client.

Everything is cached under MODKEEL_HOME/companion-tools/<mc>/ (see toolchain.py).
"""
from __future__ import annotations

import json
import re
import subprocess
import zipfile
from pathlib import Path

import toolchain as tc
from toolchain import fetch

TOOLS = tc.TOOLS
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_MAVEN = "https://maven.fabricmc.net/net/fabricmc"
TINY_REMAPPER = "0.14.1"


def obfuscated(mc: str) -> bool:
    """26.1 and later ship with Mojang names."""
    return not re.match(r"2[6-9]\.", mc)


def _version_json(mc: str) -> dict:
    dest = TOOLS / mc / "version.json"
    if not dest.exists():
        manifest = tc.fetch_json(MANIFEST)
        url = next(v["url"] for v in manifest["versions"] if v["id"] == mc)
        fetch(url, dest)
    return json.loads(dest.read_text(encoding="utf-8"))


def remapper() -> Path:
    return fetch(f"{FABRIC_MAVEN}/tiny-remapper/{TINY_REMAPPER}/tiny-remapper-{TINY_REMAPPER}-fat.jar",
                 TOOLS / f"tiny-remapper-{TINY_REMAPPER}-fat.jar")


def official_client(mc: str) -> Path:
    return fetch(_version_json(mc)["downloads"]["client"]["url"], TOOLS / mc / "client-official.jar")


PRIMITIVES = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I",
              "long": "J", "float": "F", "double": "D"}


def _desc(java_type: str, named_to_official: dict[str, str]) -> str:
    dims = java_type.count("[]")
    base = java_type.replace("[]", "")
    if base in PRIMITIVES:
        d = PRIMITIVES[base]
    else:
        name = base.replace(".", "/")
        d = "L" + named_to_official.get(name, name) + ";"
    return "[" * dims + d


def parse_proguard(text: str):
    """Mojang's client mappings: named -> official classes, members keyed by official names."""
    classes: dict[str, str] = {}  # official -> named
    raw: list[tuple[str, str, str]] = []  # (named class, member line, official class)
    current = None
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        if not line.startswith(" "):
            named, off = line.rstrip(":").split(" -> ")
            current = (named.replace(".", "/"), off.replace(".", "/"))
            classes[current[1]] = current[0]
        else:
            raw.append((current[0], line.strip(), current[1]))
    named_to_official = {n: o for o, n in classes.items()}
    methods: dict[tuple[str, str, str], str] = {}
    fields: dict[tuple[str, str], str] = {}
    field_descs: dict[tuple[str, str], str] = {}  # official descriptors
    for _, member, off_cls in raw:
        left, obf = member.split(" -> ")
        if "(" in left:
            left = re.sub(r"^\d+:\d+:", "", left)
            left = re.sub(r"\):\d+(:\d+)?$", ")", left)
            ret, rest = left.split(" ", 1)
            name, args = rest.split("(", 1)
            args = args.rstrip(")")
            desc = "(" + "".join(_desc(a, named_to_official) for a in args.split(",") if a) + ")" \
                + _desc(ret, named_to_official)
            methods[(off_cls, obf, desc)] = name
        else:
            ftype, name = left.split(" ", 1)
            fields[(off_cls, obf)] = name
            field_descs[(off_cls, obf)] = _desc(ftype, named_to_official)
    return classes, methods, fields, field_descs


def merged_mappings(mc: str) -> Path:
    """tiny v2 with official, intermediary and named namespaces, like Loom's layered mojmap."""
    out = TOOLS / mc / "merged.tiny"
    if out.exists():
        return out
    proguard = fetch(_version_json(mc)["downloads"]["client_mappings"]["url"],
                     TOOLS / mc / "client.txt").read_text(encoding="utf-8")
    classes, methods, fields, _ = parse_proguard(proguard)
    inter_jar = fetch(f"{FABRIC_MAVEN}/intermediary/{mc}/intermediary-{mc}-v2.jar",
                      TOOLS / mc / "intermediary-v2.jar")
    with zipfile.ZipFile(inter_jar) as z:
        inter = z.read("mappings/mappings.tiny").decode("utf-8").splitlines()
    lines = ["tiny\t2\t0\tofficial\tintermediary\tnamed"]
    cls = None
    missing = 0
    for line in inter[1:]:
        parts = line.split("\t")
        if parts[0] == "c":
            cls = parts[1]
            named = classes.get(cls)
            if named is None:
                missing += 1
                named = parts[2]
            lines.append(f"c\t{cls}\t{parts[2]}\t{named}")
        elif len(parts) >= 5 and parts[0] == "" and parts[1] in ("m", "f"):
            kind, desc, off, it = parts[1], parts[2], parts[3], parts[4]
            named = (methods.get((cls, off, desc)) if kind == "m" else fields.get((cls, off))) or it
            lines.append(f"\t{kind}\t{desc}\t{off}\t{it}\t{named}")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"  merged mappings for {mc}: {len(classes)} classes ({missing} without a Mojang name)")
    return out


def remap(src: Path, dest: Path, mc: str, frm: str, to: str, classpath: list[Path],
          mappings: Path | None = None) -> Path:
    """`mappings`: a tiny v2 file with both namespaces; Fabric's merged one by default."""
    if dest.exists() and dest.stat().st_mtime >= src.stat().st_mtime:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".part.jar")
    if tmp.exists():
        tmp.unlink()
    subprocess.run([tc.jdk_tool("java"), "-jar", str(remapper()), str(src), str(tmp),
                    str(mappings or merged_mappings(mc)), frm, to, *map(str, classpath),
                    "--renameinvalidlocals", "--ignoreconflicts"],
                   check=True, stdout=subprocess.DEVNULL)
    tmp.replace(dest)
    return dest


def named_client(mc: str) -> Path:
    return remap(official_client(mc), TOOLS / mc / "client-named.jar", mc, "official", "named", [])


def intermediary_client(mc: str) -> Path:
    return remap(official_client(mc), TOOLS / mc / "client-intermediary.jar", mc, "official",
                 "intermediary", [])


def to_named(mc: str, jar: Path) -> Path:
    """A Fabric mod jar (intermediary) with Mojang names, to compile against."""
    return remap(jar, TOOLS / mc / "named" / jar.name, mc, "intermediary", "named",
                 [intermediary_client(mc)])


def to_intermediary(mc: str, jar: Path, dest: Path, classpath: list[Path]) -> Path:
    """Our compiled classes (named) as Fabric Loader loads them in production."""
    if dest.exists():
        dest.unlink()
    return remap(jar, dest, mc, "named", "intermediary", [named_client(mc), *classpath])


def java_release(mc: str) -> int:
    return int(_version_json(mc).get("javaVersion", {}).get("majorVersion", 21))


def javac(mc: str, cp: list[Path], sources: list[Path], out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run([tc.jdk_tool("javac"), "--release", str(java_release(mc)), "-proc:none",
                    "-encoding", "UTF-8", "-Xlint:-options", "-d", str(out),
                    "-cp", tc.SEP.join(map(str, cp)), *map(str, sources)], check=True)


FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader"


def fabric_loader_libraries(mc: str) -> list[Path]:
    """Fabric Loader (the newest stable one) and its libraries."""
    dest = TOOLS / mc / "fabric-profile.json"
    if not dest.exists():
        stable = next(v for v in tc.fetch_json(f"{FABRIC_META}/{mc}") if v["loader"]["stable"])
        fetch(f"{FABRIC_META}/{mc}/{stable['loader']['version']}/profile/json", dest)
    return tc.libraries(json.loads(dest.read_text(encoding="utf-8")))


def fabric_classpath(mc: str, modules: str, tmp: Path) -> list[Path]:
    """Client, Fabric Loader, its libraries and the Fabric API modules matching `modules`, all
    with Mojang names."""
    client = named_client(mc) if obfuscated(mc) else official_client(mc)
    api = tc.modrinth_jar("fabric-api", mc, "fabric")
    tmp.mkdir(parents=True, exist_ok=True)
    mods = []
    with zipfile.ZipFile(api) as z:
        for n in z.namelist():
            if re.match(r"META-INF/jars/fabric-(" + modules + r")-.*\.jar$", n):
                p = tmp / n.rsplit("/", 1)[-1]
                p.write_bytes(z.read(n))
                mods.append(to_named(mc, p) if obfuscated(mc) else p)
    # only this version's libraries: others (a newer LWJGL, say) break javac and the remapper
    libs = tc.libraries(_version_json(mc))
    return [client, *fabric_loader_libraries(mc), *mods, *libs]


NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"


def neoforge_version(mc: str) -> str:
    """The NeoForge release for `mc` installed in the lab client dir, installing the latest one
    with NeoForge's own installer if there is none."""
    prefix = ".".join(mc.split(".")[1:]) + "."  # 1.21.1 -> 21.1.
    installed = sorted((d.name.removeprefix("neoforge-") for d in (tc.CLIENT / "versions").glob(
        f"neoforge-{prefix}*") if (d / f"{d.name}.json").exists()),
        key=lambda v: [int(x) for x in re.findall(r"\d+", v)])
    if installed:
        return installed[-1]
    url = ("https://maven.neoforged.net/api/maven/latest/version/releases/"
           f"net%2Fneoforged%2Fneoforge?filter={prefix}")
    version = tc.fetch_json(url)["version"]
    installer = fetch(f"{NEOFORGE_MAVEN}/{version}/neoforge-{version}-installer.jar",
                      TOOLS / mc / f"neoforge-{version}-installer.jar")
    profiles = tc.CLIENT / "launcher_profiles.json"  # the installer refuses a dir without one
    if not profiles.exists():
        tc.CLIENT.mkdir(parents=True, exist_ok=True)
        profiles.write_text('{"profiles": {}}')
    # it writes its log next to where it runs
    subprocess.run([tc.jdk_tool("java"), "-jar", str(installer), "--install-client", str(tc.CLIENT)],
                   cwd=installer.parent, check=True, stdout=subprocess.DEVNULL)
    return version


FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
FORGE_PROMOS = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"


def forge_version(mc: str) -> str:
    """The Forge release for `mc` installed in the lab client dir (e.g. 47.4.10), installing the
    recommended one with Forge's own installer if there is none."""
    installed = sorted((d.name.removeprefix(f"{mc}-forge-") for d in (tc.CLIENT / "versions").glob(
        f"{mc}-forge-*") if (d / f"{d.name}.json").exists()),
        key=lambda v: [int(x) for x in re.findall(r"\d+", v)])
    if installed:
        return installed[-1]
    promos = tc.fetch_json(FORGE_PROMOS)["promos"]
    version = promos.get(f"{mc}-recommended") or promos[f"{mc}-latest"]
    full = f"{mc}-{version}"
    installer = fetch(f"{FORGE_MAVEN}/{full}/forge-{full}-installer.jar",
                      TOOLS / mc / f"forge-{full}-installer.jar")
    profiles = tc.CLIENT / "launcher_profiles.json"  # the installer refuses a dir without one
    if not profiles.exists():
        tc.CLIENT.mkdir(parents=True, exist_ok=True)
        profiles.write_text('{"profiles": {}}')
    subprocess.run([tc.jdk_tool("java"), "-jar", str(installer), "--installClient", str(tc.CLIENT)],
                   cwd=installer.parent, check=True, stdout=subprocess.DEVNULL)
    return version


def neoforge_classpath(mc: str) -> list[Path]:
    """NeoForge runs with Mojang names: its patched classes over the renamed client, the
    NeoForge jar and the libraries of both version files. No remapping."""
    nv = neoforge_version(mc)
    libs = tc.CLIENT / "libraries"
    neo = json.loads((tc.CLIENT / "versions" / f"neoforge-{nv}" / f"neoforge-{nv}.json")
                     .read_text(encoding="utf-8"))
    game = neo["arguments"]["game"]
    neoform = game[game.index("--fml.neoFormVersion") + 1]
    base = libs / "net" / "neoforged" / "neoforge" / nv
    renamed = libs / "net" / "minecraft" / "client" / f"{mc}-{neoform}" / f"client-{mc}-{neoform}-srg.jar"
    cp = [base / f"neoforge-{nv}-client.jar", renamed, base / f"neoforge-{nv}-universal.jar",
          *tc.libraries(neo), *tc.libraries(_version_json(mc))]
    return [p for p in cp if p.exists()]


def compile_neoforge(mc: str, sources: list[Path], out: Path, extra_cp: list[Path] = ()) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    javac(mc, [*extra_cp, *neoforge_classpath(mc)], sources, out)
    return out


def _forge_install(mc: str) -> tuple[str, dict, Path]:
    """Forge version, its version file and the MCP config version it was installed with."""
    fv = forge_version(mc)
    fj = json.loads((tc.CLIENT / "versions" / f"{mc}-forge-{fv}" / f"{mc}-forge-{fv}.json")
                    .read_text(encoding="utf-8"))
    game = fj["arguments"]["game"]
    return fv, fj, game[game.index("--fml.mcpVersion") + 1]


def forge_mappings(mc: str) -> Path:
    """tiny v2 with official, srg and named namespaces. Forge before 1.20.5 runs with SRG names:
    Mojang's class names, numbered members (m_12345_, f_12345_). The installer leaves
    official -> srg in the MCP config dir; Mojang's mappings give official -> named."""
    out = TOOLS / mc / "forge.tiny"
    if out.exists():
        return out
    _, _, mcp = _forge_install(mc)
    tsrg = (tc.CLIENT / "libraries" / "de" / "oceanlabs" / "mcp" / "mcp_config" / f"{mc}-{mcp}"
            / f"mcp_config-{mc}-{mcp}-mappings-merged.txt").read_text(encoding="utf-8")
    proguard = fetch(_version_json(mc)["downloads"]["client_mappings"]["url"],
                     TOOLS / mc / "client.txt").read_text(encoding="utf-8")
    classes, methods, fields, field_descs = parse_proguard(proguard)
    lines = ["tiny\t2\t0\tofficial\tsrg\tnamed"]
    cls = None
    for line in tsrg.splitlines()[1:]:
        if line.startswith("\t\t"):  # parameters and "static" markers
            continue
        parts = line.strip().split(" ")
        if not line.startswith("\t"):
            cls = parts[0]
            lines.append(f"c\t{cls}\t{parts[1]}\t{classes.get(cls, parts[1])}")
        elif len(parts) == 3:  # obf desc srg
            off, desc, srg = parts
            lines.append(f"\tm\t{desc}\t{off}\t{srg}\t{methods.get((cls, off, desc), srg)}")
        elif len(parts) == 2 and (cls, parts[0]) in field_descs:  # obf srg
            off, srg = parts
            lines.append(f"\tf\t{field_descs[(cls, off)]}\t{off}\t{srg}\t{fields[(cls, off)]}")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def forge_classpath(mc: str) -> tuple[list[Path], Path]:
    """Forge's patched classes, the client, Forge itself (all renamed srg -> named), its
    language and loader jars and the libraries of both version files. Also returns the
    mappings."""
    fv, fj, mcp = _forge_install(mc)
    libs = tc.CLIENT / "libraries"
    tiny = forge_mappings(mc)
    base = libs / "net" / "minecraftforge" / "forge" / f"{mc}-{fv}"
    srg_client = libs / "net" / "minecraft" / "client" / f"{mc}-{mcp}" / f"client-{mc}-{mcp}-srg.jar"
    patched = base / f"forge-{mc}-{fv}-client.jar"
    named = TOOLS / mc / "forge-named"
    client = remap(srg_client, named / srg_client.name, mc, "srg", "named", [], tiny)
    forge_client = remap(patched, named / patched.name, mc, "srg", "named", [srg_client], tiny)
    universal = base / f"forge-{mc}-{fv}-universal.jar"
    universal = remap(universal, named / universal.name, mc, "srg", "named",
                      [patched, srg_client], tiny)
    loader = [p for p in (libs / "net" / "minecraftforge").glob(f"*/{mc}-{fv}/*-{mc}-{fv}.jar")]
    cp = [forge_client, client, universal, *loader, *tc.libraries(fj),
          *tc.libraries(_version_json(mc))]
    return [p for p in cp if p.exists()], tiny


def compile_forge(mc: str, sources: list[Path], out: Path, extra_cp: list[Path] = ()) -> Path:
    """javac against Mojang names, then remap our classes named -> srg for Forge."""
    cp, tiny = forge_classpath(mc)
    cp = [*extra_cp, *cp]
    work = out.with_name(out.name + "-work")
    named = work / "named"
    javac(mc, cp, sources, named)
    jar = work / "named.jar"
    with zipfile.ZipFile(jar, "w") as z:
        for f in sorted(named.rglob("*.class")):
            z.write(f, f.relative_to(named).as_posix())
    mapped = work / "srg.jar"
    if mapped.exists():
        mapped.unlink()
    remap(jar, mapped, mc, "named", "srg", cp, tiny)
    with zipfile.ZipFile(mapped) as z:
        z.extractall(out)
    return out


def compile_fabric(mc: str, sources: list[Path], out: Path, modules: str,
                   extra_cp: list[Path] = ()) -> Path:
    """javac a Fabric mod's sources for `mc`; `out` ends with classes Fabric Loader can load
    (intermediary names on obfuscated versions)."""
    work = out.with_name(out.name + "-work")
    cp = [*extra_cp, *fabric_classpath(mc, modules, work / "api")]
    named = work / "named" if obfuscated(mc) else out
    javac(mc, cp, sources, named)
    if obfuscated(mc):
        jar = work / "named.jar"
        with zipfile.ZipFile(jar, "w") as z:
            for f in sorted(named.rglob("*.class")):
                z.write(f, f.relative_to(named).as_posix())
        mapped = to_intermediary(mc, jar, work / "intermediary.jar", cp)
        with zipfile.ZipFile(mapped) as z:
            z.extractall(out)
    return out
