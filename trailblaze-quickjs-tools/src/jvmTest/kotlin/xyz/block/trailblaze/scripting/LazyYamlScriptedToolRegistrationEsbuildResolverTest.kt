package xyz.block.trailblaze.scripting

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
 * OtherTrailblazeTool" — one target's step failed on a long run of consecutive main
 * builds before the gap was caught.
 *
 * The contract these tests pin:
 *  1. Walk-up finds esbuild at the **flat layout** (`sdks/typescript/...`).
 *  2. Walk-up finds esbuild at the **`opensource/`-nested layout** (`opensource/sdks/...`).
 *  3. Walk-up walks **ancestor directories**, not just the start dir.
 *  4. Walk-up returns `null` when neither layout matches anywhere up the tree.
 *  5. Walk-up requires the binary to be **executable**, not just present.
 *  6. PATH lookup short-circuits the walk-up (verified via the on-PATH helper).
 *  7. The in-process SDK entry resolves only from a tree that can ACTUALLY bundle — a source
 *     checkout whose `node_modules` were never installed yields null, so the bundler's fallback
 *     engages instead of the bundle dying on `Could not resolve "zod"`.
 *
 * A future repo reorg that adds a third SDK layout should add it to `SDK_PACKAGE_SUBPATHS`
 * (and to its sister copy in `ScriptedToolDefinitionAnalyzer.resolveSdkDir`) AND extend
 * this test with a matching case — that's what closes the diagnostic gap that hid the
 * outage above for a long run of consecutive main builds.
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

  // ── Walk-up: nested layout (the fix for that outage) ───────────────────────────────

  @Test
  fun `walk-up finds esbuild at the nested layout - opensource_sdks_typescript_node_modules`() {
    // **That regression's fix.** Before this branch was added to the walk-up, agents
    // running from a repo whose SDK was placed under an `opensource/` sub-directory
    // saw esbuild populated at `opensource/sdks/typescript/.../esbuild` by
    // `bun install` but the walk-up only looked at `sdks/typescript/...` — so `null`
    // was returned, every QuickJS inline scripted tool silently failed to register,
    // and that target's recorded scripted-tool dispatch failed at trail-time. This test is
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

  // ── In-process SDK entry walk-up (decoupled from esbuild — the slim-alias fix) ────────
  //
  // These pin [LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup], which locates
  // `sdks/typescript/src/in-process.ts` from the daemon CWD — deliberately NOT by walking up from
  // the esbuild binary. The bug it fixes: `resolveEsbuildBinary` prefers an esbuild on PATH (e.g.
  // `/opt/homebrew/bin/esbuild`); deriving the slim entry from THAT binary's location finds nothing,
  // so the bundler inlines the full ~1.2 MB SDK into every on-device bundle. The function takes no
  // esbuild argument at all, so the entry resolves regardless of where esbuild lives.
  //
  // The layout cases inject an always-usable deps gate: they are about WHICH tree the walk finds,
  // and a planted entry file has no `node_modules` beside it. The gate itself is pinned separately
  // below, where the injected value is the thing under test.

  @Test
  fun `slim-entry walk-up finds the flat layout - sdks_typescript_src`() {
    val repoRoot = tempFolder.newFolder("slim-flat-repo")
    val entry = plantFakeSlimEntry(repoRoot, "sdks/typescript/src/in-process.ts")
    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup(repoRoot, DEPS_USABLE)
    assertEquals(entry.canonicalPath, found?.canonicalPath)
  }

  @Test
  fun `slim-entry walk-up finds the nested layout - opensource_sdks_typescript_src`() {
    val repoRoot = tempFolder.newFolder("slim-nested-repo")
    val entry = plantFakeSlimEntry(repoRoot, "opensource/sdks/typescript/src/in-process.ts")
    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup(repoRoot, DEPS_USABLE)
    assertEquals(entry.canonicalPath, found?.canonicalPath)
  }

  @Test
  fun `slim-entry walk-up traverses ancestors from a deeply nested start dir`() {
    // The real shape: the daemon's CWD (or a scripted tool's dir) is many levels below the SDK.
    val repoRoot = tempFolder.newFolder("slim-walkup-repo")
    val entry = plantFakeSlimEntry(repoRoot, "sdks/typescript/src/in-process.ts")
    val deeplyNested = File(repoRoot, "module/src/main/resources/trails/config/trailmaps/foo/tools").apply { mkdirs() }
    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup(deeplyNested, DEPS_USABLE)
    assertEquals(entry.canonicalPath, found?.canonicalPath)
  }

  @Test
  fun `slim-entry walk-up returns null when no SDK source exists up the tree`() {
    val isolated = tempFolder.newFolder("no-slim-anywhere")
    val nested = File(isolated, "some/empty/tree").apply { mkdirs() }
    assertNull(
      LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup(nested, DEPS_USABLE),
      "Expected null when no SDK source is present under the temp dir",
    )
  }

  // ── The deps gate: a tree only wins if it can actually bundle ─────────────────────────
  //
  // `in-process.ts` does `export { z } from "zod"`, so aliasing `@trailblaze/scripting` to a tree
  // whose `node_modules` were never installed fails the bundle with `Could not resolve "zod"` and
  // kills the trail before its first tool call. Null instead lets DaemonScriptedToolBundler drop
  // the alias and HostScriptedToolLauncher re-enable the precompiled fallback — a fatter bundle
  // beats a dead session. This is the root cause of a false ProGuard-shrink alert, and
  // it presents as an agent-dependent flake because the bundle cache under `~/.trailblaze/`
  // outlives the checkout a CI job wipes: a warm agent never invokes esbuild at all.

  @Test
  fun `slim-entry walk-up returns null when the SDK source is present but its deps are missing`() {
    val repoRoot = tempFolder.newFolder("slim-deps-missing")
    val entry = plantFakeSlimEntry(repoRoot, "sdks/typescript/src/in-process.ts")
    val gated = mutableListOf<File>()

    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup(repoRoot) {
      gated += it
      false
    }

    assertNull(found, "an SDK tree that cannot bundle must not win the alias; got: ${found?.absolutePath}")
    // The gate is asked about the SDK PACKAGE dir (where `node_modules` lives), not the entry file
    // or its `src/` parent — a gate handed the wrong dir would look for deps that are never there
    // and reject every tree, which reads identically to "no SDK reachable".
    assertEquals(
      listOf(entry.parentFile.parentFile.canonicalPath),
      gated.map { it.canonicalPath },
      "the deps gate must be asked about the SDK package dir",
    )
  }

  @Test
  fun `slim-entry walk-up does not walk past a nearest SDK tree whose deps are missing`() {
    // An outer checkout is a DIFFERENT copy of the SDK (a worktree's parent, a sibling clone).
    // Bundling this tree's tools against that copy's version is worse than the bundler's own
    // version-matched fallback, so the nearest tree decides — mirrors resolveSdkDir's rule.
    val outerRoot = tempFolder.newFolder("nearest-decides-outer")
    val outerEntry = plantFakeSlimEntry(outerRoot, "sdks/typescript/src/in-process.ts")
    val innerRoot = File(outerRoot, "inner-checkout").apply { mkdirs() }
    val innerSdkDir = File(innerRoot, "sdks/typescript")
    plantFakeSlimEntry(innerRoot, "sdks/typescript/src/in-process.ts")

    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntryViaWalkup(innerRoot) {
      // Only the OUTER copy has deps. A walk that continued past the inner tree would find it.
      it.canonicalPath != innerSdkDir.canonicalPath
    }

    assertNull(
      found,
      "expected null rather than the outer checkout's SDK; got: ${found?.absolutePath} " +
        "(outer entry is ${outerEntry.absolutePath})",
    )
  }

  @Test
  fun `deps gate rejects a tree with no node_modules without invoking esbuild`() {
    // Pins the structural fast path in the REAL gate (no injected predicate): a cold checkout —
    // the case the gate exists for — gets a deterministic answer without paying for a subprocess.
    // The stub esbuild would report the tree USABLE if it ran, so a `true` here means the fast path
    // was removed and a subprocess decided instead.
    val sdkDir = tempFolder.newFolder("cold-checkout-sdk")
    plantFakeSlimEntry(sdkDir, "src/in-process.ts")
    val passingEsbuild = plantStubEsbuild(sdkDir, exitCode = 0)

    assertFalse(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { passingEsbuild }),
      "a tree with no node_modules at all must be rejected without asking esbuild",
    )
  }

  @Test
  fun `deps gate probes a tree that has node_modules but no zod inside it`() {
    // The fast path checks for the node_modules DIRECTORY, not `node_modules/zod`. Narrowing it to
    // zod would let a structural check answer the question the functional probe exists to answer —
    // and would reject a hoisted layout where zod resolves from an ancestor. Here the probe says
    // yes over a tree with no zod dir of its own, and the gate must take the probe's word.
    val sdkDir = tempFolder.newFolder("probed-not-structural-sdk")
    File(sdkDir, "node_modules").mkdirs()
    val passingEsbuild = plantStubEsbuild(sdkDir, exitCode = 0)

    assertTrue(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { passingEsbuild }),
      "the fast path must not require node_modules/zod — the probe is what decides",
    )
  }

  @Test
  fun `deps gate rejects a tree whose zod is installed but does not bundle`() {
    // The measured trap this gate exists for: with zod's files deleted but its `package.json`
    // kept, `bun install` reports "no changes" and exits 0 over a tree that still cannot bundle.
    // A file-existence check passes such a tree; the functional probe must not. The stub esbuild
    // stands in for the real bundler's verdict (non-zero exit = could not resolve).
    val sdkDir = tempFolder.newFolder("hollow-zod-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val failingEsbuild = plantStubEsbuild(sdkDir, exitCode = 1)

    assertFalse(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { failingEsbuild }),
      "node_modules/zod existing is not proof the tree can bundle",
    )
  }

  @Test
  fun `deps gate accepts a tree whose deps bundle`() {
    val sdkDir = tempFolder.newFolder("installed-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val passingEsbuild = plantStubEsbuild(sdkDir, exitCode = 0)

    assertTrue(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { passingEsbuild }),
      "an installed tree whose probe bundles must win the alias",
    )
  }

  @Test
  fun `deps gate accepts an installed tree when no esbuild is available to probe with`() {
    // With no esbuild anywhere, nothing can be live-bundled at all — planInlineToolRoute has
    // already routed every tool to its precompiled bundle, so the alias target is moot. Keeping
    // the structural answer here avoids demoting a perfectly good tree over a missing probe.
    val sdkDir = tempFolder.newFolder("installed-sdk-no-esbuild")
    File(sdkDir, "node_modules/zod").mkdirs()

    assertTrue(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { null }),
      "an installed tree must not be rejected just because no esbuild could probe it",
    )
  }

  @Test
  fun `deps gate runs the probe from the SDK dir and makes the import a value use`() {
    // Two things the probe cannot get wrong, captured from a stub that records its invocation:
    //  1. The working dir is the SDK package dir — that is what esbuild resolves a stdin entry's
    //     imports against, so a probe run anywhere else answers about the wrong tree.
    //  2. The probe source USES the binding. Under `--loader=ts` an unused import is elided
    //     before resolution, and the probe would pass against any tree at all (the same trap
    //     the shell-side dependency installer documents).
    val sdkDir = tempFolder.newFolder("probe-shape-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val recordingEsbuild = plantRecordingEsbuild(sdkDir)

    LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { recordingEsbuild })

    val recorded = readProbeRecord(sdkDir)
    assertEquals(sdkDir.canonicalPath, File(recorded.cwd).canonicalPath, "probe must run from the SDK dir")
    assertTrue(
      recorded.stdin.contains("import { z } from \"zod\"") && recorded.stdin.contains("console.log(z)"),
      "probe source must import zod AND use the binding; got: ${recorded.stdin}",
    )
  }

  @Test
  fun `deps gate probes with the flags that decide how a bare import resolves`() {
    // The verdict is only worth anything if it predicts the REAL bundle, and these flags are what
    // decide WHICH file a bare `zod` import resolves to: without `--bundle` esbuild never resolves
    // the import at all (the probe would pass over any tree), without `--loader=ts` it can't parse
    // the TypeScript source, and esbuild's default platform picks different package fields than
    // DaemonScriptedToolBundler.runEsbuild does. Asserted explicitly because dropping any one of
    // them leaves every other test in this file green.
    val sdkDir = tempFolder.newFolder("probe-flags-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val recordingEsbuild = plantRecordingEsbuild(sdkDir)

    LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { recordingEsbuild })

    val argv = readProbeRecord(sdkDir).argv
    listOf("--bundle", "--loader=ts", "--platform=neutral", "--main-fields=module,main").forEach { flag ->
      assertTrue(flag in argv, "probe must pass $flag so its verdict predicts the real bundle; got: $argv")
    }
  }

  @Test
  fun `probe environment withholds the in-process signing secrets and keeps everything else`() {
    // `make-test-apk` reads the keystore passwords out of the environment and resolves the SDK
    // entry in the SAME process, so an inherited environment hands them to an esbuild binary that
    // may have been walked up from an arbitrary ancestor tree — visible to it and to `ps -E`. The
    // real bundler strips the same keys (SISTER-IMPL-TAG: esbuild-withheld-secrets).
    //
    // Driven through a builder this test seeds, because the JVM's own environment can't be mutated
    // from inside it: asserting on a child's env would only prove the keys were absent from the
    // parent too, which is true on any machine and therefore proves nothing.
    val builder = ProcessBuilder("true").apply {
      environment()["TRAILBLAZE_INPROCESS_KEYSTORE_PASSWORD"] = "keystore-secret-value"
      environment()["TRAILBLAZE_INPROCESS_KEY_PASSWORD"] = "key-secret-value"
      environment()["TRAILBLAZE_INPROCESS_HARMLESS"] = "kept-value"
    }

    val env = LazyYamlScriptedToolRegistration.withholdProbeSecrets(builder).environment()

    assertFalse("TRAILBLAZE_INPROCESS_KEYSTORE_PASSWORD" in env, "the keystore password must not reach the probe child")
    assertFalse("TRAILBLAZE_INPROCESS_KEY_PASSWORD" in env, "the key password must not reach the probe child")
    // A blanket-cleared environment would pass both assertions above while breaking the probe —
    // esbuild needs PATH. Pin that only the named keys are withheld.
    assertEquals(
      "kept-value",
      env["TRAILBLAZE_INPROCESS_HARMLESS"],
      "only the named secrets are withheld; the rest of the environment is inherited",
    )
  }

  @Test
  fun `the probe child does not inherit a blanket-cleared environment`() {
    // The end-to-end half of the test above: proves the withholding is actually WIRED into the
    // probe's ProcessBuilder and that it didn't take the whole environment with it. A probe child
    // with no PATH cannot run a real esbuild's own subprocesses, and the failure would present as
    // every source checkout quietly degrading to the precompiled fallback.
    val sdkDir = tempFolder.newFolder("probe-env-wiring-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val recordingEsbuild = plantRecordingEsbuild(sdkDir)

    LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { recordingEsbuild })

    val env = readProbeRecord(sdkDir).env
    assertTrue(
      env.any { it.startsWith("PATH=") },
      "the probe child must inherit the environment minus the named secrets; got: $env",
    )
    assertFalse(
      env.any { it.startsWith("TRAILBLAZE_INPROCESS_KEYSTORE_PASSWORD=") },
      "the keystore password must not reach the probe child",
    )
  }

  @Test
  fun `deps gate reports a tree unusable when the probe hangs past its deadline`() {
    // Hang containment. A broken install can leave a binary that never exits, and an unbounded
    // probe would park session start inside a resolver — the worst place for it, because the
    // symptom is a launch that never begins rather than a tool that fails. A deadline hit reports
    // unusable, which is what routes to the bundler's fallback. The deadline is injected, so this
    // is the test's own parameter to the code under test rather than a wall-clock bet.
    val sdkDir = tempFolder.newFolder("probe-hangs-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val hangingEsbuild = File(sdkDir, "hanging-esbuild").apply {
      // `exec` so the sleeper REPLACES the shell: without it the shell is the process we hold and
      // destroyForcibly leaves a `sleep` grandchild running for its full duration after the test.
      writeText("#!/bin/sh\nexec sleep 30\n")
      setExecutable(true)
    }

    assertFalse(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(
        sdkDir,
        esbuildBinary = { hangingEsbuild },
        timeoutSeconds = 1,
      ),
      "a probe that never exits must report the tree unusable rather than parking the caller",
    )
  }

  @Test
  fun `deps gate reports a tree unusable when its bundler cannot be executed`() {
    // A binary that fails to EXEC is a verdict about the tree, not about our environment: that
    // esbuild is the one that would bundle it. Distinct from the probe being unable to run at all
    // (an unwritable temp dir), which leaves the tree in place for the bundler to judge.
    val sdkDir = tempFolder.newFolder("probe-unexecutable-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val missingEsbuild = File(sdkDir, "node_modules/.bin/esbuild")

    assertFalse(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { missingEsbuild }),
      "a bundler that cannot be launched must not be read as a usable tree",
    )
  }

  @Test
  fun `deps gate takes its verdict from the exit code when the probe never reads stdin`() {
    // A bundler that exits before draining stdin is the shape the probe's `runCatching` around the
    // stdin write exists for: the child already decided, so the exit code is the answer and a write
    // that lands nowhere must not change it. (The probe source is tens of bytes, so it fits the pipe
    // buffer and the write itself succeeds even against a dead reader — this pins the verdict, not
    // the guard. The guard covers the same shape with a payload that doesn't fit.)
    val sdkDir = tempFolder.newFolder("probe-no-stdin-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val earlyExit = { code: Int ->
      File(sdkDir, "early-exit-$code").apply {
        writeText("#!/bin/sh\nexit $code\n")
        setExecutable(true)
      }
    }

    assertTrue(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { earlyExit(0) }),
      "exit 0 without reading stdin is still a usable tree",
    )
    assertFalse(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { earlyExit(1) }),
      "exit 1 without reading stdin is still an unusable tree",
    )
  }

  @Test
  fun `deps gate leaves the tree in place when the probe is killed by a signal`() {
    // A probe the OS killed (OOM killer, a CI agent reaping the process group) answered nothing
    // about the tree. Reading that as "unusable" would silently downgrade a perfectly good checkout
    // to the precompiled fallback on a busy machine, so the tree stays in place and the real bundle
    // decides. Distinguishable from a refusal only by the exit code, which is 128+signal.
    val sdkDir = tempFolder.newFolder("probe-signalled-sdk")
    File(sdkDir, "node_modules/zod").mkdirs()
    val suicidalEsbuild = File(sdkDir, "suicidal-esbuild").apply {
      writeText("#!/bin/sh\nkill -9 ${'$'}${'$'}\n")
      setExecutable(true)
    }

    assertTrue(
      LazyYamlScriptedToolRegistration.sdkDepsUsableForBundling(sdkDir, esbuildBinary = { suicidalEsbuild }),
      "a probe killed by a signal is not a verdict about the tree",
    )
  }

  // ── In-process SDK entry: the caller-chosen esbuild reaches the gate ────

  @Test
  fun `slim-entry resolution gates the tree with the caller-supplied esbuild`() {
    // `make-test-apk --esbuild` names the binary that will bundle, and the verdict is only
    // meaningful about THAT binary — otherwise the caller's esbuild gets paired with a source tree
    // nothing verified it can resolve. Both directions asserted against the same tree, so the
    // override is what moves the answer; the environment and CWD are injected, so neither an
    // ambient TRAILBLAZE_SDK_DIR nor the runner's working directory can decide it.
    val startRoot = tempFolder.newFolder("caller-esbuild-start")
    plantFakeSlimEntry(startRoot, "sdks/typescript/src/in-process.ts")
    File(startRoot, "sdks/typescript/node_modules/zod").mkdirs()
    val binDir = tempFolder.newFolder("caller-esbuild-bin")

    assertNull(
      LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(
        esbuildOverride = plantStubEsbuild(binDir, exitCode = 1),
        sdkDirEnv = null,
        startDir = startRoot,
      ),
      "a caller-supplied esbuild that can't resolve the tree must reject it",
    )
    assertNotNull(
      LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(
        esbuildOverride = plantStubEsbuild(binDir, exitCode = 0),
        sdkDirEnv = null,
        startDir = startRoot,
      ),
      "a caller-supplied esbuild that resolves the tree must accept it",
    )
  }

  // ── In-process SDK entry: TRAILBLAZE_SDK_DIR branch (composition over the walk-up) ────

  @Test
  fun `slim-entry resolution prefers TRAILBLAZE_SDK_DIR when its src_in-process exists`() {
    val sdkDir = tempFolder.newFolder("explicit-sdk")
    val entry = File(sdkDir, "src/in-process.ts").apply { parentFile.mkdirs(); writeText("export const x = 1;\n") }
    // startDir has its OWN walk-up entry; the explicit env dir must win.
    val startRoot = tempFolder.newFolder("env-wins-start")
    plantFakeSlimEntry(startRoot, "sdks/typescript/src/in-process.ts")

    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(
      sdkDirEnv = sdkDir.absolutePath,
      startDir = startRoot,
      depsUsable = DEPS_USABLE,
    )
    assertEquals(entry.canonicalPath, found?.canonicalPath, "TRAILBLAZE_SDK_DIR/src/in-process.ts must take precedence")
  }

  @Test
  fun `slim-entry resolution falls through to the walk-up when TRAILBLAZE_SDK_DIR lacks the entry`() {
    // Env dir set but has no src/in-process.ts → must NOT short-circuit to null; fall through to CWD walk-up.
    val emptySdkDir = tempFolder.newFolder("explicit-sdk-empty")
    val startRoot = tempFolder.newFolder("fallthrough-start")
    val walkupEntry = plantFakeSlimEntry(startRoot, "sdks/typescript/src/in-process.ts")

    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(
      sdkDirEnv = emptySdkDir.absolutePath,
      startDir = startRoot,
      depsUsable = DEPS_USABLE,
    )
    assertEquals(walkupEntry.canonicalPath, found?.canonicalPath, "expected fall-through to the walk-up, not null")
  }

  @Test
  fun `slim-entry resolution returns null for a TRAILBLAZE_SDK_DIR whose deps are missing`() {
    // An override names ONE SDK: "alias @trailblaze/scripting to THIS tree". When that tree cannot
    // bundle, quietly substituting a copy found above the CWD would mix the caller's tools with
    // another checkout's SDK version. Fall-through is reserved for an override that isn't an SDK
    // package at all (the test above) — a stale path rather than a version statement.
    val sdkDir = tempFolder.newFolder("explicit-sdk-no-deps")
    File(sdkDir, "src/in-process.ts").apply { parentFile.mkdirs(); writeText("export const x = 1;\n") }
    val startRoot = tempFolder.newFolder("override-null-start")
    val walkupEntry = plantFakeSlimEntry(startRoot, "sdks/typescript/src/in-process.ts")

    val found = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(
      sdkDirEnv = sdkDir.absolutePath,
      startDir = startRoot,
      // Everything BUT the named tree has deps, so a fall-through would resolve the walk-up entry.
      depsUsable = { it.canonicalPath != sdkDir.canonicalPath },
    )

    assertNull(
      found,
      "expected null, not the walk-up's ${walkupEntry.absolutePath}; got: ${found?.absolutePath}",
    )
  }

  @Test
  fun `slim-entry resolution ignores a null or blank TRAILBLAZE_SDK_DIR and uses the walk-up`() {
    val startRoot = tempFolder.newFolder("blank-env-start")
    val walkupEntry = plantFakeSlimEntry(startRoot, "sdks/typescript/src/in-process.ts")

    assertEquals(
      walkupEntry.canonicalPath,
      LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(null, startRoot, DEPS_USABLE)?.canonicalPath,
    )
    assertEquals(
      walkupEntry.canonicalPath,
      LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry("   ", startRoot, DEPS_USABLE)?.canonicalPath,
    )
  }

  // ── helpers ─────────────────────────────────────────────────────────────────────────

  private fun plantFakeSlimEntry(root: File, relativePath: String): File =
    File(root, relativePath).apply {
      parentFile.mkdirs()
      writeText("export const slim = true;\n")
    }

  /** A stub bundler that reads its stdin (so the probe's write never blocks) and exits [exitCode]. */
  private fun plantStubEsbuild(dir: File, exitCode: Int): File =
    File(dir, "stub-esbuild-$exitCode").apply {
      writeText("#!/bin/sh\ncat > /dev/null\nexit $exitCode\n")
      setExecutable(true)
      require(canExecute()) { "Test fixture failed to make the stub esbuild executable at $absolutePath" }
    }

  /**
   * A stub bundler that dumps how it was invoked — working dir, argv, environment, and the source
   * on stdin — into three files under [dir], then exits 0.
   *
   * Files rather than stdout because the probe redirects the child's stdout to its own throwaway
   * log and deletes it; the recording has to outlive the call.
   */
  private fun plantRecordingEsbuild(dir: File): File =
    File(dir, "recording-esbuild").apply {
      writeText(
        """
        #!/bin/sh
        pwd > "${dir.absolutePath}/probe-cwd.txt"
        for arg in "${'$'}@"; do echo "${'$'}arg"; done > "${dir.absolutePath}/probe-argv.txt"
        env > "${dir.absolutePath}/probe-env.txt"
        cat > "${dir.absolutePath}/probe-stdin.txt"
        exit 0
        """.trimIndent() + "\n",
      )
      setExecutable(true)
      require(canExecute()) { "Test fixture failed to make the recording esbuild executable at $absolutePath" }
    }

  private class ProbeRecord(val cwd: String, val argv: List<String>, val env: List<String>, val stdin: String)

  private fun readProbeRecord(dir: File) = ProbeRecord(
    cwd = File(dir, "probe-cwd.txt").readText().trim(),
    argv = File(dir, "probe-argv.txt").readLines().filter { it.isNotBlank() },
    env = File(dir, "probe-env.txt").readLines().filter { it.isNotBlank() },
    stdin = File(dir, "probe-stdin.txt").readText(),
  )

  private companion object {
    /** Deps gate for the layout/precedence cases, which are not about the gate. */
    val DEPS_USABLE: (File) -> Boolean = { true }
  }

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
