package xyz.block.trailblaze.mobile.tools

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.util.Base64
import kotlin.test.assertIs
import xyz.block.trailblaze.device.MAX_RUN_AS_WRITE_CONTENT_BYTES
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
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
 * path, invalid `runAs`, malformed base64, missing executor), the pure path and `run-as`
 * validators, the dual-mode annotation contract, and YAML round-trip. Happy-path execution
 * against a live executor is covered by an on-device run.
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
  // runAs — the `run-as` write into an app's private data dir
  // -------------------------------------------------------------------------------------------

  /**
   * Every run-as precondition must be reported BEFORE the executor lookup. Two reasons: a
   * metacharacter-bearing package name must never reach the `run-as` shell wrapper on a
   * device-backed context, and a caller who violated the cap or aimed at a directory needs to be
   * told that — not "executor is not provided", which names the wrong problem and hides the real
   * one until the wiring is fixed. `doesNotContain("AndroidDeviceCommandExecutor")` is the
   * load-bearing assertion in each of these.
   */
  @Test fun `errors on a runAs bearing shell metacharacters, before the executor lookup`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/data/com.example.app/shared_prefs/debug.xml",
      base64Content = base64Of("<map/>"),
      runAs = "com.example.app; rm -rf /",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("valid Android package name")
    assertThat(result.errorMessage).doesNotContain("AndroidDeviceCommandExecutor")
  }

  @Test fun `errors on a blank runAs`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/data/com.example.app/files/a.bin",
      base64Content = base64Of("x"),
      runAs = "   ",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("blank")
    assertThat(result.errorMessage).doesNotContain("AndroidDeviceCommandExecutor")
  }

  @Test fun `errors on an over-cap runAs payload, before the executor lookup`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/data/com.example.app/files/big.bin",
      base64Content = Base64.getEncoder().encodeToString(ByteArray(MAX_RUN_AS_WRITE_CONTENT_BYTES + 1)),
      runAs = "com.example.app",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("over the $MAX_RUN_AS_WRITE_CONTENT_BYTES-byte cap")
    assertThat(result.errorMessage).doesNotContain("AndroidDeviceCommandExecutor")
  }

  @Test fun `a payload exactly at the cap is not rejected`() = runBlocking {
    // Boundary: the cap is inclusive, so this must fall through to the executor lookup rather
    // than reporting the cap. Pins that the comparison is `>` and not `>=`.
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/data/com.example.app/files/big.bin",
      base64Content = Base64.getEncoder().encodeToString(ByteArray(MAX_RUN_AS_WRITE_CONTENT_BYTES)),
      runAs = "com.example.app",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("AndroidDeviceCommandExecutor")
  }

  @Test fun `errors on a directory devicePath under runAs, before the executor lookup`() = runBlocking {
    val tool = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/data/com.example.app/shared_prefs/",
      base64Content = base64Of("<map/>"),
      runAs = "com.example.app",
    )
    val result = tool.execute(createContext(TrailblazeDevicePlatform.ANDROID))

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertThat(result.errorMessage).contains("must name a file, not a directory")
    assertThat(result.errorMessage).doesNotContain("AndroidDeviceCommandExecutor")
  }

  @Test fun `validateRunAsWrite accepts a real package name, file path and in-cap payload`() {
    assertThat(
      AndroidWriteBytesToFileTrailblazeTool.validateRunAsWrite(
        runAs = "com.example.app",
        devicePath = "/data/data/com.example.app/shared_prefs/debug.xml",
        contentSize = 1024,
      ),
    ).isNull()
    assertThat(
      AndroidWriteBytesToFileTrailblazeTool.validateRunAsWrite("com.foo.my_app", "/data/data/com.foo.my_app/a", 0),
    ).isNull()
  }

  @Test fun `validateRunAsWrite rejects blank and single-segment ids`() {
    assertThat(validateRunAsWriteFor("")!!).contains("blank")
    assertThat(validateRunAsWriteFor("   ")!!).contains("blank")
    assertThat(validateRunAsWriteFor("foo")!!).contains("package name")
  }

  @Test fun `validateRunAsWrite rejects shell metacharacters`() {
    // The package-name grammar IS the escaping scheme for this token — anything that could split
    // or extend the `run-as` invocation must be refused rather than quoted.
    listOf(
      "com.example.app; rm -rf /",
      "com.example.app && id",
      "com.example.app | cat",
      "com.example.app\$(id)",
      "com.example.app`id`",
      "com.example app",
      "'com.example.app'",
    ).forEach { bad ->
      assertThat(validateRunAsWriteFor(bad), name = bad).isNotNull().contains("package name")
    }
  }

  private fun validateRunAsWriteFor(runAs: String) = AndroidWriteBytesToFileTrailblazeTool
    .validateRunAsWrite(runAs = runAs, devicePath = "/data/data/com.example.app/a.bin", contentSize = 1)

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
      config: {}
      trail:
        - step: recorded
          recording:
            android:
              - android_writeBytesToFile:
                  devicePath: /storage/emulated/0/Download/logo.png
                  base64Content: AQIDBA==
    """.trimIndent()

    val tool = trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single()
      .trailblazeTool as AndroidWriteBytesToFileTrailblazeTool

    assertThat(tool.devicePath).isEqualTo("/storage/emulated/0/Download/logo.png")
    assertThat(tool.base64Content).isEqualTo("AQIDBA==")
    assertThat(tool.runAs).isNull()
  }

  @Test fun `decodes runAs from trail YAML`() {
    val yaml = """
      config: {}
      trail:
        - step: recorded
          recording:
            android:
              - android_writeBytesToFile:
                  devicePath: /data/data/com.example.app/shared_prefs/debug.xml
                  base64Content: AQIDBA==
                  runAs: com.example.app
    """.trimIndent()

    val tool = trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single()
      .trailblazeTool as AndroidWriteBytesToFileTrailblazeTool

    assertThat(tool.runAs).isEqualTo("com.example.app")
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

  @Test fun `round-trips runAs through YAML encode-then-decode`() {
    val original = AndroidWriteBytesToFileTrailblazeTool(
      devicePath = "/data/data/com.example.app/shared_prefs/debug.xml",
      base64Content = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
      runAs = "com.example.app",
    )
    val yamlInstance = trailblazeYaml.getInstance()
    val encoded = yamlInstance.encodeToString(AndroidWriteBytesToFileTrailblazeTool.serializer(), original)
    // Anchored on the key, not the bare id: the devicePath already contains the package name, so
    // `contains("com.example.app")` would pass even with runAs dropped from serialization.
    assertThat(encoded).contains("runAs: com.example.app")
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
