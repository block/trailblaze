package xyz.block.trailblaze.scripting

import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/** A target's subprocess tool whose name is already registered keeps that registration. */
class HostScriptedToolLauncherPreRegisteredTest {

  private val dir = Files.createTempDirectory("launcher-preregistered").toFile()
  private val sessionId = SessionId("launcher-preregistered-${System.nanoTime()}")

  @AfterTest
  fun cleanUp() {
    SurveyBundles.unregister(sessionId)
    dir.deleteRecursively()
  }

  @Test
  fun `a subprocess tool an earlier target registered is not launched again`() {
    val toolRepo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(name = "launcher-preregistered", toolClasses = emptySet()),
    )
    val earlier = registration("fixture_shared_subprocess")
    toolRepo.addDynamicTools(listOf(earlier))
    val target = object : TrailblazeHostAppTarget(id = "laterapp", displayName = "Later App") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> = emptySet()

      override fun getInlineScriptTools(): List<InlineScriptToolConfig> = listOf(
        InlineScriptToolConfig(
          script = dir.resolve("tools/fixture_shared_subprocess.ts").absolutePath,
          name = "fixture_shared_subprocess",
          description = "fixture_shared_subprocess",
          runtime = ScriptedToolRuntime.SUBPROCESS,
        ),
      )
    }
    val progress = mutableListOf<String>()

    val runtime = runBlocking {
      HostScriptedToolLauncher.launch(
        targetTestApp = target,
        config = TrailblazeConfig(),
        sessionId = sessionId,
        deviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID),
          trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
          widthPixels = 1080,
          heightPixels = 1920,
        ),
        logsRepo = LogsRepo(logsDir = dir.resolve("logs").also { it.mkdirs() }, watchFileSystem = false),
        toolRepo = toolRepo,
        classLoader = javaClass.classLoader,
        logPrefix = "[test]",
        onProgressMessage = { progress += it },
      )
    }

    assertNull(runtime)
    assertEquals(emptyList(), progress)
    assertSame(earlier, toolRepo.getRegisteredDynamicTools()[ToolName("fixture_shared_subprocess")])
  }

  private fun registration(toolName: String): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(toolName)
      override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(name = toolName)

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("not built in this test")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = error("not decoded in this test")
    }
}
