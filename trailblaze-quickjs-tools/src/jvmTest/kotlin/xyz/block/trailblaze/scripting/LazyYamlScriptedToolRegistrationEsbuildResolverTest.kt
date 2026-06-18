package xyz.block.trailblaze.scripting

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression net for [LazyYamlScriptedToolRegistration.resolveEsbuildBinary] —
 * specifically the **walk-up** half, which on a CI agent without esbuild on PATH is
 * the only path that resolves the bundler binary.
 *
 * **Why this test exists.** The walk-up originally only recognized the flat layout
 * (`sdks/typescript/node_modules/.bin/esbuild`). After a consumer reorganized the SDK
 * under an `opensource/` sub-directory, the walk-up silently missed
 * `opensource/sdks/typescript/...`. On agents without esbuild on PATH,
 * `resolveEsbuildBinary` returned `null`, the inline-tool registration silently no-op'd
 * in `TrailblazeHostYamlRunner`, and every TS-migrated scripted tool failed at trail
 * dispatch time with the cryptic "Unsupported tool type for RPC execution:
 * OtherTrailblazeTool" — the K1 step failed on 30+ consecutive main builds before the
 * gap was caught.
 *
 * The contract these tests pin:
 *  1. Walk-up finds esbuild at the **flat layout** (`sdks/typescript/...`).
 *  2. Walk-up finds esbuild at the **`opensource/`-nested layout** (`opensource/sdks/...`).
 *  3. Walk-up walks **ancestor directories**, not just the start dir.
 *  4. Walk-up returns `null` when neither layout matches anywhere up the tree.
 *  5. Walk-up requires the binary to be **executable**, not just present.
 *  6. PATH lookup short-circuits the walk-up (verified via the on-PATH helper).
 *
 * A future repo reorg that adds a third SDK layout should add it to the relative-
 * candidate list in [LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup] AND
 * extend this test with a matching case — that's what closes the diagnostic gap that
 * hid the K1 outage through 30+ consecutive main builds.
 */
class LazyYamlScriptedToolRegistrationEsbuildResolverTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  // ── Walk-up: flat layout ───────────────────────────────────────────────────────────

  @Test
  fun `walk-up finds esbuild at the flat layout - sdks_typescript_node_modules`() {
    val repoRoot = tempFolder.newFolder("flat-layout-repo")
    val esbuild = plantFakeEsbuild(repoRoot, "sdks/typescript/node_modules/.bin/esbuild")

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(repoRoot)
    assertEquals(esbuild.canonicalPath, found?.canonicalPath)
  }

  @Test
  fun `walk-up finds flat-layout esbuild from a deeply nested start dir`() {
    // Mirrors the real-world scenario where the daemon's CWD is the repo root but
    // a scripted-tool source's parent dir is many levels deeper. Walk-up must
    // traverse ancestors, not just check the start dir.
    val repoRoot = tempFolder.newFolder("walk-up-flat-repo")
    val esbuild = plantFakeEsbuild(repoRoot, "sdks/typescript/node_modules/.bin/esbuild")
    val deeplyNested = File(repoRoot, "module/src/main/resources/trails/config/trailmaps/foo/tools").apply { mkdirs() }

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(deeplyNested)
    assertEquals(esbuild.canonicalPath, found?.canonicalPath)
  }

  // ── Walk-up: nested layout (the K1 fix) ────────────────────────────────────────────

  @Test
  fun `walk-up finds esbuild at the nested layout - opensource_sdks_typescript_node_modules`() {
    // **The K1 regression fix.** Before this branch was added to the walk-up, agents
    // running from a repo whose SDK was placed under an `opensource/` sub-directory
    // saw esbuild populated at `opensource/sdks/typescript/.../esbuild` by
    // `bun install` but the walk-up only looked at `sdks/typescript/...` — so `null`
    // was returned, every QuickJS inline scripted tool silently failed to register,
    // and K1's recorded scripted-tool dispatch failed at trail-time. This test is
    // the mechanical pin that closes the gap.
    val repoRoot = tempFolder.newFolder("nested-layout-repo")
    val esbuild = plantFakeEsbuild(repoRoot, "opensource/sdks/typescript/node_modules/.bin/esbuild")

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(repoRoot)
    assertEquals(esbuild.canonicalPath, found?.canonicalPath)
  }

  @Test
  fun `walk-up finds nested-layout esbuild from a deeply nested start dir`() {
    val repoRoot = tempFolder.newFolder("nested-walkup-repo")
    val esbuild = plantFakeEsbuild(repoRoot, "opensource/sdks/typescript/node_modules/.bin/esbuild")
    // Approximates the deep tree where a scripted tool's `.ts` source lives —
    // matches the typical `<module>/src/main/resources/trails/config/...` shape a
    // multi-module Gradle build produces.
    val deeplyNested = File(repoRoot, "module/src/main/resources/trails/config/trailmaps/foo/tools").apply { mkdirs() }

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(deeplyNested)
    assertEquals(esbuild.canonicalPath, found?.canonicalPath)
  }

  // ── Walk-up: flat layout wins over nested layout when both present ─────────────────

  @Test
  fun `walk-up returns flat layout when BOTH layouts are present at the same level`() {
    // Order parity check: when a hypothetical repo has both layouts populated (e.g.
    // a flat-layout submodule under a nested-layout monorepo), the flat candidate is
    // tried first because it's shorter and matches the framework's documented tree
    // shape. A regression that reversed the candidate-list order would still resolve
    // esbuild SOMEWHERE but could subtly change which binary a fresh contributor sees
    // if their checkout has both populated.
    val repoRoot = tempFolder.newFolder("both-layouts-repo")
    val flatLayout = plantFakeEsbuild(repoRoot, "sdks/typescript/node_modules/.bin/esbuild")
    plantFakeEsbuild(repoRoot, "opensource/sdks/typescript/node_modules/.bin/esbuild")

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(repoRoot)
    assertEquals(flatLayout.canonicalPath, found?.canonicalPath)
  }

  // ── Walk-up: not-found cases ────────────────────────────────────────────────────────

  @Test
  fun `walk-up returns null when no SDK node_modules exists anywhere up the tree`() {
    val isolated = tempFolder.newFolder("no-sdk-anywhere")
    val nested = File(isolated, "some/empty/tree").apply { mkdirs() }
    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(nested)
    // Walk reaches filesystem root without finding a candidate. The host's real
    // filesystem may or may not have one at /sdks/... in absurd cases; this test
    // is robust as long as / has no `sdks/typescript/node_modules/.bin/esbuild` —
    // which it doesn't on any sane developer or CI machine.
    assertNull(
      found,
      "Expected null when no SDK layout is present under the temp dir; got: ${found?.absolutePath}",
    )
  }

  @Test
  fun `walk-up ignores a non-executable file at the candidate path`() {
    // Defensive: the resolver should treat a non-executable file at the candidate
    // path as "not found" rather than handing back a binary the caller can't actually
    // launch. Mirrors the `canExecute()` guard in the implementation. Without this,
    // a corrupt or permission-stripped esbuild would surface as a downstream
    // "esbuild process exited 126" with no hint that the resolution itself was the
    // problem.
    val repoRoot = tempFolder.newFolder("non-executable-esbuild")
    val candidate = File(repoRoot, "sdks/typescript/node_modules/.bin/esbuild").apply {
      parentFile.mkdirs()
      writeText("#!/bin/sh\necho fake\n")
      // Deliberately do NOT call setExecutable(true).
    }
    require(!candidate.canExecute()) {
      "Test precondition: the fake esbuild must start non-executable; got canExecute=true"
    }

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildViaWalkup(repoRoot)
    assertNull(found, "Expected null for a non-executable candidate; got: ${found?.absolutePath}")
  }

  // ── PATH lookup ─────────────────────────────────────────────────────────────────────

  @Test
  fun `PATH lookup finds esbuild at a directory listed in the injected PATH`() {
    // Pins the PATH-lookup half independently of the walk-up so a regression on one
    // doesn't mask a regression on the other. The two halves compose into the full
    // `resolveEsbuildBinary` — PATH first (covers `brew install esbuild`), walk-up
    // second (covers `bun install` populating `<repo>/[opensource/]sdks/typescript/`
    // node_modules).
    val binDir = tempFolder.newFolder("fake-bin")
    val esbuild = File(binDir, "esbuild").apply {
      writeText("#!/bin/sh\nexit 0\n")
      setExecutable(true)
    }
    val pathEnv = listOf(
      "/nonexistent/first/path",
      binDir.absolutePath,
      "/nonexistent/last/path",
    ).joinToString(File.pathSeparator)

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildOnPath(pathEnv)
    assertEquals(esbuild.canonicalPath, found?.canonicalPath)
  }

  @Test
  fun `PATH lookup returns null when no listed directory contains esbuild`() {
    val emptyDir = tempFolder.newFolder("path-with-no-esbuild")
    val pathEnv = emptyDir.absolutePath
    val found = LazyYamlScriptedToolRegistration.resolveEsbuildOnPath(pathEnv)
    assertNull(found, "Expected null when PATH has no esbuild; got: ${found?.absolutePath}")
  }

  @Test
  fun `PATH lookup returns null when PATH env var is unset`() {
    // Some CI agents launch the daemon under a stripped environment (e.g. `sudo -E` is
    // not used). The resolver must not NPE on a null PATH — it should defer to the
    // walk-up at the top-level entry point. This test pins the helper-level contract
    // that null PATH means "no result from this half"; the composed `resolveEsbuildBinary`
    // then falls through to the walk-up.
    val found = LazyYamlScriptedToolRegistration.resolveEsbuildOnPath(null)
    assertNull(found, "Expected null for unset PATH; got: ${found?.absolutePath}")
  }

  @Test
  fun `PATH lookup skips blank entries`() {
    // POSIX shell treats `:` as the separator and an empty entry means "current dir".
    // The resolver must not match a current-dir-relative `esbuild`; it must skip blanks
    // explicitly. Pin the behavior so a future refactor doesn't accidentally let
    // `File("", "esbuild")` resolve against whatever the JVM's CWD happens to be.
    val binDir = tempFolder.newFolder("fake-bin-with-blank")
    val esbuild = File(binDir, "esbuild").apply {
      writeText("#!/bin/sh\nexit 0\n")
      setExecutable(true)
    }
    val pathEnv = listOf(
      "",
      "  ",
      binDir.absolutePath,
    ).joinToString(File.pathSeparator)

    val found = LazyYamlScriptedToolRegistration.resolveEsbuildOnPath(pathEnv)
    assertEquals(esbuild.canonicalPath, found?.canonicalPath)
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────

  private fun plantFakeEsbuild(root: File, relativePath: String): File {
    val candidate = File(root, relativePath).apply {
      parentFile.mkdirs()
      writeText("#!/bin/sh\nexit 0\n")
      setExecutable(true)
    }
    require(candidate.canExecute()) {
      "Test fixture failed to make the fake esbuild executable at ${candidate.absolutePath}"
    }
    return candidate
  }
}
