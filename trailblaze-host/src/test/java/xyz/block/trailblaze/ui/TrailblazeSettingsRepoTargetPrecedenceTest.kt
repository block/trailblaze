package xyz.block.trailblaze.ui

import java.io.File
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig

/**
 * Pins the `defaults.target` consumption in [TrailblazeSettingsRepo.getCurrentSelectedTargetApp].
 *
 * Effective-target precedence (rung 1, the explicit per-run/session target, is applied by
 * callers above this function — see the method kdoc):
 *  - Rung 2: persisted user selection (`selectedTargetAppId`).
 *  - Rung 3: workspace `defaults.target` from `trails/config/trailblaze.yaml`.
 *  - Rung 4: neutral default — surfaced as `null` here so callers apply
 *    [TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget].
 *
 * Plus the degradation contract: an unknown, blank, or malformed `defaults.target` (or a
 * malformed workspace file) must fall through to `null`, never crash.
 */
class TrailblazeSettingsRepoTargetPrecedenceTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Before
  fun assumeTempFolderIsScratch() {
    // Walk-up reaches the filesystem root. If an ancestor of the temp dir happens to
    // contain trailblaze.yaml, tests that rely on Scratch semantics behave as if a
    // workspace were present. Skip rather than fail in that case.
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

  private fun repo(
    persistedTargetId: String?,
    loadedTargets: Set<TrailblazeHostAppTarget>,
    settingsFile: File = File(tempFolder.newFolder(), "trailblaze-settings.json"),
    trailsDirectory: String? = null,
  ): TrailblazeSettingsRepo = TrailblazeSettingsRepo(
    settingsFile = settingsFile,
    initialConfig = SavedTrailblazeAppConfig(
      selectedTrailblazeDriverTypes = emptyMap(),
      selectedTargetAppId = persistedTargetId,
      trailsDirectory = trailsDirectory,
    ),
    defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    allTargetApps = { loadedTargets },
    supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
  )

  /** Creates a workspace whose anchor has [body] as its content; returns the workspace root dir. */
  private fun workspaceWithAnchor(body: String): File {
    val root = tempFolder.newFolder()
    val configDir = File(root, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText(body + "\n")
    return root
  }

  /** Creates a workspace whose anchor declares [defaultsTarget]; returns the workspace root dir. */
  private fun workspaceWithDefaultTarget(defaultsTarget: String?): File = workspaceWithAnchor(
    if (defaultsTarget != null) {
      """
      defaults:
        target: $defaultsTarget
      """.trimIndent()
    } else {
      // Anchor present but no defaults.target.
      "targets: []"
    },
  )

  @Test
  fun `rung 2 persisted selection wins over workspace default`() {
    val alpha = target("alpha")
    val beta = target("beta")
    val repo = repo(persistedTargetId = "beta", loadedTargets = setOf(alpha, beta))
    val workspace = workspaceWithDefaultTarget("alpha")

    assertEquals(beta, repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `no-arg resolution re-anchors when the configured trails directory changes at runtime`() {
    // The no-arg overload reads TRAILBLAZE_CONFIG_DIR from the real process environment
    // (no injectable envReader on that path); skip when set rather than resolve against it.
    Assume.assumeTrue(
      "TRAILBLAZE_CONFIG_DIR is set in this environment — skipping the no-arg-path test.",
      System.getenv("TRAILBLAZE_CONFIG_DIR") == null,
    )
    val alpha = target("alpha")
    val beta = target("beta")
    val workspaceA = workspaceWithDefaultTarget("alpha")
    val workspaceB = workspaceWithDefaultTarget("beta")
    val repo = TrailblazeSettingsRepo(
      settingsFile = File(tempFolder.newFolder(), "trailblaze-settings.json"),
      initialConfig = SavedTrailblazeAppConfig(
        selectedTrailblazeDriverTypes = emptyMap(),
        selectedTargetAppId = null,
        trailsDirectory = workspaceA.absolutePath,
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { setOf(alpha, beta) },
      supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
    )

    assertEquals(alpha, repo.getCurrentSelectedTargetApp())

    // Switch the configured trails directory at runtime (the app-settings path). The
    // walk-up seed must follow the new workspace without a daemon restart — the seed is
    // recomputed per call; only the per-seed walk-up cache is frozen.
    repo.updateAppConfig { it.copy(trailsDirectory = workspaceB.absolutePath) }

    assertEquals(beta, repo.getCurrentSelectedTargetApp())
  }

  @Test
  fun `rung 3 workspace default resolves when nothing is persisted`() {
    val alpha = target("alpha")
    val beta = target("beta")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha, beta))
    val workspace = workspaceWithDefaultTarget("alpha")

    assertEquals(alpha, repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `rung 3 workspace default resolves when the persisted id matches no loaded target`() {
    val alpha = target("alpha")
    // Persisted id is a stale target that isn't loaded — rung 2 misses, rung 3 takes over.
    val repo = repo(persistedTargetId = "ghost", loadedTargets = setOf(alpha))
    val workspace = workspaceWithDefaultTarget("alpha")

    assertEquals(alpha, repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `persisted neutral-default sentinel does not mask the workspace default`() {
    // Legacy CLI code auto-persisted the neutral default's id ("default") without user
    // intent, so a stored sentinel must not outrank a committed workspace default.
    val alpha = target("alpha")
    val neutral = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    val repo = repo(persistedTargetId = neutral.id, loadedTargets = setOf(alpha, neutral))
    val workspace = workspaceWithDefaultTarget("alpha")

    assertEquals(alpha, repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `persisted neutral-default sentinel still resolves when no workspace default exists`() {
    val alpha = target("alpha")
    val neutral = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
    val repo = repo(persistedTargetId = neutral.id, loadedTargets = setOf(alpha, neutral))
    val noWorkspace = tempFolder.newFolder()

    assertEquals(neutral, repo.getCurrentSelectedTargetApp(cwd = noWorkspace.toPath(), envReader = { null }))
  }

  @Test
  fun `unknown defaults-target id falls through to null (neutral)`() {
    val alpha = target("alpha")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha))
    val workspace = workspaceWithDefaultTarget("does-not-exist")

    assertNull(repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `blank defaults-target is treated as absent`() {
    val alpha = target("alpha")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha))
    val workspace = workspaceWithAnchor(
      """
      defaults:
        target: ""
      """.trimIndent(),
    )

    assertNull(repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `malformed workspace yaml degrades to null instead of crashing`() {
    val alpha = target("alpha")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha))
    val workspace = workspaceWithAnchor("defaults: [this is: not — valid yaml: {{{")

    assertNull(repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  @Test
  fun `TRAILBLAZE_CONFIG_DIR override supplies the workspace default`() {
    val alpha = target("alpha")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha))
    // The env override points at a config dir carrying its own anchor; cwd has no workspace.
    val envConfigDir = tempFolder.newFolder()
    File(envConfigDir, "trailblaze.yaml").writeText("defaults:\n  target: alpha\n")
    val scratchCwd = tempFolder.newFolder()

    assertEquals(
      alpha,
      repo.getCurrentSelectedTargetApp(
        cwd = scratchCwd.toPath(),
        envReader = { envConfigDir.absolutePath },
      ),
    )
  }

  @Test
  fun `rung 4 null when no workspace anchor and nothing persisted`() {
    val alpha = target("alpha")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha))
    // A bare temp dir with no trails/config/trailblaze.yaml above it → no workspace default.
    val noWorkspace = tempFolder.newFolder()

    assertNull(repo.getCurrentSelectedTargetApp(cwd = noWorkspace.toPath(), envReader = { null }))
  }

  @Test
  fun `daemon-side persistence never fabricates a target selection`() {
    // The daemon's own save path is the third writer of the settings file (after the CLI's
    // getOrCreateConfig / updateConfig, pinned in CliConfigHelperDefaultsTest). A null
    // selection must round-trip as an ABSENT key — a materialized value would read back as
    // an explicit user pick and mask the workspace `defaults.target` rung forever.
    val settingsFile = File(tempFolder.newFolder(), "trailblaze-settings.json")
    val repo = repo(
      persistedTargetId = null,
      loadedTargets = setOf(target("alpha")),
      settingsFile = settingsFile,
    )

    repo.saveConfig(repo.serverStateFlow.value.appConfig)

    assertFalse(
      settingsFile.readText().contains("selectedTargetAppId"),
      "a null target selection must be omitted from the persisted settings file",
    )
  }

  @Test
  fun `null when workspace anchor omits defaults-target`() {
    val alpha = target("alpha")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha))
    val workspace = workspaceWithDefaultTarget(null)

    assertNull(repo.getCurrentSelectedTargetApp(cwd = workspace.toPath(), envReader = { null }))
  }

  // --- getCurrentSelectedTargetAppForCallerCwd: the run-dispatch anchor moves to the caller ---
  //
  // A daemon-dispatched `trailblaze run` used to resolve rung 3 against the daemon's frozen
  // configured-trails-dir, so a caller whose workspace declared a different `defaults.target`
  // got a run targeting the daemon's default while `config get target` (resolved from the
  // caller's cwd) reported the caller's. These pin that the forwarded caller cwd now wins, and
  // that the absence of one falls back to the daemon anchor unchanged.

  /** Points the repo's frozen anchor (`workspaceAnchorSeedPath`) at a workspace declaring [defaultsTarget]. */
  private fun repoWithFrozenAnchor(
    persistedTargetId: String?,
    loadedTargets: Set<TrailblazeHostAppTarget>,
    defaultsTarget: String?,
  ): TrailblazeSettingsRepo {
    val frozenRoot = workspaceWithDefaultTarget(defaultsTarget)
    return repo(
      persistedTargetId = persistedTargetId,
      loadedTargets = loadedTargets,
      trailsDirectory = File(frozenRoot, "trails").absolutePath,
    )
  }

  @Test
  fun `caller cwd anchors the workspace defaults-target rung`() {
    val alpha = target("alpha")
    val beta = target("beta")
    val repo = repo(persistedTargetId = null, loadedTargets = setOf(alpha, beta))
    val callerWorkspace = workspaceWithDefaultTarget("alpha")

    assertEquals(
      alpha,
      repo.getCurrentSelectedTargetAppForCallerCwd(
        callerWorkspaceDir = callerWorkspace.absolutePath,
        envReader = { null },
      ),
    )
  }

  @Test
  fun `forwarded caller cwd overrides the daemon frozen anchor`() {
    val alpha = target("alpha")
    val beta = target("beta")
    // Daemon launched in a workspace whose default is beta; caller's shell is in one whose
    // default is alpha. The run must follow the CALLER, matching `config get target`.
    val repo = repoWithFrozenAnchor(
      persistedTargetId = null,
      loadedTargets = setOf(alpha, beta),
      defaultsTarget = "beta",
    )
    val callerWorkspace = workspaceWithDefaultTarget("alpha")

    assertEquals(
      alpha,
      repo.getCurrentSelectedTargetAppForCallerCwd(
        callerWorkspaceDir = callerWorkspace.absolutePath,
        envReader = { null },
      ),
    )
  }

  @Test
  fun `null caller cwd falls back to the daemon frozen anchor`() {
    val alpha = target("alpha")
    val beta = target("beta")
    val repo = repoWithFrozenAnchor(
      persistedTargetId = null,
      loadedTargets = setOf(alpha, beta),
      defaultsTarget = "beta",
    )

    assertEquals(
      beta,
      repo.getCurrentSelectedTargetAppForCallerCwd(callerWorkspaceDir = null, envReader = { null }),
    )
  }

  @Test
  fun `blank caller cwd falls back to the daemon frozen anchor`() {
    val alpha = target("alpha")
    val beta = target("beta")
    val repo = repoWithFrozenAnchor(
      persistedTargetId = null,
      loadedTargets = setOf(alpha, beta),
      defaultsTarget = "beta",
    )

    assertEquals(
      beta,
      repo.getCurrentSelectedTargetAppForCallerCwd(callerWorkspaceDir = "   ", envReader = { null }),
    )
  }

  @Test
  fun `relative caller cwd falls back to the daemon frozen anchor`() {
    val alpha = target("alpha")
    val beta = target("beta")
    // The field contract is an ABSOLUTE path (the CLI forwards `callerCwd().toAbsolutePath()`).
    // A relative value would otherwise resolve against the daemon's own process cwd — silently
    // daemon-anchored under a surprising path — so it must degrade to the daemon anchor instead.
    val repo = repoWithFrozenAnchor(
      persistedTargetId = null,
      loadedTargets = setOf(alpha, beta),
      defaultsTarget = "beta",
    )

    assertEquals(
      beta,
      repo.getCurrentSelectedTargetAppForCallerCwd(
        callerWorkspaceDir = "some/relative/workspace",
        envReader = { null },
      ),
    )
  }
}
