# Trailblaze — Setup

One-time installation steps. After this, the commands in `SKILL.md`
and the rungs under `references/` will all work.

## Install the CLI

If `trailblaze` is not already on the user's PATH:

```bash
curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh | bash
```

**Required:** `curl`, `java 17+`.

**Optional** (full recording / replay / video fidelity — Homebrew users):

```bash
brew install bun esbuild ffmpeg
```

## Verify

```bash
trailblaze --version
```

Should print a version string. If not, re-check that the install
script finished without errors and that the install location is on
your `PATH`.
