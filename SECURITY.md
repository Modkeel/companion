# Security

## Reporting a problem

If you find a security problem in Modkeel Companion, please report it privately:
[open a security advisory](https://github.com/Modkeel/companion/security/advisories/new) on
this repo. Do not open a public issue for it.

You get an answer within 7 days. Once a fix is released, the advisory is published with credit
to you, unless you prefer otherwise.

Supported: the latest release. A fix for an older line ships as a new patch release.

## What the mod does on your computer

- It reads your `mods` folder and the game's logs and crash reports, and writes backups of your
  worlds and its own files under `modkeel/` in your game folder.
- It can turn a mod off by renaming its jar to `.disabled`, always telling you, and you can turn
  it back on from its screen.
- It sends nothing unless you tick "Help other players (anonymous)" when you pick a fix for a
  crash (and, later, only if you choose to always share). A report holds the crash, the fix you
  picked and whether it held: no names, file paths, logs or worlds.
- NeoForge and Forge themselves read `updates.json` from this repo to tell you about a new
  version; that request is the loader's, not Modkeel's.

## How releases are made

Every release jar is built by GitHub Actions from the tagged source, carries a signed build
provenance and a SHA-256 in `SHA256SUMS`, and can be rebuilt byte for byte from this repo. Each
release links the VirusTotal report of every jar. See "Verify a release" in the
[README](README.md#verify-a-release).
