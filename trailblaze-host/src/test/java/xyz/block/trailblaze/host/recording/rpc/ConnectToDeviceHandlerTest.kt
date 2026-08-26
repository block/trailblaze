package xyz.block.trailblaze.host.recording.rpc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.recording.DeviceConnectionService
import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DefaultDeviceClassifierIconProvider
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import java.io.File
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a caller gets back from [ConnectToDeviceHandler] when the target it named can't be bound:
 * an unregistered target refused BEFORE a live connection is reused, and the message a target
 * conflict hands back - including which recovery it advises.
 *
 * These are what the caller sees instead of the wrong app running, and neither is reachable from
 * [HostDeviceSessionManagerTest]: the registry knows nothing about which targets this daemon has
 * registered, and the wording is assembled here.
 *
 * No device is touched: every case is decided before the handler reaches the connection service,
 * which is the point of resolving and checking the binding up front.
 */
class ConnectToDeviceHandlerTest {

  private val tempDir: File = File.createTempFile("trailblaze-connect-handler-", "").also {
    it.delete()
    it.mkdirs()
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  /**
   * The Playwright-native browser, because the handler synthesizes its summary from the device id
   * alone - no discovered device, and `bindsTargetApp` is true for it, so the binding is recorded.
   */
  private val deviceId = TrailblazeDeviceId(WebInstanceIds.PLAYWRIGHT_NATIVE, TrailblazeDevicePlatform.WEB)

  private class FakeTarget(id: String) : TrailblazeHostAppTarget(id = id, displayName = id) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> = emptyList()
    override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private class StubStream : DeviceScreenStream {
    override val deviceWidth: Int = 800
    override val deviceHeight: Int = 600
    override fun frames(): Flow<ByteArray> = emptyFlow()
    override suspend fun tap(x: Int, y: Int) {}
    override suspend fun longPress(x: Int, y: Int) {}
    override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {}
    override suspend fun inputText(text: String) {}
    override suspend fun pressKey(key: String) {}
    override suspend fun getViewHierarchy(): ViewHierarchyTreeNode = error("not used")
    override suspend fun getTrailblazeNodeTree(): TrailblazeNode? = null
    override suspend fun getScreenshot(): ByteArray = ByteArray(0)
    override suspend fun getMirrorScreenshot(): ByteArray = ByteArray(0)
  }

  private fun handler(sessionManager: HostDeviceSessionManager, registered: Set<String>): ConnectToDeviceHandler {
    val deviceManager = TrailblazeDeviceManager(
      logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false),
      settingsRepo = TrailblazeSettingsRepo(
        settingsFile = File(tempDir, "settings-${System.nanoTime()}.json"),
        initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
        defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
        allTargetApps = { emptySet() },
        supportedDriverTypes = emptySet(),
      ),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      currentTrailblazeLlmModelProvider = {
        TrailblazeLlmModel(
          trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
          modelId = "test-model",
          inputCostPerOneMillionTokens = 0.0,
          outputCostPerOneMillionTokens = 0.0,
          contextLength = 1000,
          maxOutputTokens = 1000,
          capabilityIds = emptyList(),
        )
      },
      initialAppTargets = registered.map { FakeTarget(it) }.toSet(),
      appIconProvider = AppIconProvider.DefaultAppIconProvider,
      deviceClassifierIconProvider = DefaultDeviceClassifierIconProvider,
      runYamlLambda = {},
      installedAppIdsProviderBlocking = { emptySet() },
      appVersionInfoProviderBlocking = { _, _ -> null },
      onDeviceInstrumentationArgsProvider = { emptyMap() },
      trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    )
    return ConnectToDeviceHandler(
      deviceManager = deviceManager,
      connectionService = DeviceConnectionService(deviceManager),
      sessionManager = sessionManager,
    )
  }

  private fun connect(sessionManager: HostDeviceSessionManager, registered: Set<String>, targetAppId: String?) =
    runBlocking {
      handler(sessionManager, registered).handle(ConnectToDeviceRequest(deviceId, targetAppId))
    }

  private fun failureMessage(result: RpcResult<*>): String {
    assertTrue(result is RpcResult.Failure, "expected a refusal, got $result")
    return result.message
  }

  @Test
  fun `an unregistered target is refused even though the device is already connected`() {
    val sessionManager = HostDeviceSessionManager()
    runBlocking { sessionManager.connectIfAbsent(deviceId, HostDeviceSessionManager.Binding("app-a")) { StubStream() } }

    // The reuse path would happily hand this caller the live stream. It doesn't get that far: an id
    // this daemon has never heard of is a caller bug, and reporting success for it would run app-a
    // while the caller believes it named something else entirely.
    val message = failureMessage(connect(sessionManager, registered = setOf("app-a"), targetAppId = "gone-app"))
    assertTrue("gone-app" in message, message)
    assertTrue("not registered" in message, message)
  }

  @Test
  fun `a session connected for another target is refused, naming both`() {
    val sessionManager = HostDeviceSessionManager()
    runBlocking { sessionManager.connectIfAbsent(deviceId, HostDeviceSessionManager.Binding("app-a")) { StubStream() } }

    val message = failureMessage(connect(sessionManager, setOf("app-a", "app-b"), targetAppId = "app-b"))
    // Both ids, because neither alone tells the caller what to do: the bound one says why the
    // connection can't be reused, the requested one says what the retry is for.
    assertTrue("app-a" in message, message)
    assertTrue("app-b" in message, message)
    assertTrue("Disconnect it" in message, message)
  }

  @Test
  fun `a recorder-owned session says to stop the recording, not to disconnect`() {
    val sessionManager = HostDeviceSessionManager()
    // `attach` is the recorder publishing a connection it still owns and drives. Disconnecting only
    // drops it from this registry, so following that advice would open a SECOND connection to a
    // device mid-recording.
    sessionManager.attach(deviceId, StubStream(), HostDeviceSessionManager.Binding("app-a"))

    val message = failureMessage(connect(sessionManager, setOf("app-a", "app-b"), targetAppId = "app-b"))
    assertTrue("being recorded for target 'app-a'" in message, message)
    assertTrue("Stop that recording" in message, message)
    assertTrue("Disconnect" !in message, message)
  }

  @Test
  fun `a connect for the target it is already bound to is handed the live session`() {
    val sessionManager = HostDeviceSessionManager()
    val stream = StubStream()
    runBlocking { sessionManager.connectIfAbsent(deviceId, HostDeviceSessionManager.Binding("app-a")) { stream } }

    val result = connect(sessionManager, setOf("app-a"), targetAppId = "app-a")
    assertTrue(result is RpcResult.Success, "expected the existing session, got $result")
    assertEquals(stream.deviceWidth, result.data.deviceWidth)
    assertEquals(stream.deviceHeight, result.data.deviceHeight)
  }
}
