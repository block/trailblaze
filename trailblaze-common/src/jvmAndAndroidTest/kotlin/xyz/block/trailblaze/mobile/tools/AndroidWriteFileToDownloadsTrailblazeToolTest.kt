package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Unit coverage for [AndroidWriteFileToDownloadsTrailblazeTool] mirroring
 * [AndroidGrantPermissionToolsTest]'s scope: every branch of `execute()` that doesn't require a
 * real [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor] (an `expect class`, not mockable
 * here), plus the static annotation contract. Happy-path writes are integration-tested by the
 * Square launch flow on a real device.
 */
class AndroidWriteFileToDownloadsTrailblazeToolTest {

  @Test fun `errors on iOS platform`() = runBlocking {
    val tool = AndroidWriteFileToDownloadsTrailblazeTool(fileName = "setup.json", content = "{}")
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
    assertThat(result.errorMessage).contains("IOS")
  }

  @Test fun `errors on a blank fileName before touching the executor`() = runBlocking {
    // Author-fixable validation runs before the infra (executor) check, so a blank fileName fails
    // with a routable message even when no executor is wired.
    val tool = AndroidWriteFileToDownloadsTrailblazeTool(fileName = "  ", content = "{}")
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("fileName must not be blank")
  }

  @Test fun `rejects a fileName with path separators or parent-dir segments (no traversal)`() = runBlocking {
    // The host transport concatenates fileName into /storage/emulated/0/Download/<fileName>, so a
    // path separator or `..` must be rejected up front rather than escaping the Downloads directory.
    for (bad in listOf("../escape.json", "sub/dir.json", "a\\b.json", "..")) {
      val result = AndroidWriteFileToDownloadsTrailblazeTool(fileName = bad, content = "{}")
        .execute(createContext(TrailblazeDevicePlatform.ANDROID))
      assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
      assertThat(result.errorMessage).contains("bare filename")
    }
  }

  @Test fun `errors when executor is missing`() = runBlocking {
    val tool = AndroidWriteFileToDownloadsTrailblazeTool(fileName = "setup.json", content = "{}")
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `annotation contract`() {
    // Pins: name matches the `android_writeFileToDownloads.tool.yaml` descriptor (registration is a
    // name-keyed lookup), `surfaceToLlm = false` (composition primitive, not LLM-callable), and
    // `requiresHost = false` so the on-device runner registers it too — the executor is dual-mode.
    // The NOT-`HostLocalExecutableTrailblazeTool` check catches a future refactor that accidentally
    // adds the host-only marker and silently gates the tool off on-device dispatch.
    val tool = AndroidWriteFileToDownloadsTrailblazeTool(fileName = "setup.json", content = "{}")
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    assertThat(tool).isNotInstanceOf(HostLocalExecutableTrailblazeTool::class)

    val annotation = AndroidWriteFileToDownloadsTrailblazeTool::class.java
      .getAnnotation(TrailblazeToolClass::class.java)
      ?: fail("@TrailblazeToolClass annotation missing from AndroidWriteFileToDownloadsTrailblazeTool")
    assertThat(annotation.name).isEqualTo("android_writeFileToDownloads")
    assertThat(annotation.surfaceToLlm).isEqualTo(false)
    assertThat(annotation.isRecordable).isEqualTo(false)
    assertThat(annotation.requiresHost).isEqualTo(false)
  }

  private fun createContext(platform: TrailblazeDevicePlatform): TrailblazeToolExecutionContext {
    val driverType = when (platform) {
      TrailblazeDevicePlatform.ANDROID -> TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> TrailblazeDriverType.PLAYWRIGHT_NATIVE
      TrailblazeDevicePlatform.DESKTOP -> TrailblazeDriverType.COMPOSE
    }
    return TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = platform,
        ),
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }
}
