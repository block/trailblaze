package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths

class WorkspaceCompileBootstrapTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  /**
   * Toolset names referenced by these fixture packs (`core_interaction`, `verification`)
   * are real toolsets shipped on the framework classpath, so the compiler's reference
   * validation passes in the host module's test classpath.
   */
  private fun writeFixturePack(packsDir: File, id: String) {
    val packDir = File(packsDir, id).apply { mkdirs() }
    File(packDir, "pack.yaml").writeText(
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

  private fun targetsDir(configDir: File): File =
    File(configDir, TrailblazeConfigPaths.WORKSPACE_DIST_TARGETS_SUBPATH)

  private fun hashFile(configDir: File): File =
    File(
      configDir,
      "${TrailblazeConfigPaths.WORKSPACE_DIST_SUBDIR}/${WorkspaceCompileBootstrap.HASH_FILENAME}",
    )

  @Test
  fun `no packs directory returns NoWorkspacePacks`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.NoWorkspacePacks, result)
  }

  @Test
  fun `empty packs directory returns NoWorkspacePacks`() {
    val configDir = tempFolder.newFolder("trails", "config")
    File(configDir, TrailblazeConfigPaths.PACKS_SUBDIR).mkdirs()
    // Subdir without a pack.yaml — should not count.
    File(configDir, "${TrailblazeConfigPaths.PACKS_SUBDIR}/scratch").mkdirs()

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.NoWorkspacePacks, result)
    // Critical: bootstrap must NOT print "Recompiling..." or call compile when there
    // are no manifests, otherwise the codex / Copilot zero-pack regression returns —
    // every startup would re-run compile against an empty input set forever.
    assertTrue(!hashFile(configDir).exists(), "no-pack workspace should not produce a hash file")
  }

  @Test
  fun `first run with no hash compiles and emits one yaml per app pack`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")
    writeFixturePack(packsDir, "beta")

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 2), result)
    assertTrue(File(targetsDir(configDir), "alpha.yaml").isFile)
    assertTrue(File(targetsDir(configDir), "beta.yaml").isFile)
    assertTrue(hashFile(configDir).isFile)
  }

  @Test
  fun `second run with unchanged manifests skips compile`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    val targetFile = File(targetsDir(configDir), "alpha.yaml")
    val firstMtime = targetFile.lastModified()

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.UpToDate, result)
    // Skip path didn't rewrite the materialized target.
    assertEquals(firstMtime, targetFile.lastModified())
  }

  @Test
  fun `editing a manifest invalidates the hash and triggers recompile`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")

    // Edit the manifest's display name (a structural change that lands in the emitted YAML).
    File(packsDir, "alpha/pack.yaml").writeText(
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
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

    WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.1.0")

    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
  }

  @Test
  fun `compile error throws WorkspaceCompileException and clears hash`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    // Pack declares a dependency that doesn't exist — resolver fails, compile fails.
    val brokenDir = File(packsDir, "broken").apply { mkdirs() }
    File(brokenDir, "pack.yaml").writeText(
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
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

    val before = WorkspaceCompileBootstrap.computeWorkspaceHash(packsDir, "1.0.0")

    File(packsDir, "alpha/pack.yaml").appendText("\n# trailing comment\n")
    val after = WorkspaceCompileBootstrap.computeWorkspaceHash(packsDir, "1.0.0")

    assertNotEquals(before, after)
  }

  @Test
  fun `hash differs when version changes`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

    val v1 = WorkspaceCompileBootstrap.computeWorkspaceHash(packsDir, "1.0.0")
    val v2 = WorkspaceCompileBootstrap.computeWorkspaceHash(packsDir, "1.1.0")

    assertNotEquals(v1, v2)
  }

  @Test
  fun `hash is identical for CRLF and LF variants of the same manifest`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")
    val packFile = File(packsDir, "alpha/pack.yaml")
    val lfHash = WorkspaceCompileBootstrap.computeWorkspaceHash(packsDir, "1.0.0")

    // Rewrite the manifest with CRLF line endings — same logical content, different bytes.
    val crlfBytes = packFile.readText(Charsets.UTF_8).replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
    packFile.writeBytes(crlfBytes)
    val crlfHash = WorkspaceCompileBootstrap.computeWorkspaceHash(packsDir, "1.0.0")

    assertEquals(lfHash, crlfHash, "CRLF and LF copies of the same manifest must hash identically")
  }

  @Test
  fun `missing dist targets dir forces recompile even when hash matches`() {
    val configDir = tempFolder.newFolder("trails", "config")
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

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
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")
    writeFixturePack(packsDir, "beta")

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
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")

    val hash = hashFile(configDir)
    hash.parentFile.mkdirs()
    hash.writeText("")

    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
    assertNotEquals("", hash.readText().trim())
  }

  @Test
  fun `WorkspaceCompileException message includes resolver errors`() {
    val errors = listOf("pack 'foo' is missing dependency 'bar'", "cycle: a -> b -> a")
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
    val packsDir = File(configDir, "packs").apply { mkdirs() }
    writeFixturePack(packsDir, "alpha")
    // Don't create dist/ at all — bootstrap must mkdirs along the way.
    val result = WorkspaceCompileBootstrap.bootstrap(configDir = configDir, version = "1.0.0")
    assertEquals(WorkspaceCompileBootstrap.BootstrapResult.Recompiled(emitted = 1), result)
  }
}
