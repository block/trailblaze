package xyz.block.trailblaze.config.project

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths

/**
 * Tests for [TrailblazeWorkspaceConfigResolver.resolve] — specifically the precedence between
 * an explicit `TRAILBLAZE_CONFIG_DIR` and cwd walk-up.
 *
 * The load-bearing case is the regression guard: an explicit config dir that carries its own
 * `trailblaze.yaml` must become the authoritative workspace **anchor** (the file that defines
 * targets/trailmaps), not merely the file-scan directory. Before the fix the env var moved
 * only `configDir` while `configFile` still came from cwd walk-up, so a cwd that is itself a
 * workspace (e.g. a monorepo root whose `contacts`/`wikipedia` trailmaps are android-only)
 * shadowed the env-pointed example workspace and its scripted tools never registered.
 */
class TrailblazeWorkspaceConfigResolverTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    // Walk-up reaches the filesystem root; skip if an ancestor of the temp dir already
    // carries a trailblaze.yaml, which would defeat the Scratch/walk-up assumptions.
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      findWorkspaceRoot(tempFolder.root.toPath()) is WorkspaceRoot.Scratch,
    )
  }

  /** Creates `<parent>/<name>/trails/config/trailblaze.yaml` and returns the workspace dir. */
  private fun newWorkspace(name: String): File {
    val workspace = tempFolder.newFolder(name)
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).apply {
      parentFile.mkdirs()
      writeText("")
    }
    return workspace
  }

  private fun configDirOf(workspace: File): File =
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR)

  private fun File.canonical(): File = toPath().toRealPath().toFile()

  @Test
  fun `explicit config dir with its own anchor wins over a different cwd workspace`() {
    // cwd is one workspace (think: monorepo root, android-only trailmaps)…
    val cwdWorkspace = newWorkspace("cwd-workspace")
    // …and TRAILBLAZE_CONFIG_DIR points at a *different* workspace (think: examples/wikipedia).
    val envWorkspace = newWorkspace("env-workspace")
    val envConfigDir = configDirOf(envWorkspace)

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { envConfigDir.absolutePath },
    )

    // The anchor (and thus the targets/trailmaps that load) comes from the env workspace,
    // NOT the cwd workspace.
    assertEquals(
      File(envWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(envConfigDir.canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `resolveConfigFile honors the explicit config dir anchor`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")
    val envWorkspace = newWorkspace("env-workspace")
    val envConfigDir = configDirOf(envWorkspace)

    // resolveConfigFile reads the real env var, so exercise resolve() directly to inject it;
    // this asserts the two entry points agree (no anchor split between the trail runner and
    // the LLM-config / MCP / CLI-info callers).
    val viaResolve = TrailblazeWorkspaceConfigResolver
      .resolve(cwdWorkspace.toPath(), envReader = { envConfigDir.absolutePath })
      .configFile

    assertEquals(
      File(envWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      viaResolve?.canonical(),
    )
  }

  @Test
  fun `explicit config dir without its own anchor keeps cwd walk-up anchor`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")
    // Override dir is a real directory but carries no trailblaze.yaml of its own.
    val bareEnvDir = tempFolder.newFolder("bare-env-config-dir")

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { bareEnvDir.absolutePath },
    )

    // Legacy split preserved: anchor still from walk-up, payload dir from the override.
    assertEquals(
      File(cwdWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(bareEnvDir.canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `no env var resolves the cwd workspace via walk-up`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { null },
    )

    assertEquals(
      File(cwdWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(configDirOf(cwdWorkspace).canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `env var pointing at a non-directory is ignored in favor of walk-up`() {
    val cwdWorkspace = newWorkspace("cwd-workspace")
    val notADir = File(tempFolder.newFolder("env-host"), "not-a-dir").apply { writeText("x") }

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = cwdWorkspace.toPath(),
      envReader = { notADir.absolutePath },
    )

    assertEquals(
      File(cwdWorkspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      resolved.configFile?.canonical(),
    )
    assertEquals(configDirOf(cwdWorkspace).canonical(), resolved.configDir?.canonical())
  }

  @Test
  fun `scratch cwd with no env var resolves no config`() {
    val scratch = tempFolder.newFolder("scratch")

    val resolved = TrailblazeWorkspaceConfigResolver.resolve(
      fromPath = scratch.toPath(),
      envReader = { null },
    )

    assertNull(resolved.configFile)
    assertNull(resolved.configDir)
  }

  // ---------------------------------------------------------------------------
  // loadWorkspaceDefaults — the shared read path for `defaults.*` consumers.
  // The load-bearing contract is degradation: a broken workspace file (or a throw
  // anywhere in the resolve → load pipeline) must return null, never propagate —
  // callers sit on hot paths (Compose recomposition, per-dispatch MCP).
  // ---------------------------------------------------------------------------

  @Test
  fun `loadWorkspaceDefaults returns the defaults block with the anchor file`() {
    val workspace = newWorkspace("defaults-workspace")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE)
      .writeText("defaults:\n  target: alpha\n")

    val loaded = TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
      fromPath = workspace.toPath(),
      consumer = "test",
      envReader = { null },
    )

    assertEquals("alpha", loaded?.defaults?.target)
    assertEquals(
      File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).canonical(),
      loaded?.configFile?.canonical(),
    )
  }

  @Test
  fun `loadWorkspaceDefaults returns null when the anchor declares no defaults`() {
    val workspace = newWorkspace("no-defaults-workspace")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE).writeText("targets: []\n")

    assertNull(
      TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
        fromPath = workspace.toPath(),
        consumer = "test",
        envReader = { null },
      ),
    )
  }

  @Test
  fun `loadWorkspaceDefaults returns null when no anchor resolves`() {
    val scratch = tempFolder.newFolder("no-anchor")

    assertNull(
      TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
        fromPath = scratch.toPath(),
        consumer = "test",
        envReader = { null },
      ),
    )
  }

  @Test
  fun `loadWorkspaceDefaults degrades to null on malformed yaml`() {
    val workspace = newWorkspace("malformed-workspace")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE)
      .writeText("defaults: [this is not\n  a mapping\n")

    assertNull(
      TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
        fromPath = workspace.toPath(),
        consumer = "test",
        envReader = { null },
      ),
    )
  }

  @Test
  fun `loadWorkspaceDefaults degrades to null when resolution itself throws`() {
    // The env read happens inside resolve(), i.e. inside loadWorkspaceDefaults' try —
    // a throwing reader stands in for any unexpected failure in the resolve → load
    // pipeline and pins the catch-everything degradation contract.
    val workspace = newWorkspace("throwing-env-workspace")

    assertNull(
      TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
        fromPath = workspace.toPath(),
        consumer = "test",
        envReader = { throw RuntimeException("simulated resolution failure") },
      ),
    )
  }

  @Test
  fun `loadWorkspaceDefaults propagates cancellation instead of degrading to null`() {
    // Cancellation is not a load failure: swallowing it would leave a cancelled coroutine
    // running through the degrade-to-null path instead of unwinding.
    val workspace = newWorkspace("cancelling-env-workspace")

    assertFailsWith<CancellationException> {
      TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
        fromPath = workspace.toPath(),
        consumer = "test",
        envReader = { throw CancellationException("simulated cancellation") },
      )
    }
  }

  @Test
  fun `loadWorkspaceDefaults degrades to null on interrupt but restores the interrupt flag`() {
    val workspace = newWorkspace("interrupting-env-workspace")

    val result = TrailblazeWorkspaceConfigResolver.loadWorkspaceDefaults(
      fromPath = workspace.toPath(),
      consumer = "test",
      envReader = { throw InterruptedException("simulated interrupt") },
    )

    // Thread.interrupted() also CLEARS the flag, so the test thread isn't left interrupted
    // for subsequent tests on the same worker.
    val interruptFlagWasRestored = Thread.interrupted()
    assertNull(result)
    assertTrue(interruptFlagWasRestored, "expected the interrupt flag to be restored")
  }

  // ---------------------------------------------------------------------------
  // workspaceDefaultTarget — id-only accessor with blank-normalization, the shared
  // read path for the CLI target surfaces.
  // ---------------------------------------------------------------------------

  @Test
  fun `workspaceDefaultTarget returns the declared id`() {
    val workspace = newWorkspace("wdt-declared")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE)
      .writeText("defaults:\n  target: alpha\n")

    assertEquals(
      "alpha",
      TrailblazeWorkspaceConfigResolver.workspaceDefaultTarget(
        fromPath = workspace.toPath(),
        consumer = "test",
        envReader = { null },
      ),
    )
  }

  @Test
  fun `workspaceDefaultTarget blank-normalizes a whitespace-only target to null`() {
    val workspace = newWorkspace("wdt-blank")
    File(workspace, TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE)
      .writeText("defaults:\n  target: \"   \"\n")

    assertNull(
      TrailblazeWorkspaceConfigResolver.workspaceDefaultTarget(
        fromPath = workspace.toPath(),
        consumer = "test",
        envReader = { null },
      ),
    )
  }

  @Test
  fun `workspaceDefaultTarget returns null when no anchor resolves`() {
    val scratch = tempFolder.newFolder("wdt-no-anchor")

    assertNull(
      TrailblazeWorkspaceConfigResolver.workspaceDefaultTarget(
        fromPath = scratch.toPath(),
        consumer = "test",
        envReader = { null },
      ),
    )
  }

  // ---------------------------------------------------------------------------
  // authoritativeSelectedTargetId — the neutral-"default" sentinel (rung 2)
  // shared by the CLI target surfaces and the daemon's run resolution.
  // ---------------------------------------------------------------------------

  @Test
  fun `authoritativeSelectedTargetId returns a real selection unchanged`() {
    assertEquals(
      "square",
      TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId("square", neutralDefaultId = "default"),
    )
  }

  @Test
  fun `authoritativeSelectedTargetId treats null as no authoritative selection`() {
    assertNull(TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId(null, neutralDefaultId = "default"))
  }

  @Test
  fun `authoritativeSelectedTargetId drops a persisted id equal to the neutral default`() {
    // Legacy CLI code auto-persisted the neutral default without user intent, so it must not
    // count as an authoritative selection that would mask a committed workspace defaults.target.
    assertNull(TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId("default", neutralDefaultId = "default"))
  }

  @Test
  fun `authoritativeSelectedTargetId drops a blank persisted id`() {
    assertNull(TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId("", neutralDefaultId = "default"))
    assertNull(TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId("   ", neutralDefaultId = "default"))
  }

  @Test
  fun `authoritativeSelectedTargetId honors the neutral id parameter, not a hardcoded default`() {
    // The neutral id is a parameter so the CLI (compile-time OSS static) and the daemon
    // (runtime-injected defaultHostAppTarget.id) can share this one implementation. A different
    // neutral id must drop *that* id — and stop dropping the literal "default".
    assertNull(TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId("square", neutralDefaultId = "square"))
    assertEquals(
      "default",
      TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId("default", neutralDefaultId = "square"),
    )
  }
}
