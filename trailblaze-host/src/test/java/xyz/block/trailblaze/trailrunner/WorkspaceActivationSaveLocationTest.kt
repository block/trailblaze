package xyz.block.trailblaze.trailrunner

import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepoJvm
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a recording lands after `trailblaze app` hands the daemon a repo root via
 * `PUT /api/workspace`. The recorded-trails repo is wired the way both desktop distributions wire
 * it (per-call over [TrailblazeSettingsRepo.getCurrentTrailsDir]) and is built once, before any
 * activation — as the daemon builds it at startup.
 */
class WorkspaceActivationSaveLocationTest {

  @get:Rule val tmp = TemporaryFolder()

  private val trailId = "suites/checkout/case_3"

  @Test
  fun `a repo that declares trails saves under the declared directory`() {
    val repoRoot = newRepo("declaring-repo", trailsDeclaration = "trails")

    withDaemon { activate, recordings ->
      activate(repoRoot)

      val saved = recordings.save(trailId)

      assertEquals(File(repoRoot, "trails/$trailId").canonicalFile, saved.parentFile.canonicalFile)
    }
  }

  @Test
  fun `a declaring repo keeps its logs and state at the repo root`() {
    val repoRoot = newRepo("declaring-repo", trailsDeclaration = "trails")

    withDaemon(onSettings = { settings ->
      val config = settings.serverStateFlow.value.appConfig
      assertEquals(File(repoRoot, "logs").path, config.logsDirectory)
      assertEquals(File(repoRoot, ".trailblaze").path, config.appDataDirectory)
    }) { activate, _ -> activate(repoRoot) }
  }

  @Test
  fun `a repo with no declaration saves under the repo root`() {
    val repoRoot = newRepo("plain-repo", trailsDeclaration = null)

    withDaemon { activate, recordings ->
      activate(repoRoot)

      val saved = recordings.save(trailId)

      assertEquals(File(repoRoot, trailId).canonicalFile, saved.parentFile.canonicalFile)
    }
  }

  @Test
  fun `an enclosing workspace's declaration does not move a nested repo's saves`() {
    val outer = newRepo("outer", trailsDeclaration = "trails")
    val nested = File(outer, "nested-repo").apply { mkdirs() }

    withDaemon { activate, recordings ->
      activate(nested)

      val saved = recordings.save(trailId)

      assertEquals(File(nested, trailId).canonicalFile, saved.parentFile.canonicalFile)
    }
  }

  @Test
  fun `a save after switching workspaces lands in the new workspace`() {
    val first = newRepo("first-clone", trailsDeclaration = "trails")
    val second = newRepo("second-clone", trailsDeclaration = "trails")

    withDaemon { activate, recordings ->
      activate(first)
      recordings.save(trailId)
      activate(second)

      val saved = recordings.save(trailId)

      assertEquals(File(second, "trails/$trailId").canonicalFile, saved.parentFile.canonicalFile)
      assertTrue(
        recordings.existingTrails(trailId).all { File(it).canonicalPath.startsWith(second.canonicalPath) },
        "the existing-trail lookup must follow the switch too",
      )
    }
  }

  @Test
  fun `an activated repo outranks the launch workspace's declaration`() {
    // The daemon launched in another declaring clone; activation stores `<repo>/trails`, which
    // equals the derived default once app data moves to `<repo>/.trailblaze`.
    val repoRoot = newRepo("declaring-repo", trailsDeclaration = "trails")

    assertActivationOutranksLaunchDeclaration(repoRoot, expected = File(repoRoot, "trails"))
  }

  @Test
  fun `an activated repo whose trails folder is a symlink outranks the launch workspace's declaration`() {
    // `<repo>/trails` links to a folder outside the repo, which the repo declares by absolute
    // path. Walking up from that folder finds no workspace, so only a recorded pick keeps it.
    val external = tmp.newFolder("shared-trails")
    val repoRoot = newRepo("symlinked-repo", trailsDeclaration = external.absolutePath)
    Files.createSymbolicLink(File(repoRoot, "trails").toPath(), external.toPath())

    assertActivationOutranksLaunchDeclaration(repoRoot, expected = external)
  }

  private fun assertActivationOutranksLaunchDeclaration(repoRoot: File, expected: File) {
    val launchedIn = newRepo("launch-clone-${repoRoot.name}", trailsDeclaration = "trails")
    withDaemon(onSettings = { settings ->
      val effective = TrailblazeDesktopUtil.getEffectiveTrailsDirectory(
        appConfig = settings.serverStateFlow.value.appConfig,
        workspaceTrailsDirProvider = { File(launchedIn, "trails") },
      )
      assertEquals(expected.canonicalPath, File(effective).canonicalPath)
    }) { activate, _ -> activate(repoRoot) }
  }

  @Test
  fun `switching workspaces moves the workspace config dir too`() {
    // The config dir is where trailmaps, targets and `defaults.target` come from. Resolved against
    // a daemon launched in the first clone, it must still land on the second after the switch.
    val first = newRepo("first-clone", trailsDeclaration = "trails")
    val second = newRepo("second-clone", trailsDeclaration = "trails")
    val launchedIn = TrailblazeWorkspaceConfigResolver.workspaceTrailsDeclaration(
      fromPath = first.toPath(),
      consumer = "test",
      envReader = { null },
    )

    withDaemon(onSettings = { settings ->
      val inEffect = TrailblazeDesktopUtil.effectiveWorkspaceConfigDir(
        appConfig = settings.serverStateFlow.value.appConfig,
        launchDeclaration = launchedIn,
      )
      assertEquals(File(second, "trailblaze-config").canonicalFile, inEffect?.canonicalFile)
    }) { activate, _ ->
      activate(first)
      activate(second)
    }
  }

  @Test
  fun `an activated repo whose declared trails folder is outside it keeps its own config folder`() {
    // Walking up from the external folder never reaches the repo, so its trailmaps and targets
    // only follow if activation saved where they live.
    val external = tmp.newFolder("outside-trails")
    val repoRoot = newRepo("absolute-repo", trailsDeclaration = external.absolutePath)
    val launchedIn = TrailblazeWorkspaceConfigResolver.workspaceTrailsDeclaration(
      fromPath = newRepo("launch-clone", trailsDeclaration = "trails").toPath(),
      consumer = "test",
      envReader = { null },
    )

    withDaemon(onSettings = { settings ->
      val inEffect = TrailblazeDesktopUtil.effectiveWorkspaceConfigDir(
        appConfig = settings.serverStateFlow.value.appConfig,
        launchDeclaration = launchedIn,
      )
      assertEquals(File(repoRoot, "trailblaze-config").canonicalFile, inEffect?.canonicalFile)
    }) { activate, _ -> activate(repoRoot) }
  }

  /** A repo root with a `trailblaze-config/trailblaze.yaml`, declaring [trailsDeclaration] if non-null. */
  private fun newRepo(name: String, trailsDeclaration: String?): File {
    val root = tmp.newFolder(name)
    val configDir = File(root, "trailblaze-config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText(
      if (trailsDeclaration == null) "# no trails declaration\n" else "trails: $trailsDeclaration\n",
    )
    trailsDeclaration?.takeUnless { File(it).isAbsolute }?.let { File(root, it).mkdirs() }
    return root
  }

  private class Recordings(private val repo: RecordedTrailsRepoJvm) {
    fun save(trailId: String): File {
      val result = repo.saveRecording(items(trailId), sessionInfo(trailId))
      return File(result.getOrElse { throw AssertionError("save failed: ${it.message}", it) })
    }

    fun existingTrails(trailId: String): List<String> =
      repo.getExistingTrails(sessionInfo(trailId)).map { it.absolutePath }
  }

  private fun withDaemon(
    onSettings: (TrailblazeSettingsRepo) -> Unit = {},
    block: suspend (activate: suspend (File) -> Unit, recordings: Recordings) -> Unit,
  ) {
    val settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tmp.newFolder(), "trailblaze-settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget) },
      supportedDriverTypes = setOf(TrailblazeDriverType.DEFAULT_ANDROID),
    )
    val deps = TrailRunnerDeps(
      trailsRootProvider = settingsRepo::getCurrentTrailsDir,
      logsRepo = LogsRepo(logsDir = tmp.newFolder(), watchFileSystem = false),
      settingsRepo = settingsRepo,
      deviceManager = null,
      integrationsProvider = null,
      integrationActionHandler = null,
      analyticsProvider = null,
      analyticsCaptureStarter = null,
      eventCaptureController = null,
    )
    val recordings = Recordings(RecordedTrailsRepoJvm(trailsDirectoryProvider = settingsRepo::getCurrentTrailsDir))
    testApplication {
      application { routing { settingsRoutes(deps) } }
      val activate: suspend (File) -> Unit = { root ->
        val response = client.put("/trailrunner/api/workspace") {
          contentType(ContentType.Application.FormUrlEncoded)
          setBody("path=${URLEncoder.encode(root.absolutePath, Charsets.UTF_8)}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
      }
      block(activate, recordings)
    }
    onSettings(settingsRepo)
  }

  private companion object {
    fun items(trailId: String): List<TrailYamlItem> = listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = trailId)),
      TrailYamlItem.PromptsTrailItem(
        listOf(
          DirectionStep(
            step = "Open the cart",
            recording = ToolRecording(
              tools = listOf(
                TrailblazeToolYamlWrapper(
                  name = "tapCart",
                  trailblazeTool = OtherTrailblazeTool(toolName = "tapCart", raw = JsonObject(emptyMap())),
                ),
              ),
            ),
          ),
        ),
      ),
    )

    fun sessionInfo(trailId: String): SessionInfo = SessionInfo(
      sessionId = SessionId("test-session"),
      latestStatus = SessionStatus.Unknown,
      timestamp = Instant.fromEpochMilliseconds(0),
      durationMs = 0L,
      trailFilePath = null,
      hasRecordedSteps = true,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 100,
        heightPixels = 200,
        classifiers = listOf(TrailblazeDeviceClassifier("android")),
      ),
      trailConfig = TrailConfig(id = trailId),
    )
  }
}
