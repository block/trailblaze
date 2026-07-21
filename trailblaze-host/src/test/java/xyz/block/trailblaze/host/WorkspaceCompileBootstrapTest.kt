package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.scripting.MetaOnlyDescriptorTestFixture

class WorkspaceCompileBootstrapTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * Toolset names referenced by these fixture trailmaps (`core_interaction`, `verification`)
   * are real toolsets shipped on the framework classpath, so the compiler's reference
   * validation passes in the host module's test classpath.
   */
  private fun writeFixtureTrailmap(trailmapsDir: File, id: String) {
    val trailmapDir = File(trailmapsDir, id).apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: $id
      target:
        display_name: ${id.replaceFirstChar { it.uppercase() }} App
        platforms:
          android:
            app_ids:
              - com.example.$id
            tool_sets:
              - core_interaction
      """.trimIndent(),
    )
  }

  private fun writeLibraryTrailmap(trailmapsDir: File, id: String) {
    val trailmapDir = File(trailmapsDir, id).apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: $id
      platforms:
        android:
          tool_sets:
            - core_interaction
      exports: []
      """.trimIndent(),
    )
  }

  private fun targetsDir(configDir: File): File =
    File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_TARGETS_SUBPATH)

  private fun hashFile(configDir: File): File =
    File(
      configDir,
      "${TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR}/${WorkspaceCompileBootstrap.HASH_FILENAME}",
    )

  private fun targetsManifest(configDir: File): File =
    File(
      configDir,
      "${TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR}/${WorkspaceCompileBootstrap.TARGETS_FILENAME}",
    )

  private fun codegenManifest(configDir: File): File =
    File(
      configDir,
      "${TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR}/${WorkspaceCompileBootstrap.CODEGEN_FILES_FILENAME}",
    )

  private fun codegenHash(configDir: File): File =
    File(
      configDir,
      "${TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR}/${WorkspaceCompileBootstrap.CODEGEN_HASH_FILENAME}",
    )

  @Test
  fun `trailmap referencing a workspace-authored toolset compiles cleanly`() {
    // Regression: before WorkspaceCompileBootstrap wired its referenceSource to the
    // workspace filesystem, a trailmap that referenced its own
    // `<workspace>/trails/config/trailmaps/<id>/toolsets/<name>.yaml` would compile-fail with
    // "unknown toolset" because the compiler defaulted to a classpath-only resource source.
    // The end-user reproducer: a `wikipedia_extras` workspace toolset listed in a trailmap's
    // `platforms.android.tool_sets:` and authored as a workspace file rather than a classpath
    // resource. The trailmap-resolved toolset wasn't on the classpath, so reference
    // validation rejected it even though it sat right next to the trailmap on disk.
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    val trailmapDir = File(trailmapsDir, "workspaceapp").apply { mkdirs() }
    val toolsetsDir = File(trailmapDir, "toolsets").apply { mkdirs() }
    File(toolsetsDir, "workspace_extras.yaml").writeText(
      """
      id: workspace_extras
      description: "Workspace-authored toolset that only exists on disk."
      drivers:
        - android-ondevice-instrumentation
      tools:
        - launchApp
      """.trimIndent(),
    )
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: workspaceapp
      target:
        display_name: Workspace App
        platforms:
          android:
            app_ids:
              - com.example.workspace
            tool_sets:
              - workspace_extras
      """.trimIndent(),
    )

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
    assertTrue(
      File(targetsDir(configDir), "workspaceapp.yaml").isFile,
      "Compile must succeed and emit the target YAML when the trailmap's referenced toolset " +
        "lives only on the workspace filesystem, not the classpath.",
    )
  }

  @Test
  fun `no trailmaps directory returns NoWorkspaceTrailmaps`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.NoWorkspaceTrailmaps, result)
  }

  @Test
  fun `empty trailmaps directory returns NoWorkspaceTrailmaps`() {
    val configDir = tempFolder.newFolder("trails", "config")
    File(configDir, TrailblazeConfigPaths.TRAILMAPS_SUBDIR).mkdirs()
    // Subdir without a trailmap.yaml — should not count.
    File(configDir, "${TrailblazeConfigPaths.TRAILMAPS_SUBDIR}/scratch").mkdirs()

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.NoWorkspaceTrailmaps, result)
    // Critical: bootstrap must NOT print "Recompiling..." or call compile when there
    // are no manifests, otherwise the codex / Copilot zero-trailmap regression returns —
    // every startup would re-run compile against an empty input set forever.
    assertTrue(!hashFile(configDir).exists(), "no-trailmap workspace should not produce a hash file")
  }

  @Test
  fun `first run with no hash compiles and emits one yaml per app trailmap`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    writeFixtureTrailmap(trailmapsDir, "beta")

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 2), result)
    assertTrue(File(targetsDir(configDir), "alpha.yaml").isFile)
    assertTrue(File(targetsDir(configDir), "beta.yaml").isFile)
    assertTrue(hashFile(configDir).isFile)
    assertEquals(listOf("alpha.yaml", "beta.yaml"), targetsManifest(configDir).readLines())
  }

  @Test
  fun `second run with unchanged manifests skips compile`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    val targetFile = File(targetsDir(configDir), "alpha.yaml")
    val firstMtime = targetFile.lastModified()

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.UpToDate, result)
    // Skip path didn't rewrite the materialized target.
    assertEquals(firstMtime, targetFile.lastModified())
  }

  @Test
  fun `library trailmaps do not force every unchanged startup to recompile`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    writeLibraryTrailmap(trailmapsDir, "shared")

    assertEquals(
      WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1),
      WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0"),
    )
    assertEquals(listOf("alpha.yaml"), targetsManifest(configDir).readLines())

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.UpToDate, result)
  }

  @Test
  fun `UpToDate path regenerates a deleted client_d_ts and re-extracts a deleted SDK`() {
    // A user who manually deletes their per-trailmap `trailblaze-client.d.ts` or the workspace SDK
    // shouldn't need to edit an input to get them back — the next daemon start regenerates them.
    // SDK setup is idempotent; typed bindings use the generated-files manifest as the missing-output
    // half of their cache contract.
    //
    // Also covers the legacy-tsconfig-base prune on the UpToDate path: a user
    // upgrading from the extends-style era who then lands on the UpToDate branch
    // (hash matches, no recompile needed) must still get their stale
    // `.trailblaze/tsconfig.base.json` pruned — the prune runs alongside SDK
    // extraction, not gated on hash drift.
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    val clientDts = File(trailmapsDir, "alpha/tools/trailblaze-client.d.ts")
    val sdkBundle = File(configDir.parentFile, ".trailblaze/sdk/dist/index.d.ts")
    assertTrue(clientDts.isFile, "expected per-trailmap trailblaze-client.d.ts emitted on first run")
    assertTrue(sdkBundle.isFile, "expected SDK declaration bundle extracted on first run")
    assertTrue(codegenManifest(configDir).isFile, "expected generated-file manifest on first run")
    assertTrue(codegenHash(configDir).isFile, "expected typed-bindings input hash on first run")

    // User wipes the per-trailmap typed bindings + workspace SDK between runs. The hash
    // file is untouched — same input manifests, same framework version. Also
    // synthesize a stale `.trailblaze/tsconfig.base.json` from the pre-bundled-.d.ts
    // era to verify the prune fires on this path.
    assertTrue(clientDts.delete())
    assertTrue(sdkBundle.delete())
    val staleTsconfigBase = File(configDir.parentFile, ".trailblaze/tsconfig.base.json")
      .apply { writeText("""{ "compilerOptions": { "strict": true } }""") }
    assertTrue(staleTsconfigBase.isFile, "fixture should pre-populate the legacy file")

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    // The compile itself is still skip-eligible (hash matches) — confirm we hit the
    // UpToDate branch rather than forcing a recompile to regenerate codegen outputs.
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.UpToDate, result)
    assertTrue(clientDts.isFile, "expected trailblaze-client.d.ts to regenerate on UpToDate path")
    assertTrue(sdkBundle.isFile, "expected SDK bundle to re-extract on UpToDate path")
    assertFalse(
      staleTsconfigBase.exists(),
      "expected stale tsconfig.base.json to be pruned on UpToDate path; still present at $staleTsconfigBase",
    )
  }

  @Test
  fun `typed bindings cache invalidates for workspace config outside bundle inputs`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    val initialCodegenHash = codegenHash(configDir).readText()

    // Workspace defaults participate in project resolution even though they do not affect the
    // narrower dist/targets bundle hash.
    File(configDir, "trailblaze.yaml").writeText("defaults:\n  target: alpha\n")
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.UpToDate, result)
    assertNotEquals(initialCodegenHash, codegenHash(configDir).readText())
  }

  @Test
  fun `NoWorkspaceTrailmaps path still extracts the workspace SDK bundle`() {
    // A fresh checkout or a classpath-only consumer can start the daemon with no
    // `trailmaps/` directory yet. The workspace SDK declaration bundle should still land so
    // the first trailmap the user authors immediately picks up IDE typing.
    val configDir = tempFolder.newFolder("trails", "config")

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.NoWorkspaceTrailmaps, result)
    val sdkBundle = File(configDir.parentFile, ".trailblaze/sdk/dist/index.d.ts")
    assertTrue(sdkBundle.isFile, "expected SDK bundle extracted even with no workspace trailmaps")
  }

  @Test
  fun `editing a manifest invalidates the hash and triggers recompile`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    // Edit the manifest's display name (a structural change that lands in the emitted YAML).
    File(trailmapsDir, "alpha/trailmap.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha Renamed
        platforms:
          android:
            app_ids:
              - com.example.alpha
            tool_sets:
              - core_interaction
      """.trimIndent(),
    )

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
    val rendered = File(targetsDir(configDir), "alpha.yaml").readText()
    assertTrue(rendered.contains("Alpha Renamed"), "expected updated displayName in: $rendered")
  }

  @Test
  fun `framework version bump invalidates hash and forces recompile`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.1.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
  }

  @Test
  fun `compile error throws WorkspaceCompileException and clears hash`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    // Trailmap declares a dependency that doesn't exist — resolver fails, compile fails.
    val brokenDir = File(trailmapsDir, "broken").apply { mkdirs() }
    File(brokenDir, "trailmap.yaml").writeText(
      """
      id: broken
      dependencies:
        - does_not_exist
      target:
        display_name: Broken App
        platforms:
          android:
            app_ids:
              - com.example.broken
            tool_sets:
              - core_interaction
      """.trimIndent(),
    )

    assertFailsWith<WorkspaceCompileBootstrap.WorkspaceCompileException> {
      WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    }

    assertTrue(!hashFile(configDir).exists(), "compile failure must not leave a stored hash")
  }

  @Test
  fun `hash differs when manifest content changes`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    val before = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    File(trailmapsDir, "alpha/trailmap.yaml").appendText("\n# trailing comment\n")
    val after = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    assertNotEquals(before, after)
  }

  @Test
  fun `hash differs when a tools-subtree ts file changes`() {
    // Regression: meta-only authoring shape (PR #3338) makes the sibling `.ts` file the
    // source of truth for `name:` / `inputSchema:` / `description:` — the analyzer reads
    // it during compile and bakes the extracted metadata into `dist/targets/<trailmap>.yaml`.
    // Editing only the `.ts` (e.g., adding an input field, updating the TSDoc) must
    // therefore invalidate the bundle hash and force a recompile; otherwise the daemon's
    // hash-skip silently keeps the stale compile until the user touches `trailmap.yaml`.
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    val toolsDir = File(trailmapsDir, "alpha/tools").apply { mkdirs() }
    val toolYaml = File(toolsDir, "myTool.yaml").apply {
      writeText("script: ./myTool.ts\n_meta:\n  trailblaze/requiresContext: true\n")
    }
    val toolTs = File(toolsDir, "myTool.ts").apply {
      writeText("// initial body — analyzer reads me\n")
    }

    val before = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    // Touch only the `.ts` file — the `.yaml` descriptor and `trailmap.yaml` manifest stay
    // identical to confirm the hash picks up `.ts`-only edits specifically.
    toolTs.writeText("// edited body — analyzer must re-run\n")
    val afterTsEdit = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    assertNotEquals(before, afterTsEdit, "Editing a tool .ts file must invalidate the workspace hash")

    // Confirm a `.yaml` descriptor edit also invalidates (covers full-YAML descriptor
    // authors who don't touch the .ts at all — the YAML carries `_meta:` /
    // `description:` that flow into compile output).
    toolYaml.appendText("# additional comment\n")
    val afterYamlEdit = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")
    assertNotEquals(afterTsEdit, afterYamlEdit, "Editing a tool .yaml file must invalidate the workspace hash")
  }

  @Test
  fun `hash ignores files in tools subtree that don't end in yaml or ts`() {
    // Defensive pin on the hash's file-suffix filter: framework-managed artifacts under
    // `tools/` (the generated `tsconfig.json`, `.gitignore`, `.trailblaze/` subdir) must
    // NOT participate in the hash, otherwise a daemon-side regeneration of those files
    // would force a spurious recompile on every boot.
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    val toolsDir = File(trailmapsDir, "alpha/tools").apply { mkdirs() }
    File(toolsDir, "myTool.yaml").writeText("script: ./myTool.ts\n")
    File(toolsDir, "myTool.ts").writeText("// content\n")

    val before = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    // Write framework-managed artifacts that the hash must ignore.
    File(toolsDir, "tsconfig.json").writeText("{ \"extends\": \"…\" }")
    File(toolsDir, ".gitignore").writeText("tsconfig.json\n.trailblaze/\n")
    File(toolsDir, ".trailblaze").apply { mkdirs() }
    File(toolsDir, "trailblaze-client.d.ts").writeText("// generated\n")

    val after = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")
    assertEquals(before, after, "Non-(yaml|ts) tool files and subdirs must not affect the hash")
  }

  @Test
  fun `hash filter narrowly excludes only the codegen filename, not all d_ts files`() {
    // The hash's `f.name != GENERATED_FILE_NAME` filter narrowly excludes ONE specific
    // codegen output. Any OTHER `.ts` (including a sibling `.d.ts`) must STILL affect
    // the hash — author-owned `.d.ts` files (e.g., hand-authored ambient declarations)
    // are real input. This test pins that the filter doesn't over-broadly skip every
    // `.d.ts`, only the framework's exact codegen filename.
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    val toolsDir = File(trailmapsDir, "alpha/tools").apply { mkdirs() }
    File(toolsDir, "myTool.yaml").writeText("script: ./myTool.ts\n")
    File(toolsDir, "myTool.ts").writeText("// content\n")

    val before = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    // A sibling `.d.ts` that is NOT the framework's codegen output: must affect the hash.
    File(toolsDir, "ambient.d.ts").writeText("declare const FOO: string;\n")

    val after = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")
    assertNotEquals(
      before,
      after,
      "Non-framework `.d.ts` files in tools/ MUST still affect the hash; the filter is " +
        "scoped to the exact codegen filename, not all `.d.ts`.",
    )
  }

  @Test
  fun `hash differs when version changes`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    val v1 = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")
    val v2 = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.1.0")

    assertNotEquals(v1, v2)
  }

  @Test
  fun `hash is identical for CRLF and LF variants of the same manifest`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    val trailmapFile = File(trailmapsDir, "alpha/trailmap.yaml")
    val lfHash = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    // Rewrite the manifest with CRLF line endings — same logical content, different bytes.
    val crlfBytes = trailmapFile.readText(Charsets.UTF_8).replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
    trailmapFile.writeBytes(crlfBytes)
    val crlfHash = WorkspaceCompileBootstrap.computeWorkspaceHash(trailmapsDir, "1.0.0")

    assertEquals(lfHash, crlfHash, "CRLF and LF copies of the same manifest must hash identically")
  }

  @Test
  fun `missing dist targets dir forces recompile even when hash matches`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    // User wiped `dist/targets/` but left `.bundle.hash` behind. The next bootstrap
    // must NOT skip — that would leave the daemon running against missing target files.
    targetsDir(configDir).deleteRecursively()

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
  }

  @Test
  fun `single missing target file forces recompile even when hash matches`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    writeFixtureTrailmap(trailmapsDir, "beta")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    // User deleted exactly one materialized target. Hash file is still valid against
    // the input manifests, but the on-disk bundle is no longer complete.
    assertTrue(File(targetsDir(configDir), "beta.yaml").delete())

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 2), result)
    assertTrue(File(targetsDir(configDir), "beta.yaml").isFile)
  }

  @Test
  fun `empty hash file is treated as missing`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")

    val hash = hashFile(configDir)
    hash.parentFile.mkdirs()
    hash.writeText("")

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
    assertNotEquals("", hash.readText().trim())
  }

  @Test
  fun `WorkspaceCompileException message includes resolver errors`() {
    val errors = listOf("trailmap 'foo' is missing dependency 'bar'", "cycle: a -> b -> a")
    val ex = WorkspaceCompileBootstrap.WorkspaceCompileException(errors)
    val msg = ex.message ?: ""
    for (err in errors) assertTrue(msg.contains(err), "expected '$err' in: $msg")
  }

  /**
   * Confirms the empty hash sentinel handler in [WorkspaceCompileBootstrap.bootstrap]
   * doesn't NPE when the file isn't there at all (the common case on a fresh checkout).
   */
  @Test
  fun `bootstrap handles absent hash file path components`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    writeFixtureTrailmap(trailmapsDir, "alpha")
    // Don't create dist/ at all — bootstrap must mkdirs along the way.
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
  }

  @Test
  fun `bootstrap resolves a meta-only descriptor trailmap when analyzer is available`() {
    // End-to-end integration test for the daemon-init recompile path against a workspace
    // that has adopted the meta-only authoring shape. Pins the wiring this PR added
    // (`AnalyzerScriptedToolEnrichment.resolveFromEnvironment()` passed through to
    // `TrailblazeCompiler.compile`); without it, the bootstrap would throw
    // `WorkspaceCompileException` citing the meta-only descriptor.
    //
    // Skipped when the analyzer isn't available locally — same `assumeTrue` shape the
    // sister test in `CompileCommandTest` uses. Production behavior on the same gap is
    // a clear "enrichment not wired" diagnostic, so skipping the test is safe.
    val enrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    assumeTrue(MetaOnlyDescriptorTestFixture.ANALYZER_UNAVAILABLE_SKIP_MESSAGE, enrichment != null)

    val configDir = tempFolder.newFolder("trails", "config")
    val trailmapsDir = File(configDir, "trailmaps").apply { mkdirs() }
    MetaOnlyDescriptorTestFixture.writeMetaOnlyTrailmap(trailmapsDir)

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(
      WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1),
      result,
      "Meta-only descriptor must compile cleanly when analyzer is wired",
    )
    assertTrue(
      File(targetsDir(configDir), "metaonly.yaml").isFile,
      "metaonly.yaml should be emitted under the workspace's dist/targets dir",
    )
  }
}
