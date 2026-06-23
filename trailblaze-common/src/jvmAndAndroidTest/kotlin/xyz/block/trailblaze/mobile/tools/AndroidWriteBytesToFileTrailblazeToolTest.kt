package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNull
import java.util.Base64
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
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Unit coverage for the byte-transfer primitive [AndroidWriteBytesToFileTrailblazeTool]
 * (`android_writeBytesToFile`) — the lone new framework write tool. It writes raw bytes (base64)
 * to an arbitrary absolute device path; MediaStore registration, MIME, perms, and a specific
 * public collection are deliberately left to `android_adbShell` composition, not params here.
 *
 * Covers the `execute()` branches that don't need a real
 * [xyz.block.trailblaze.device.AndroidDeviceCommandExecutor] (non-Android platform, invalid
 * path, malformed base64, missing executor), the pure path validator, the dual-mode annotation
 * contract, and YAML round-trip. Happy-path execution against a live executor is covered by an
 * on-device run.
 */
class AndroidWriteBytesToFileTrailblazeToolTest {

  private val trailblazeYaml = createTrailblazeYaml(setOf(AndroidWriteBytesToFileTrailblazeTool::class))

  private fun base64Of(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

  // -------------------------------------------------------------------------------------------
  // execute() failure branches that don't require a real executor
  // -------------------------------------------------------------------------------------------

  @Test fun `errors on iOS platform`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/storage/emulated/0/Download/setup.json",
      base64Content = base64Of("{}"),
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.IOS))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
    assertThat(result.errorMessage).contains("IOS")
  }

  @Test fun `errors on web platform`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(devicePath = "/sdcard/a.bin", base64Content = base64Of("{}"))
    val result = tool.execute(createContext(TrailblazeDevicePlatform.WEB))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("only supported on Android")
  }

  @Test fun `errors when executor is missing`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(devicePath = "/sdcard/a.bin", base64Content = base64Of("{}"))
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `errors on a relative path before touching the executor`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(devicePath = "Download/setup.json", base64Content = base64Of("{}"))
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("invalid devicePath")
    assertThat(result.errorMessage).contains("absolute")
  }

  @Test fun `errors on a blank path`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(devicePath = "   ", base64Content = base64Of("{}"))
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("invalid devicePath")
    assertThat(result.errorMessage).contains("blank")
  }

  @Test fun `valid base64 decodes then reaches the executor lookup`() = runBlocking {
    // Valid base64 decodes cleanly, so execute() proceeds past the decode and only then hits the
    // missing-executor branch — proving the decode path is wired and doesn't reject valid base64.
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/sdcard/Pictures/logo.png",
      base64Content = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `malformed base64 errors before the executor`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/sdcard/Pictures/logo.png",
      base64Content = "!!! not valid base64 !!!",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("base64-decode")
  }

  // -------------------------------------------------------------------------------------------
  // validateDevicePath — pure path guard (no executor needed)
  // -------------------------------------------------------------------------------------------

  @Test fun `validateDevicePath accepts absolute paths (including spaces, which are escaped downstream)`() {
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("/storage/emulated/0/Download/setup.json")).isNull()
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("/sdcard/Pictures/logo.png")).isNull()
    // A path is not charset-restricted — the executor single-quote-escapes it for any shell
    // mkdir/cp, and the host body transfer uses adb push (no shell). So spaces are allowed.
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("/sdcard/My Folder/a.bin")).isNull()
  }

  @Test fun `validateDevicePath rejects blank`() {
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("")!!).contains("blank")
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("   ")!!).contains("blank")
  }

  @Test fun `validateDevicePath rejects a relative path`() {
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("Download/setup.json")!!).contains("absolute")
    assertThat(AndroidWriteBytesToFileTrailblazeTool.validateDevicePath("./a.bin")!!).contains("absolute")
  }

  // -------------------------------------------------------------------------------------------
  // Dual-mode annotation contract
  // -------------------------------------------------------------------------------------------

  @Test fun `annotation contract (dual-mode, not LLM-facing, not recordable)`() {
    val tool = AndroidWriteBytesToFileTrailblazeTool(devicePath = "/sdcard/a.bin", base64Content = base64Of("{}"))
    assertThat(tool).isInstanceOf(ExecutableTrailblazeTool::class)
    assertThat(tool).isNotInstanceOf(HostLocalExecutableTrailblazeTool::class)

    val annotation = AndroidWriteBytesToFileTrailblazeTool::class.java
      .getAnnotation(TrailblazeToolClass::class.java)
      ?: fail("@TrailblazeToolClass annotation missing from AndroidWriteBytesToFileTrailblazeTool")
    assertThat(annotation.name).isEqualTo("android_writeBytesToFile")
    assertThat(annotation.surfaceToLlm).isEqualTo(false)
    assertThat(annotation.isRecordable).isEqualTo(false)
    assertThat(annotation.requiresHost).isEqualTo(false)
  }

  // -------------------------------------------------------------------------------------------
  // Serialization
  // -------------------------------------------------------------------------------------------

  @Test fun `decodes from trail YAML`() {
    val yaml = """
      - tools:
          - android_writeBytesToFile:
              devicePath: /storage/emulated/0/Download/logo.png
              base64Content: AQIDBA==
    """.trimIndent()

    val tool = (trailblazeYaml.decodeTrail(yaml).single() as TrailYamlItem.ToolTrailItem)
      .tools.single().trailblazeTool as AndroidWriteBytesToFileTrailblazeTool

    assertThat(tool.devicePath).isEqualTo("/storage/emulated/0/Download/logo.png")
    assertThat(tool.base64Content).isEqualTo("AQIDBA==")
  }

  @Test fun `round-trips through YAML encode-then-decode`() {
    val original = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/sdcard/Pictures/logo.png",
      base64Content = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
    )
    val yamlInstance = trailblazeYaml.getInstance()
    val encoded = yamlInstance.encodeToString(AndroidWriteBytesToFileTrailblazeTool.serializer(), original)
    val decoded = yamlInstance.decodeFromString(AndroidWriteBytesToFileTrailblazeTool.serializer(), encoded)
    assertThat(decoded).isEqualTo(original)
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
