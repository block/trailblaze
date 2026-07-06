package xyz.block.trailblaze.util

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [BunBinaryResolver] — the shared PATH-then-repo-walk-up bun resolver used by the
 * scripted-tool analyzer, the LSP routes, and the interactive report renderer.
 */
class BunBinaryResolverTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  // ── resolveBunBinary — bun-only PATH walk ──────────────────────────────────
  //
  // Trailblaze uses bun as its sole JS runtime — no Node fallback. These
  // tests pin the resolver against an explicit PATH string so they don't
  // depend on the runner's actual env. The production no-arg overload reads
  // `System.getenv("PATH")` and delegates to the internal overload below.
  //
  // **Why no Node anywhere here.** The contract is "install bun; nothing
  // else is required for Trailblaze." A separate Node install must NOT be
  // picked up — both because it would mask a missing-bun environment on
  // hosts that happen to have only Node, and because we want the failure
  // mode to be the directed "install bun" diagnostic rather than a
  // potentially-surprising fallback.

  /**
   * Plant a fake executable script named [name] in [dir] so the resolver under
   * test sees a binary that passes both `exists()` and `canExecute()` without
   * having to actually run real bun/Node. Used across the [BunBinaryResolver.resolveBunBinary]
   * test block; the body is a sh stub that prints a marker, never executed by
   * production code paths.
   */
  private fun planFakeExecutable(dir: File, name: String): File =
    File(dir, name).apply {
      writeText("#!/bin/sh\necho 'fake $name'\n")
      setExecutable(true)
    }

  @Test
  fun `resolveBunBinary returns the bun binary when bun is on PATH`() {
    // Happy path — bun installed (via `brew install bun` in setup.sh on
    // Runway CI agents, the equivalent on any other host). Pre-fix this
    // case made `AnalyzerScriptedToolEnrichment.resolveFromEnvironment()`
    // return null and every meta-only / partial-descriptor trailmap failed
    // to load.
    val bunDir = tempFolder.newFolder("bun-dir")
    val bun = planFakeExecutable(bunDir, "bun")
    val resolved = BunBinaryResolver.resolveBunBinary(pathEnv = bunDir.absolutePath)
    assertEquals(bun, resolved, "expected bun to be returned")
  }

  @Test
  fun `resolveBunBinary returns null when only node is on PATH and bun is missing`() {
    // Explicit "no Node fallback" pin. A host that only has Node installed
    // (e.g. legacy developer machine, partial install) must surface the
    // missing-bun diagnostic — silently using Node would let the analyzer
    // run with subtle bun-vs-node behavior differences we've never tested
    // against, contradicting the "install bun, everything just works"
    // contract.
    val nodeOnlyDir = tempFolder.newFolder("node-only-dir")
    planFakeExecutable(nodeOnlyDir, "node")
    val resolved = BunBinaryResolver.resolveBunBinary(pathEnv = nodeOnlyDir.absolutePath)
    assertNull(
      resolved,
      "node must NOT be accepted as a JS runtime — Trailblaze requires bun. " +
        "A node-only host should surface the 'install bun' diagnostic.",
    )
  }

  @Test
  fun `resolveBunBinary picks bun from a later PATH dir`() {
    // PATH iteration coverage — bun isn't always in the first dir. The
    // resolver must walk every dir in PATH, not just the head. A regression
    // that broke into the loop after dir[0] would fail this case while still
    // passing the single-dir happy-path test above.
    val emptyFirstDir = tempFolder.newFolder("empty-first")
    val bunLaterDir = tempFolder.newFolder("bun-later")
    val bun = planFakeExecutable(bunLaterDir, "bun")
    val combinedPath = emptyFirstDir.absolutePath + File.pathSeparator + bunLaterDir.absolutePath
    val resolved = BunBinaryResolver.resolveBunBinary(pathEnv = combinedPath)
    assertEquals(bun, resolved)
  }

  @Test
  fun `resolveBunBinary returns null when bun is not on PATH`() {
    // The "no JS runtime available" terminal state — production callers
    // downgrade gracefully via `AnalyzerScriptedToolEnrichment
    // .resolveFromEnvironment()` returning null and emitting the
    // "analyzer unavailable" diagnostic. Pins the resolver's null return
    // rather than throwing or returning a stale candidate.
    val emptyDir = tempFolder.newFolder("empty-dir")
    val resolved = BunBinaryResolver.resolveBunBinary(pathEnv = emptyDir.absolutePath)
    assertNull(resolved)
  }

  @Test
  fun `resolveBunBinary returns null when PATH env is null`() {
    // Defensive — `System.getenv("PATH")` can legitimately return null in
    // sandboxed or stripped-env scenarios. Without the null guard the
    // delegate would crash; with it, every downstream call site sees the
    // same "no runtime" return as the empty-PATH case above.
    assertNull(BunBinaryResolver.resolveBunBinary(pathEnv = null))
  }

  @Test
  fun `resolveBunBinary returns null when PATH contains only separators`() {
    // Defensive — PATH like `":"` (Unix) or `";"` (Windows) splits into
    // empty-string segments. The resolver filters them via
    // `.filter { it.isNotBlank() }`, but no test pinned that contract —
    // a regression that dropped the filter would let `File("", "bun")`
    // try to open `./bun` in the JVM's cwd, which could surprise on a
    // host that happens to have a `bun` file in CWD. Same null-return
    // shape as the empty-PATH case above, but exercises the blank-segment
    // filter explicitly. (Lead-review v1 finding #3.)
    assertNull(BunBinaryResolver.resolveBunBinary(pathEnv = File.pathSeparator))
    assertNull(
      BunBinaryResolver.resolveBunBinary(
        pathEnv = File.pathSeparator + File.pathSeparator,
      ),
    )
  }

  // ── resolveBunViaWalkup — repo hermit `bin/bun` fallback ────────────────────
  //
  // The fresh-daemon fix. The `./trailblaze` wrapper spawns the daemon JVM with
  // the calling shell's PATH; on a host that already has JDK 21 the wrapper never
  // sourced `bin/activate-hermit`, so `bun` was absent from the daemon's PATH and
  // every meta-only / TS scripted-tool descriptor silently failed to enrich,
  // breaking a target's TypeScript launch-step tools that its launch orchestrator
  // composes by name.
  // The hermit `bin/bun` symlink is committed to the repo, so walking up from CWD
  // resolves it regardless of how the daemon was launched. These tests pin the
  // walk-up against an injected start dir (no dependency on the real repo layout).

  // The walk-up is gated on the committed `bin/activate-hermit` marker so it only ever
  // executes *this repo's* Hermit-pinned `bin/bun`, never a coincidental `bin/bun` in some
  // ancestor of CWD. The helper plants both the marker and an executable `bin/bun`.
  private fun planHermitBin(repoRoot: File): File {
    val bin = File(repoRoot, "bin").apply { mkdirs() }
    File(bin, "activate-hermit").writeText("#!/bin/sh\n# hermit activation marker\n")
    return planFakeExecutable(bin, "bun")
  }

  @Test
  fun `resolveBunViaWalkup finds bun at repo bin from the repo root`() {
    val repoRoot = tempFolder.newFolder("walkup-repo-root")
    val bun = planHermitBin(repoRoot)
    val resolved = BunBinaryResolver.resolveBunViaWalkup(repoRoot)
    assertEquals(bun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunViaWalkup finds bun at repo bin from a deeply nested start dir`() {
    // Mirrors the real-world daemon scenario: CWD is the repo root (or any dir
    // under it) while the walk climbs ancestors to find the committed `bin/bun`.
    val repoRoot = tempFolder.newFolder("walkup-nested-repo")
    val bun = planHermitBin(repoRoot)
    val deeplyNested =
      File(repoRoot, "module/src/main/resources/trails/config/trailmaps/foo/tools").apply { mkdirs() }
    val resolved = BunBinaryResolver.resolveBunViaWalkup(deeplyNested)
    assertEquals(bun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunViaWalkup returns null when no repo bin bun exists up the tree`() {
    val isolated = tempFolder.newFolder("walkup-no-bun")
    val nested = File(isolated, "some/empty/tree").apply { mkdirs() }
    val resolved = BunBinaryResolver.resolveBunViaWalkup(nested)
    assertNull(
      resolved,
      "expected null when no bin/bun is present up the tree; got ${resolved?.absolutePath}",
    )
  }

  @Test
  fun `resolveBunViaWalkup ignores a non-executable bin bun`() {
    // Matches the PATH half's canExecute() guard — a permission-stripped or
    // half-extracted symlink target must read as "not found" rather than handing
    // back a binary the analyzer subprocess can't actually launch.
    val repoRoot = tempFolder.newFolder("walkup-nonexec-bun")
    val bin = File(repoRoot, "bin").apply { mkdirs() }
    File(bin, "activate-hermit").writeText("#!/bin/sh\n") // present, so we reach the canExecute gate
    File(bin, "bun").apply {
      writeText("#!/bin/sh\necho nope\n")
      // Deliberately do NOT set executable.
    }
    val resolved = BunBinaryResolver.resolveBunViaWalkup(repoRoot)
    assertNull(resolved, "expected null for a non-executable bin/bun; got ${resolved?.absolutePath}")
  }

  @Test
  fun `resolveBunViaWalkup ignores a bin bun without the hermit activation marker`() {
    // Security gate (Codex review on #3929): an untrusted ancestor that carries an
    // executable `bin/bun` but is NOT a Hermit-managed repo (no `bin/activate-hermit`)
    // must be ignored, so the analyzer never executes an arbitrary project-local binary.
    val untrusted = tempFolder.newFolder("walkup-no-hermit-marker")
    val bin = File(untrusted, "bin").apply { mkdirs() }
    planFakeExecutable(bin, "bun") // executable bun, but no activate-hermit marker beside it
    val resolved = BunBinaryResolver.resolveBunViaWalkup(untrusted)
    assertNull(
      resolved,
      "expected null for a bin/bun without the hermit activation marker; got ${resolved?.absolutePath}",
    )
  }

  // ── resolveBunBinary(pathEnv, startDir) — the production composition ─────────
  //
  // The no-arg resolveBunBinary() feeds live PATH + CWD into this composition. These pin the
  // PATH-first-then-walk-up behaviour (the actual production entry point used by the daemon's
  // scripted-tool enrichment) without mutating process env.

  @Test
  fun `resolveBunBinary composition prefers a PATH bun over the repo walk-up`() {
    val pathDir = tempFolder.newFolder("compose-path-bun")
    val pathBun = planFakeExecutable(pathDir, "bun")
    // startDir is a valid hermit repo too, but the bun-only contract resolves PATH first.
    val repoRoot = tempFolder.newFolder("compose-repo-shadowed")
    planHermitBin(repoRoot)
    val resolved = BunBinaryResolver.resolveBunBinary(pathDir.absolutePath, repoRoot)
    assertEquals(pathBun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunBinary composition falls through to the repo walk-up when PATH has no bun`() {
    // The JDK-21 fresh-daemon case: hermit never activated, so PATH carries no bun and the
    // committed repo `bin/bun` must be found via the walk-up.
    val repoRoot = tempFolder.newFolder("compose-fallback-repo")
    val walkupBun = planHermitBin(repoRoot)
    val resolved = BunBinaryResolver.resolveBunBinary(null, repoRoot)
    assertEquals(walkupBun.canonicalFile, resolved?.canonicalFile)
  }

  @Test
  fun `resolveBunBinary composition returns null when neither PATH nor the walk-up resolves bun`() {
    val isolated = tempFolder.newFolder("compose-none")
    val startDir = File(isolated, "deep/dir").apply { mkdirs() }
    val resolved = BunBinaryResolver.resolveBunBinary(null, startDir)
    assertNull(resolved, "expected null when neither PATH nor the repo walk-up resolves bun")
  }
}
