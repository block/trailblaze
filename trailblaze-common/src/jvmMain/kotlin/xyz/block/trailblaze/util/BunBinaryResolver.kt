package xyz.block.trailblaze.util

import java.io.File

/**
 * Resolves the `bun` binary Trailblaze spawns for every TypeScript-touching subprocess — the
 * scripted-tool analyzer, the LSP routes, and the interactive report renderer. Shared so every
 * caller agrees on the same PATH-then-repo-walk-up contract instead of drifting copies.
 *
 * **Bun is the only JS runtime Trailblaze uses.** `setup.sh` installs bun on every Runway CI
 * agent, so there's no behavior incentive to keep a Node fallback.
 *
 * `bun.exe` is tried alongside `bun` for Windows-checkout walk-ups.
 *
 * Returns null when bun is neither on PATH nor resolvable via the repo `bin/` walk-up. Callers
 * degrade gracefully — e.g. the analyzer reports "no tools extracted" and the interactive report
 * generator falls back to the legacy report only.
 */
object BunBinaryResolver {

  fun resolveBunBinary(): File? =
    resolveBunBinary(
      pathEnv = System.getenv("PATH"),
      startDir = File(System.getProperty("user.dir") ?: ".").absoluteFile,
    )

  /**
   * Composable form of the no-arg [resolveBunBinary]: PATH first (the bun-only contract), then
   * the repo `bin/bun` walk-up from [startDir]. Pulled out (and `internal`) so a unit test can
   * pin the actual production composition — a bun on PATH short-circuits the walk-up; an absent
   * PATH bun falls through to it (the JDK-21 fresh-daemon case) — without mutating process env
   * or depending on the host's CWD. The no-arg form just feeds this the live `PATH` + CWD.
   */
  internal fun resolveBunBinary(pathEnv: String?, startDir: File): File? =
    resolveBunBinary(pathEnv) ?: resolveBunViaWalkup(startDir)

  /**
   * Overload for tests — accepts an explicit `PATH` string instead of reading from the JVM env.
   * Production callers use the no-arg form; direct callers in this module mock PATH to pin the
   * "bun-only" contract (any other runtime on PATH must NOT be picked up) without mutating
   * process env. This half is PATH-only by design — the [resolveBunViaWalkup] fallback is
   * composed in by the no-arg [resolveBunBinary].
   */
  internal fun resolveBunBinary(pathEnv: String?): File? {
    val path = pathEnv ?: return null
    val dirs = path.split(File.pathSeparator).filter { it.isNotBlank() }
    for (name in listOf("bun", "bun.exe")) {
      for (dir in dirs) {
        val candidate = File(dir, name)
        if (candidate.exists() && candidate.canExecute()) return candidate
      }
    }
    return null
  }

  /**
   * Walk-up half of [resolveBunBinary]: walk from [startDir] upward looking for the repo's
   * committed hermit `bin/bun` (or `bin/bun.exe`) symlink. The hermit symlink
   * `bin/bun -> .bun-<version>.pkg` is checked into the repo (see root CLAUDE.md "Toolchain"),
   * so a daemon launched from anywhere under the repo tree resolves it even when the spawning
   * shell never ran `source bin/activate-hermit` — closing the fresh-daemon gap where a JDK-21
   * host skipped hermit activation and shipped a daemon with no `bun` on PATH.
   *
   * **Repo-gated.** The walk-up only accepts a `bin/bun` that sits next to the repo's committed
   * Hermit activation script (`bin/activate-hermit`). Without that gate the walk-up would hand
   * back the first executable named `bin/bun` in *any* ancestor of CWD — so an installed CLI or
   * an untrusted workspace that happens to carry a `bin/bun` helper could get it executed
   * instead of cleanly degrading to the missing-bun path. `bin/activate-hermit` is committed
   * alongside the pinned `bin/bun -> .bun-<version>.pkg` symlink, so its presence in the same
   * `bin/` is a reliable marker that this is *this repo's* Hermit toolchain and not a
   * coincidental `bin/bun`.
   *
   * Pulled out (and `internal`) so a unit test can pin the walk-up against an injected starting
   * directory without depending on the host's actual CWD or repo layout. Requires the symlink
   * target be executable, matching the PATH half's `canExecute()` guard.
   */
  internal fun resolveBunViaWalkup(startDir: File): File? {
    var current: File? = startDir
    while (current != null) {
      val binDir = File(current, "bin")
      // Only trust this `bin/` if it's the repo's Hermit toolchain dir (proven by the
      // committed activation script), so we never execute an arbitrary ancestor's `bin/bun`.
      if (File(binDir, "activate-hermit").isFile) {
        for (name in listOf("bun", "bun.exe")) {
          val candidate = File(binDir, name)
          if (candidate.exists() && candidate.canExecute()) return candidate
        }
      }
      current = current.parentFile
    }
    return null
  }
}
