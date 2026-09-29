"""What the build takes from the machine and the network: a JDK, downloads and a cache of
Minecraft files. Environment variables move it:

  MODKEEL_JDK     a JDK 25 or later (default: JAVA_HOME, then MODKEEL_HOME/jdks, then PATH)
  MODKEEL_HOME    cache root (default ~/.modkeel)
  MODKEEL_MC_DIR  the Minecraft dir loader installers write into (default MODKEEL_HOME/minecraft)
"""
from __future__ import annotations

import functools
import json
import os
import re
import shutil
import sys
import urllib.parse
import urllib.request
from pathlib import Path

UA = "Modkeel-build/0.1 (github.com/Modkeel/companion)"
HOME = Path(os.environ.get("MODKEEL_HOME", Path.home() / ".modkeel"))
CLIENT = Path(os.environ.get("MODKEEL_MC_DIR", HOME / "minecraft"))
TOOLS = HOME / "companion-tools"
MODS = HOME / "mods"
SEP = ";" if os.name == "nt" else ":"
EXE = ".exe" if os.name == "nt" else ""
JDK_MIN = 25  # Minecraft 26.x runs on Java 25; older targets compile with --release


def _jdk_version(home: Path) -> int:
    release = home / "release"
    if not (home / "bin" / f"javac{EXE}").exists() or not release.exists():
        return 0
    m = re.search(r'JAVA_VERSION="(\d+)', release.read_text(encoding="utf-8", errors="replace"))
    return int(m.group(1)) if m else 0


@functools.cache
def jdk_home() -> Path:
    candidates = [os.environ.get("MODKEEL_JDK"), os.environ.get("JAVA_HOME"),
                  *sorted(map(str, (HOME / "jdks").glob("jdk-*")), reverse=True)]
    javac = shutil.which("javac")
    if javac:
        candidates.append(str(Path(javac).resolve().parent.parent))
    for c in filter(None, candidates):
        if _jdk_version(Path(c)) >= JDK_MIN:
            return Path(c)
    sys.exit(f"the build needs JDK {JDK_MIN} or later: set MODKEEL_JDK to its home")


def jdk_tool(name: str) -> str:
    return str(jdk_home() / "bin" / (name + EXE))


def open_url(url: str, timeout: int = 120):
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA}),
                                  timeout=timeout)


def fetch(url: str, dest: Path) -> Path:
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        with open_url(url) as r:
            data = r.read()
        tmp = dest.with_suffix(dest.suffix + ".part")
        tmp.write_bytes(data)
        tmp.replace(dest)
    return dest


def fetch_json(url: str):
    with open_url(url, 60) as r:
        return json.load(r)


def modrinth_jar(project: str, mc: str, loader: str) -> Path:
    """The newest `project` file for this game version and loader."""
    q = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps([loader])})
    versions = fetch_json(f"https://api.modrinth.com/v2/project/{project}/version?{q}")
    if not versions:
        sys.exit(f"{project} has no {loader} build for Minecraft {mc} on Modrinth")
    f = next((f for f in versions[0]["files"] if f["primary"]), versions[0]["files"][0])
    return fetch(f["url"], MODS / f["filename"])


def maven_path(coord: str) -> str:
    """group:artifact:version[:classifier] -> its path in a Maven repository."""
    group, artifact, version, *classifier = coord.split(":")
    suffix = f"-{classifier[0]}" if classifier else ""
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{suffix}.jar"


def libraries(version_json: dict, root: Path | None = None) -> list[Path]:
    """The compile-time libraries of a version file, downloaded into `root` if missing.
    Platform natives (libraries with rules) are left out: javac does not need them."""
    root = root or CLIENT / "libraries"
    out = []
    for lib in version_json["libraries"]:
        if "rules" in lib or "natives" in lib:
            continue
        art = lib.get("downloads", {}).get("artifact")
        if art:
            path, url = root / art["path"], art.get("url")
        elif "url" in lib:  # plain Maven coordinates, as Fabric's profiles give them
            rel = maven_path(lib["name"])
            path, url = root / rel, lib["url"].rstrip("/") + "/" + rel
        else:
            continue
        if not path.exists() and url:
            fetch(url, path)
        out.append(path)
    return out
