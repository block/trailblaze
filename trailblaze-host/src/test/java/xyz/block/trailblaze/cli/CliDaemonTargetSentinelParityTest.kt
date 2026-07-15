package xyz.block.trailblaze.cli

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.project.WorkspaceRoot
import xyz.block.trailblaze.config.project.findWorkspaceRoot
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Cross-reference guard for the neutral-"default" target sentinel, which is implemented once in
 * [xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId]
 * but reached through two neutral-id sources that must stay pinned to the same constant:
 *
 *  - the CLI adapter [authoritativeSelectedTargetId], which hardcodes the compile-time OSS static
 *    [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget]; and
 *  - the daemon's [TrailblazeSettingsRepo.getCurrentSelectedTargetApp], which uses the
 *    runtime-injected `defaultHostAppTarget.id`.
 *
 * Every shipped distribution injects [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget] as
 * that default (contract documented on `TrailblazeDesktopAppConfig.defaultAppTarget`). This test
 * pins that, so injected, the CLI and daemon reach the *same* authoritative-vs-neutral verdict for
 * the same persisted id — the parity PR #4801 exists to guarantee. If the CLI adapter's neutral
 * constant and the daemon's injected default ever diverge, the two verdicts disagree here.
 */
class CliDaemonTargetSentinelParityTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    val result = findWorkspaceRoot(tempFolder.root.toPath())
    Assume.assumeTrue(
      "An ancestor of ${tempFolder.root} already contains a trailblaze.yaml — skipping.",
      result is WorkspaceRoot.Scratch,
    )
  }

  private fun target(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  /** A repo injected with the shipped neutral default, mirroring every real distribution. */
  private fun repo(
    persistedTargetId: String?,
    loadedTargets: Set<TrailblazeHostAppTarget>,
  ): TrailblazeSettingsRepo = TrailblazeSettingsRepo(
    settingsFile = File(tempFolder.newFolder(), "trailblaze-settings.json"),
    initialConfig = SavedTrailblazeAppConfig(
      selectedTrailblazeDriverTypes = emptyMap(),
      selectedTargetAppId = persistedTargetId,
    ),
    defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    allTargetApps = { loadedTargets },
    supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
  )

  /** A workspace whose anchor declares `defaults.target: <defaultsTarget>`; returns its root dir. */
  private fun workspaceWithDefaultTarget(defaultsTarget: String): File {
    val root = tempFolder.newFolder()
    val configDir = File(root, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("defaults:\n  target: $defaultsTarget\n")
    return root
  }

  @Test
  fun `CLI adapter pins its neutral sentinel to the OSS static default id`() {
    // Behavioral pin (not a constant copy): the adapter drops exactly the static's id and keeps
    // any other id. This is the CLI half of the shared sentinel's neutral-id contract.
    assertNull(authoritativeSelectedTargetId(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id))
    assertNotNull(authoritativeSelectedTargetId("square"))
  }

  @Test
  fun `CLI and daemon agree on a real persisted selection`() {
    val square = target("square")
    val alpha = target("alpha")
    val neutral = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    val workspace = workspaceWithDefaultTarget("alpha")
    val repo = repo(persistedTargetId = "square", loadedTargets = setOf(square, alpha, neutral))

    // CLI: "square" is authoritative (≠ neutral) → the CLI reports it as the selection.
    assertEquals("square", authoritativeSelectedTargetId("square"))
    // Daemon: same verdict → rung 2 wins over the workspace default.
    assertEquals(square, repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `CLI and daemon agree that a persisted neutral default is not authoritative`() {
    val alpha = target("alpha")
    val neutral = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    val workspace = workspaceWithDefaultTarget("alpha")
    val repo = repo(persistedTargetId = neutral.id, loadedTargets = setOf(alpha, neutral))

    // CLI: the neutral id is dropped, so it does NOT count as an authoritative selection.
    assertNull(authoritativeSelectedTargetId(neutral.id))
    // Daemon: same verdict → the neutral id doesn't mask the committed workspace default.
    assertEquals(alpha, repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }
}
