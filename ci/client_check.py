"""The Companion in a real Minecraft client on a CI machine (Linux under Xvfb, macOS).

Builds the mod, generates a world with Mojang's server, then starts the client three times in
one game folder, as a player would:

  1 crash     a test mod crashes the game 5 s into the world: the world is backed up before it
              loads, the crash window opens once the game is gone and disables the mod
  2 screens   the next start: the screens open one after another (welcome, health, settings), each
              captured; then "Change" opens the system folder picker
  3 picker    Linux: the picker (zenity) is answered with xdotool, as typing a path; the
              backups must move there. macOS: the picker is only seen, not answered

Screens and logs go to --out, uploaded by the workflow. Needs: a JDK 25 (MODKEEL_JDK or on
PATH), portablemc; Linux: Xvfb (run under xvfb-run), xdotool, zenity, ImageMagick.

    python ci/client_check.py --mc 26.2 --out shots
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import build  # noqa: E402
import toolchain as tc  # noqa: E402
from portablemc.fabric import FabricVersion  # noqa: E402
from portablemc.standard import Context, StandardRunner  # noqa: E402

WORLD = "My World"
TIMEOUT = 420
OPTIONS = ("onboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\n"
           "renderDistance:6\nsoundCategory_master:0.0\ntutorialStep:none\n")
CRASHED = re.compile(r"Crash report saved to|---- Minecraft Crash Report")
SHOT = re.compile(r"\[modkeel\] tour: shot (\d+) (\S+) \| (.*)")
TOUR_END = re.compile(r"\[modkeel\] tour: (done|missing .*|no capture ack.*)")
MOVED = re.compile(r"\[modkeel\] backups now in (.+) \((\d+) worlds moved from (.+)\)")
PROMPT = "Where should Modkeel save the copies of your worlds?"
LINUX = sys.platform.startswith("linux")


class Watched(StandardRunner):
    """portablemc's runner, the game's process handed to `watch` (inside process_wait: run()
    deletes the natives as soon as it returns)."""

    def __init__(self, watch, console: Path) -> None:
        self.watch = watch
        self.console = console
        self.result = None

    def process_create(self, args, work_dir):
        with open(self.console, "ab") as out:
            return subprocess.Popen(args, cwd=work_dir, stdout=out, stderr=subprocess.STDOUT)

    def process_wait(self, process):
        try:
            self.result = self.watch(process)
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(30)


def wait_for(cond, seconds: float) -> bool:
    t0 = time.time()
    while time.time() - t0 < seconds:
        if cond():
            return True
        time.sleep(0.5)
    return bool(cond())


def screenshot(out: Path) -> None:
    """The whole screen: the game, and the dialogs above it."""
    cmd = (["import", "-window", "root", str(out)] if LINUX
           else ["screencapture", "-x", str(out)])
    subprocess.run(cmd, capture_output=True, timeout=30)


def server_world(mc: str, root: Path) -> Path:
    """A fresh world from Mojang's own server for this version: the client then joins one
    that existed before Modkeel, as a player's does."""
    world = root / "world"
    if (world / "level.dat").exists():
        return world
    manifest = tc.fetch_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    meta = tc.fetch_json(next(v["url"] for v in manifest["versions"] if v["id"] == mc))
    jar = tc.fetch(meta["downloads"]["server"]["url"], root / "server.jar")
    (root / "eula.txt").write_text("eula=true\n")
    (root / "server.properties").write_text("level-name=world\nonline-mode=false\n"
                                            "server-port=0\nspawn-protection=0\n")
    srv = subprocess.Popen([tc.jdk_tool("java"), "-Xmx2G", "-jar", str(jar), "--nogui"],
                           cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                           stderr=subprocess.STDOUT, text=True)
    t0 = time.time()
    for line in srv.stdout:
        if "Done (" in line:
            srv.stdin.write("stop\n")
            srv.stdin.flush()
            break
        if time.time() - t0 > TIMEOUT:
            srv.kill()
            break
    srv.wait(120)
    if not (world / "level.dat").exists():
        sys.exit("the server did not generate a world")
    return world


class Client:
    def __init__(self, mc: str, work: Path, out: Path) -> None:
        self.mc, self.work, self.out = mc, work, out
        self.log = work / "logs" / "latest.log"

    def text(self) -> str:
        return self.log.read_text(encoding="utf-8", errors="replace") if self.log.exists() else ""

    def launch(self, name: str, props: dict[str, str], quickplay: bool, watch) -> tuple[str, str]:
        """Start the game, `watch(process)` until it returns. Returns its result and the log."""
        version = FabricVersion.with_fabric(self.mc, context=Context(tc.CLIENT, self.work))
        version.set_auth_offline("Modkeel", None)
        env = version.install()
        for k, v in props.items():
            env.jvm_args.insert(1, f"-D{k}={v}")
        if quickplay:
            env.game_args += ["--quickPlaySingleplayer", WORLD]
        self.log.unlink(missing_ok=True)
        runner = Watched(watch, self.out / f"{name}-console.txt")
        env.run(runner)
        time.sleep(1)
        text = self.text()
        (self.out / f"{name}.log").write_text(text, encoding="utf-8")
        print(f"  [{name}] {runner.result}")
        for line in re.findall(r"\[modkeel\].*", text):
            print("    " + line[:200])
        return runner.result, text

    def until(self, pattern: re.Pattern, grace: float = 0):
        def watch(p):
            t0, seen = time.time(), None
            while time.time() - t0 < TIMEOUT:
                time.sleep(1)
                if p.poll() is not None:
                    return "exited"
                if seen is None and pattern.search(self.text()):
                    seen = time.time()
                if seen is not None and time.time() - seen >= grace:
                    return "matched"
            return "timeout"
        return watch


def xdo(*args: str) -> str:
    return subprocess.run(["xdotool", *args], capture_output=True, text=True,
                          timeout=30).stdout.strip()


def answer_picker(folder: Path, out: Path) -> str:
    """Linux: find zenity's window by its title, pick the folder in its list and accept.

    Typing the path is unreliable: GTK's location field completes each segment late and
    appends the leftover ("drive-26.2/me"). The picker opens in the game dir's parent with
    the game dir selected; the folder is its sibling and sorts first, so Home selects it.
    """
    assert folder.parent.is_dir() and min(p.name for p in folder.parent.iterdir()) == folder.name
    wid = ""
    t0 = time.time()
    while not wid and time.time() - t0 < 60:
        wid = xdo("search", "--onlyvisible", "--name", re.escape(PROMPT)).split("\n")[0]
        time.sleep(0.5)
    if not wid:
        return "no picker window titled with the prompt"
    time.sleep(2)
    screenshot(out / "picker.png")
    xdo("windowfocus", "--sync", wid)
    xdo("key", "Home")
    time.sleep(0.5)
    screenshot(out / "picker-selected.png")
    xdo("key", "alt+o")
    if wait_for(lambda: not xdo("search", "--onlyvisible", "--name", re.escape(PROMPT)), 10):
        return "answered"
    screenshot(out / "picker-stuck.png")
    return "the picker did not close"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mc", default="26.2")
    ap.add_argument("--out", type=Path, default=HERE / "out")
    ap.add_argument("--no-build", action="store_true")
    args = ap.parse_args()
    mc, out = args.mc, args.out.resolve()
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    checks: list[tuple[str, bool]] = []

    def check(what: str, ok: bool) -> None:
        checks.append((what, ok))
        print(f"  {'PASS' if ok else 'FAIL'} {what}", flush=True)

    jars = ({"mod": build.mod_jar(mc), "tests": build.test_jars(mc)} if args.no_build
            else build.build(mc, "fabric", run_tests=False))
    crasher = jars["tests"][0]
    root = tc.HOME / "ci"
    work, drive = root / f"game-{mc}", root / f"drive-{mc}"
    for d in (work, drive):
        shutil.rmtree(d, ignore_errors=True)
    mods = work / "mods"
    mods.mkdir(parents=True)
    drive.mkdir(parents=True)
    for j in (jars["mod"], crasher, tc.modrinth_jar("fabric-api", mc, "fabric")):
        shutil.copy2(j, mods / j.name)
    shutil.copytree(server_world(mc, root / f"server-{mc}"), work / "saves" / WORLD)
    (work / "options.txt").write_text(OPTIONS + "lang:en_us\n")
    home = work / "modkeel"
    game = Client(mc, work, out)
    # reports go nowhere from CI
    base = {"modkeel.api": "http://127.0.0.1:9"}

    print("1 crash", flush=True)
    result, text = game.launch("1-crash", {**base, "mfcrash.after": "5",
                                           "modkeel.test.window": "disable",
                                           "modkeel.test.window_shot": str(out / "window.png")},
                               True, game.until(CRASHED, grace=3))
    check("the game crashed on the test mod", "mfcrash: deliberate test crash" in text)
    check("the world was backed up before it loaded",
          len(list((home / "backups" / WORLD).glob("*.snap"))) == 1)
    disabled = mods / (crasher.name + ".disabled")
    check("the crash window disabled the mod", wait_for(disabled.exists, 90))
    # the last shot is written as the window closes, after the mod is disabled
    check("the crash window was drawn", wait_for(lambda: all(
        (out / f"window{s}.png").exists() for s in ("", "-confirm", "-done")), 30))

    print("2 screens", flush=True)
    steps = home / "tour.txt"
    # the crash window already fixed the crash: no crash screen is left to open
    steps.write_text("open:welcome\nopen:health\nopen:settings\n"
                     "modkeel.settings.backups_change\n", encoding="utf-8")
    ack = steps.with_name(steps.name + ".ack")
    ack.unlink(missing_ok=True)
    state: dict = {}

    def screens(p):
        t0, done, stage = time.time(), 0, "tour"
        while time.time() - t0 < TIMEOUT:
            time.sleep(0.5)
            if p.poll() is not None:
                return "exited"
            text = game.text()
            for m in list(SHOT.finditer(text))[done:]:
                time.sleep(0.5)
                screenshot(out / f"{done + 1:02d}-{m.group(2)}.png")
                ack.write_text(m.group(1))
                done += 1
            end = TOUR_END.search(text)
            if end and end.group(1) != "done":
                return "tour " + end.group(1)
            if "cannot show the folder picker" in text:
                return "the game could not open the picker"
            if stage == "tour" and "tour: press modkeel.settings.backups_change" in text:
                if not LINUX:
                    time.sleep(5)
                    screenshot(out / "picker.png")
                    return "picker seen"
                state["picker"] = answer_picker(drive, out)
                if state["picker"] != "answered":
                    return state["picker"]
                stage = "moving"
            if stage == "moving" and MOVED.search(text):
                time.sleep(2)
                screenshot(out / "moved.png")
                return "moved"
        return f"timed out at {stage}"

    result, text = game.launch("2-screens", {**base, "modkeel.test.tour": str(steps)}, False,
                               screens)
    shown = [m.group(2) for m in SHOT.finditer(text)]
    check("the welcome, health and settings screens opened",
          all(any(s.startswith(n) for s in shown) for n in ("Welcome", "Health", "Settings")))
    if LINUX:
        check(f"the picker took the folder: {result}", result == "moved")
        setting = home / "backup-folder.txt"
        check("the backup folder setting was written", setting.exists()
              and Path(setting.read_text(encoding="utf-8").strip()) == drive)
        moved = [p for d in drive.iterdir() if d.is_dir() for p in (d / WORLD).glob("*.snap")]
        check("the backups moved there", len(moved) == 1)
    else:
        check(f"the picker opened: {result}", result == "picker seen")

    failed = [w for w, ok in checks if not ok]
    (out / "result.json").write_text(json.dumps(
        {"mc": mc, "platform": sys.platform, "checks": dict(checks)}, indent=2))
    print(f"{len(checks) - len(failed)}/{len(checks)} checks passed", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
