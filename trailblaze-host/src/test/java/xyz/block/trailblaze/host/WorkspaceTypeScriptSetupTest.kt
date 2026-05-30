package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [WorkspaceTypeScriptSetup] — extracts the vendored TypeScript SDK declaration
 * bundle (`dist/index.d.ts`) to `<workspaceRoot>/.trailblaze/sdk/dist/` once per workspace.
 * No per-trailmap `bun install`, no `node_modules/`, no workspace `tsconfig.base.json`.
 *
 * Coverage:
 *  - Extraction writes the declaration bundle under `<workspaceRoot>/.trailblaze/sdk/dist/`.
 *  - Extraction is idempotent — second call leaves mtimes unchanged.
 *  - `setUp` returns the absolute path of the extracted bundle.
 *  - Stale files from a prior SDK shape get pruned.
 */
class WorkspaceTypeScriptSetupTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `extractSdk writes the declaration bundle under dot-trailblaze-sdk-dist`() {
    val workspace = newWorkspaceRoot()

    val sdkDir = WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())

    val sdkRoot = File(workspace, ".trailblaze/sdk")
    assertTrue(sdkRoot.isDirectory, "expected SDK root at $sdkRoot")
    assertEquals(sdkRoot.absolutePath, sdkDir.toAbsolutePath().toString())

    // The declaration bundle that ships from `:trailblaze-models`'s
    // `copyTypescriptSdkResources` task lands at this fixed relative path. Per-trailmap
    // tsconfigs hard-code it as the `paths` target.
    val bundle = File(sdkRoot, "dist/index.d.ts")
    assertTrue(bundle.isFile, "expected bundled .d.ts at $bundle")

    val content = bundle.readText()
    // Spot-check the bundle's well-known content — both the dts-bundle-generator
    // header banner AND the `TrailblazeToolMap` interface re-exported from the SDK.
    // Either one disappearing means the bundle shipping in the JAR is corrupted or
    // the generator argv drifted.
    assertTrue(
      content.contains("dts-bundle-generator"),
      "expected the generator banner in the bundle; got first 200 chars: ${content.take(200)}",
    )
    assertTrue(
      content.contains("TrailblazeToolMap"),
      "expected the public SDK type surface in the bundle; got first 200 chars: ${content.take(200)}",
    )

    // Runtime ESM companion to the declaration bundle. Per-trailmap tsconfigs' `paths`
    // mapping resolves the stem `dist/index` — bun loads `index.js`, tsc loads
    // `index.d.ts`. The extractor walks the classpath prefix recursively, so this
    // file flows through the same code path with no special wiring.
    val runtime = File(sdkRoot, "dist/index.js")
    assertTrue(runtime.isFile, "expected runtime ESM bundle at $runtime")
    val runtimeContent = runtime.readText()
    // Assert the actual ESM `export { trailblaze }` (or `export ... trailblaze`) shape
    // rather than a bare substring — a bundle that lost the runtime namespace export
    // but kept the identifier inside a comment, a string literal, or an internal
    // closure would otherwise pass a `.contains("trailblaze")` check and silently
    // ship a non-functional SDK to the workspace.
    val exportsTrailblaze = Regex("""export\s*\{[^}]*\btrailblaze\b""").containsMatchIn(runtimeContent) ||
      Regex("""export\s+(?:const|let|var|function)\s+trailblaze\b""").containsMatchIn(runtimeContent)
    assertTrue(
      exportsTrailblaze,
      "expected the SDK runtime to ESM-export the `trailblaze` namespace; got first 500 chars: " +
        runtimeContent.take(500),
    )
  }

  @Test
  fun `extractSdk is idempotent — re-run with same input doesn't churn mtimes`() {
    val workspace = newWorkspaceRoot()

    val sdkDir = WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())
    val bundle = sdkDir.resolve("dist/index.d.ts")
    val firstMtime = Files.getLastModifiedTime(bundle)

    Thread.sleep(1_100) // bigger than 1s mtime granularity on older filesystems

    WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())
    val secondMtime = Files.getLastModifiedTime(bundle)

    assertEquals(
      firstMtime,
      secondMtime,
      "Idempotent extractor should not rewrite files on identical input " +
        "(firstMtime=$firstMtime, secondMtime=$secondMtime)",
    )
  }

  @Test
  fun `setUp returns the bundle path along with the SDK dir`() {
    val workspace = newWorkspaceRoot()

    val result = WorkspaceTypeScriptSetup.setUp(workspace.toPath())

    val sdkRoot = File(workspace, ".trailblaze/sdk")
    val bundle = File(sdkRoot, "dist/index.d.ts")
    assertTrue(bundle.isFile, "expected declaration bundle at $bundle")
    assertEquals(sdkRoot.absolutePath, result.sdkDir.toAbsolutePath().toString())
    assertEquals(bundle.absolutePath, result.sdkDtsBundle.toAbsolutePath().toString())
  }

  @Test
  fun `setUp prunes a stale workspace tsconfig base left behind from the prior layout`() {
    // Pre-bundled-.d.ts workspaces had a `.trailblaze/tsconfig.base.json` that per-trailmap
    // tsconfigs `extends:`-ed. After this PR no workspace base is written, but the old
    // file would otherwise linger forever — cruft for authors and a foot-gun for anyone
    // debugging tsconfig resolution who finds a stale base file the framework no longer
    // reads. The setup must garbage-collect it.
    val workspace = newWorkspaceRoot()
    val staleBase = File(workspace, ".trailblaze/tsconfig.base.json").apply {
      parentFile.mkdirs()
      writeText("""{ "compilerOptions": { "strict": true } }""")
    }
    assertTrue(staleBase.isFile, "fixture should pre-populate the stale file")

    WorkspaceTypeScriptSetup.setUp(workspace.toPath())

    assertFalse(
      staleBase.exists(),
      "expected the stale workspace tsconfig.base.json to be pruned; found it at $staleBase",
    )
  }

  @Test
  fun `setUp is idempotent when no stale tsconfig base exists — fresh workspaces`() {
    // Most workspaces (post-first-run, or anyone who started on the bundled-.d.ts
    // shape) have nothing to prune. setUp must complete cleanly and the daemon must
    // not log a misleading "Pruned X" line for a file that was never there.
    val workspace = newWorkspaceRoot()

    WorkspaceTypeScriptSetup.setUp(workspace.toPath())
    // Second call to confirm the absent path stays absent — guards against future
    // refactors that would write the file back ("for compatibility").
    WorkspaceTypeScriptSetup.setUp(workspace.toPath())

    val workspaceTsconfigBase = File(workspace, ".trailblaze/tsconfig.base.json")
    assertFalse(
      workspaceTsconfigBase.exists(),
      "fresh workspace should never have a tsconfig.base.json materialized; found one at $workspaceTsconfigBase",
    )
  }

  @Test
  fun `setUp tolerates a corrupt-workspace directory at the legacy tsconfig base path`() {
    // Defensive: if a user accidentally `mkdir .trailblaze/tsconfig.base.json` (or a
    // corrupt git checkout produces the same shape), the prune step would otherwise
    // tear down daemon startup with `DirectoryNotEmptyException`. The framework
    // shouldn't repair corrupt workspaces, but it also shouldn't crash on them — the
    // guard skips non-regular-file paths so the rest of setUp can proceed.
    val workspace = newWorkspaceRoot()
    val collision = File(workspace, ".trailblaze/tsconfig.base.json").apply {
      mkdirs()
      File(this, "stray.txt").writeText("noise") // make the dir non-empty
    }
    assertTrue(collision.isDirectory, "fixture should pre-populate the colliding dir")

    // Should not throw — guard at the top of pruneStaleWorkspaceTsconfigBase skips the path.
    WorkspaceTypeScriptSetup.setUp(workspace.toPath())

    assertTrue(
      collision.isDirectory,
      "the colliding directory should be left untouched (framework doesn't repair corrupt workspaces)",
    )
  }

  @Test
  fun `setUp does not write a workspace tsconfig base — per-trailmap tsconfigs are self-contained`() {
    // Regression coverage for the npm-portability cut: a workspace `tsconfig.base.json`
    // would force every per-trailmap tsconfig to `extends:` a known relative path, which
    // breaks when a trailmap is published to npm and installed into a different workspace.
    // The bundled-.d.ts approach makes per-trailmap tsconfigs fully self-contained — see
    // [PerTrailmapTsconfigEmitter.renderTsconfig] — so no workspace base file is written.
    val workspace = newWorkspaceRoot()

    WorkspaceTypeScriptSetup.setUp(workspace.toPath())

    val workspaceTsconfigBase = File(workspace, ".trailblaze/tsconfig.base.json")
    assertFalse(
      workspaceTsconfigBase.exists(),
      "expected NO workspace tsconfig.base.json to be written; found one at $workspaceTsconfigBase",
    )
  }

  @Test
  fun `extractSdk prunes stale files from a prior SDK shape`() {
    // Framework upgrade case: the SDK ships file X today and stops shipping it
    // tomorrow. The next bootstrap should garbage-collect the stale file so module
    // resolution doesn't pick it up. Without this guarantee, a v1.2 → v1.3 SDK upgrade
    // could leave an `oldHelper.d.ts` shadowing the new shape.
    val workspace = newWorkspaceRoot()

    val sdkDir = WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())
    val staleFile = File(sdkDir.toFile(), "dist/_stale_helper.d.ts").apply {
      parentFile.mkdirs()
      writeText("// Synthetic stale file — should disappear on next extractSdk.")
    }
    assertTrue(staleFile.isFile, "stale fixture file should land")

    WorkspaceTypeScriptSetup.extractSdk(workspace.toPath())

    assertFalse(staleFile.exists(), "expected stale file to be pruned on re-extract")
    // Real SDK file should still be intact after the prune walk.
    assertTrue(File(sdkDir.toFile(), "dist/index.d.ts").isFile)
  }

  @Test
  fun `setUp does NOT extract the tsc payload — that's a separate opt-in call`() {
    // The bundled tsc is ~6 MB of resource I/O that only `trailblaze typecheck` needs.
    // Folding it into `setUp` would force every `trailblaze compile` and daemon-init
    // bootstrap to pay the same cost — so the contract is that `setUp` writes the SDK
    // and nothing else, and `extractTypecheck` is called explicitly by the typecheck
    // command.
    val workspace = newWorkspaceRoot()

    WorkspaceTypeScriptSetup.setUp(workspace.toPath())

    val typecheckDir = File(workspace, ".trailblaze/typecheck")
    assertFalse(
      typecheckDir.isDirectory,
      "setUp must not extract typecheck/ — only `extractTypecheck` does. Found at $typecheckDir",
    )
  }

  @Test
  fun `extractTypecheck writes the bundled tsc payload under dot-trailblaze-typecheck when shipped`() {
    // The framework JAR ships typescript@6.0.3's `_tsc.js` + `lib.*.d.ts` via the
    // `copyTypescriptCompilerResources` Gradle task in `:trailblaze-host`. CI's
    // `pr_static_checks.sh` runs `bun install` in `sdks/typescript/` before the Gradle
    // build, so the payload is always populated in CI runs of this test. A local dev who
    // hasn't run `bun install` will see `extractTypecheck` return `null` — the early
    // return below skips assertions in that case so the test stays passing.
    val workspace = newWorkspaceRoot()

    val tscJs = WorkspaceTypeScriptSetup.extractTypecheck(workspace.toPath()) ?: return

    val tscRoot = File(workspace, ".trailblaze/typecheck/typescript")
    assertTrue(tscRoot.isDirectory, "expected typecheck root at $tscRoot")
    assertTrue(File(tscRoot, "lib/_tsc.js").isFile, "expected lib/_tsc.js")
    assertTrue(
      File(tscRoot, "lib/_tsc.js").length() > 100_000,
      "expected non-trivial _tsc.js (got ${File(tscRoot, "lib/_tsc.js").length()} bytes)",
    )
    assertTrue(tscJs.toString().endsWith("lib/_tsc.js"))
  }

  @Test
  fun `extractTypecheck prunes stale files from a prior tsc payload`() {
    // Framework upgrade case for the bundled tsc payload, mirroring the SDK prune test
    // a few rows up. A future framework version that drops e.g. `lib/lib.es5.d.ts`
    // should garbage-collect the stale file so module resolution doesn't pick it up.
    // Without this guarantee, a v1.2 → v1.3 tsc upgrade could leave the stale file
    // shadowing the new layout.
    val workspace = newWorkspaceRoot()

    val tscRoot = WorkspaceTypeScriptSetup.extractTypecheck(workspace.toPath()) ?: return
    val staleFile = File(tscRoot.toFile().parentFile, "_stale_helper.d.ts").apply {
      parentFile.mkdirs()
      writeText("// Synthetic stale file — should disappear on next extractTypecheck.")
    }
    assertTrue(staleFile.isFile, "stale fixture file should land")

    WorkspaceTypeScriptSetup.extractTypecheck(workspace.toPath())

    assertFalse(staleFile.exists(), "expected stale typecheck file to be pruned on re-extract")
    // Real tsc entry point should still be intact after the prune walk.
    assertTrue(File(workspace, ".trailblaze/typecheck/typescript/lib/_tsc.js").isFile)
  }

  private fun newWorkspaceRoot(): File {
    val dir = createTempDirectory("workspace-typescript-setup-test").toFile()
    tempDirs += dir
    val trailsDir = File(dir, "trails")
    trailsDir.mkdirs()
    return trailsDir
  }
}
