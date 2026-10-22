package xyz.block.trailblaze.scripting

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/** A session's surveys are released with its runtime, or by the launch itself when it returns none. */
class HostScriptedToolLauncherSurveyRegistrationTest {

  private val dir = Files.createTempDirectory("launcher-surveys").toFile()
  private val sessionId = SessionId("launcher-surveys-${System.nanoTime()}")

  @AfterTest
  fun cleanUp() {
    SurveyBundles.unregister(sessionId)
    dir.deleteRecursively()
  }

  @Test
  fun `a launch with no tools to run leaves no survey registration behind`() {
    val runtime = runBlocking {
      HostScriptedToolLauncher.launch(
        targetTestApp = null,
        config = TrailblazeConfig(),
        sessionId = sessionId,
        deviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
          trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
          widthPixels = 1080,
          heightPixels = 1920,
        ),
        logsRepo = LogsRepo(logsDir = dir.resolve("logs").also { it.mkdirs() }, watchFileSystem = false),
        toolRepo = TrailblazeToolRepo(
          trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(name = "launcher-surveys", toolClasses = emptySet(), yamlToolNames = emptySet()),
        ),
        classLoader = javaClass.classLoader,
        logPrefix = "[test]",
        includeSubprocess = false,
        onProgressMessage = {},
      )
    }

    assertNull(runtime)
    assertNull(SurveyBundles.sourceFor(sessionId))
  }
}
