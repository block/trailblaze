package xyz.block.trailblaze.ios.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.orchestra.Command
import maestro.orchestra.SetPermissionsCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** What `ios_setPermissions` hands the driver for notifications, and where it refuses to run. */
class IosSetPermissionsTrailblazeToolTest {

  /** A driver with no answer of its own (ios-host): the answer goes to Maestro's runner. */
  private open class RecordingAgent(
    private val result: TrailblazeToolResult = TrailblazeToolResult.Success(),
    deviceInfo: TrailblazeDeviceInfo = iosDevice,
  ) :
    MaestroTrailblazeAgent(
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = { deviceInfo },
      sessionProvider = sessionProvider,
    ) {
    val commands = mutableListOf<Command>()

    override suspend fun executeMaestroCommands(commands: List<Command>, traceId: TraceId?): TrailblazeToolResult {
      this.commands += commands
      return result
    }
  }

  /** A driver that answers the alert itself (ios-axe). */
  private class AnsweringAgent(deviceInfo: TrailblazeDeviceInfo = iosDevice) : RecordingAgent(deviceInfo = deviceInfo) {
    val answers = mutableListOf<Pair<String, Boolean?>>()

    override suspend fun answerNotificationsAlert(appId: String, allow: Boolean?, traceId: TraceId?): TrailblazeToolResult {
      answers += appId to allow
      return TrailblazeToolResult.Success()
    }
  }

  @Test
  fun `a driver that answers the alert itself gets the answer, with no Maestro command`() {
    val agent = AnsweringAgent()
    val result = run(notifications = "deny", agent)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Pair<String, Boolean?>>(APP_ID to false), agent.answers)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `allow hands the driver an Allow answer for the app`() {
    val agent = RecordingAgent()
    val result = run(notifications = "allow", agent)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Command>(SetPermissionsCommand(APP_ID, mapOf("notifications" to "allow"))), agent.commands)
  }

  @Test
  fun `deny hands the driver a Don't Allow answer`() {
    val agent = RecordingAgent()
    run(notifications = "deny", agent)

    assertEquals(listOf<Command>(SetPermissionsCommand(APP_ID, mapOf("notifications" to "deny"))), agent.commands)
  }

  @Test
  fun `unset clears a driver notification answer previously set in the session`() {
    val agent = AnsweringAgent()
    run(notifications = "allow", agent)

    val result = run(notifications = "unset", agent)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf(APP_ID to true, APP_ID to null), agent.answers)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `unset hands the host driver a command to clear its notification answer`() {
    val agent = RecordingAgent()
    run(notifications = "allow", agent)

    val result = run(notifications = "unset", agent)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(
      listOf<Command>(
        SetPermissionsCommand(APP_ID, mapOf("notifications" to "allow")),
        SetPermissionsCommand(APP_ID, mapOf("notifications" to "unset")),
      ),
      agent.commands,
    )
  }

  @Test
  fun `grantAll delegates the legacy all allow behavior to the driver`() {
    val agent = RecordingAgent()
    val result = run(notifications = null, agent, grantAll = true)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Command>(SetPermissionsCommand(APP_ID, mapOf("all" to "allow"))), agent.commands)
  }

  @Test
  fun `an explicit notification answer overrides grantAll`() {
    val agent = RecordingAgent()
    val result = run(notifications = "unset", agent, grantAll = true)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(
      listOf<Command>(
        SetPermissionsCommand(APP_ID, mapOf("all" to "allow")),
        SetPermissionsCommand(APP_ID, mapOf("notifications" to "unset")),
      ),
      agent.commands,
    )
  }

  @Test
  fun `a failed grantAll stops before explicit privacy or notification changes`() {
    val refusal = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "runner unreachable")
    val agent = RecordingAgent(refusal)
    val result = run(notifications = "deny", agent, revoke = listOf("microphone"), grantAll = true)

    assertEquals(refusal, result)
    assertEquals(listOf<Command>(SetPermissionsCommand(APP_ID, mapOf("all" to "allow"))), agent.commands)
  }

  @Test
  fun `a driver that refuses the answer fails the call`() {
    val refusal = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "runner unreachable")
    val result = run(notifications = "allow", RecordingAgent(refusal))

    assertEquals(refusal, result)
  }

  @Test
  fun `an unknown notifications answer is refused before any permission changes`() {
    val agent = RecordingAgent()
    // A simctl call would fail with a different message: off macOS, or for a simulator that isn't there.
    val result = run(notifications = "invalid", agent, revoke = listOf("microphone"), grantAll = true)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("notifications must be" in error.errorMessage, error.errorMessage)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `omitting notifications preserves a driver answer previously set in the session`() {
    val agent = AnsweringAgent()
    run(notifications = "deny", agent)
    val result = run(notifications = null, agent)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Pair<String, Boolean?>>(APP_ID to false), agent.answers)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `unset without a notification driver is refused before any permission changes`() {
    val result = run(notifications = "unset", agent = null, revoke = listOf("microphone"))

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("needs a Maestro-command driver" in error.errorMessage, error.errorMessage)
  }

  @Test
  fun `grantAll without a permission driver is refused before any privacy changes`() {
    val result = run(notifications = null, agent = null, revoke = listOf("microphone"), grantAll = true)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("needs a Maestro-command driver" in error.errorMessage, error.errorMessage)
  }

  @Test
  fun `camera changes retain the notification answer established by grantAll`() {
    val agent = RecordingAgent()
    val result = run(null, agent, revoke = listOf("camera"), grantAll = true)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Command>(
      SetPermissionsCommand(APP_ID, mapOf("all" to "allow")),
      SetPermissionsCommand(APP_ID, mapOf("camera" to "deny", "notifications" to "allow")),
    ), agent.commands)
  }

  @Test
  fun `camera cannot erase an unspecified earlier notification policy`() {
    val agent = RecordingAgent()
    run("deny", agent)
    val before = agent.commands.toList()

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(run(null, agent, grant = listOf("camera")))

    assertTrue("notifications" in error.errorMessage)
    assertEquals(before, agent.commands)
  }

  @Test
  fun `an explicit notification override follows camera changes after grantAll`() {
    val agent = RecordingAgent()
    val result = run("deny", agent, revoke = listOf("camera"), grantAll = true)

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Command>(
      SetPermissionsCommand(APP_ID, mapOf("all" to "allow")),
      SetPermissionsCommand(APP_ID, mapOf("camera" to "deny", "notifications" to "allow")),
      SetPermissionsCommand(APP_ID, mapOf("notifications" to "deny")),
    ), agent.commands)
  }

  @Test
  fun `camera grant includes its explicit notification policy without a broad grant`() {
    val agent = RecordingAgent()
    val result = run("deny", agent, grant = listOf("camera"))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals(listOf<Command>(
      SetPermissionsCommand(APP_ID, mapOf("camera" to "allow", "notifications" to "deny")),
      SetPermissionsCommand(APP_ID, mapOf("notifications" to "deny")),
    ), agent.commands)
  }

  @Test
  fun `camera reset revoke and grant retain their ordering`() {
    val agent = RecordingAgent()
    val result = run("deny", agent, reset = listOf("camera"), revoke = listOf("camera"), grant = listOf("camera"))

    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals<List<Command>>(listOf("unset", "deny", "allow").map {
      SetPermissionsCommand(APP_ID, mapOf("camera" to it, "notifications" to "deny"))
    } + SetPermissionsCommand(APP_ID, mapOf("notifications" to "deny")), agent.commands)
  }

  @Test
  fun `AXe camera reset is rejected without dispatching a permission command`() {
    val agent = RecordingAgent(deviceInfo = iosAxeDevice)
    val result = run(null, agent, reset = listOf("camera"), driverType = TrailblazeDriverType.IOS_AXE)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("camera" in error.errorMessage && "IOS_HOST" in error.errorMessage, error.errorMessage)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `AXe camera revoke is rejected without dispatching a permission command`() {
    val agent = RecordingAgent(deviceInfo = iosAxeDevice)
    val result = run(null, agent, revoke = listOf("camera"), driverType = TrailblazeDriverType.IOS_AXE)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("camera" in error.errorMessage && "IOS_HOST" in error.errorMessage, error.errorMessage)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `AXe camera grant is rejected without dispatching a permission command`() {
    val agent = RecordingAgent(deviceInfo = iosAxeDevice)
    val result = run(null, agent, grant = listOf("camera"), driverType = TrailblazeDriverType.IOS_AXE)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("camera" in error.errorMessage && "IOS_HOST" in error.errorMessage, error.errorMessage)
    assertEquals(emptyList(), agent.commands)
  }

  @Test
  fun `AXe camera rejection precedes grantAll privacy changes and notification answers`() {
    val agent = AnsweringAgent(iosAxeDevice)
    run("allow", agent, driverType = TrailblazeDriverType.IOS_AXE)

    val result = run(
      "deny",
      agent,
      reset = listOf("microphone"),
      grant = listOf("camera"),
      grantAll = true,
      driverType = TrailblazeDriverType.IOS_AXE,
    )

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("camera" in error.errorMessage && "IOS_HOST" in error.errorMessage, error.errorMessage)
    assertEquals(emptyList(), agent.commands)
    assertEquals(listOf<Pair<String, Boolean?>>(APP_ID to true), agent.answers)
  }

  @Test
  fun `failed camera setup prevents a later notification change`() {
    val failure = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "camera unavailable")
    val agent = RecordingAgent(failure)

    assertEquals(failure, run("allow", agent, grant = listOf("camera")))
    assertEquals(listOf<Command>(SetPermissionsCommand(APP_ID, mapOf("camera" to "allow", "notifications" to "allow"))), agent.commands)
  }

  @Test
  fun `Android is refused and pointed at POST_NOTIFICATIONS`() {
    val agent = RecordingAgent()
    val result = run(notifications = "allow", agent, platform = TrailblazeDevicePlatform.ANDROID)

    val error = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    assertTrue("POST_NOTIFICATIONS" in error.errorMessage, error.errorMessage)
    assertEquals(emptyList(), agent.commands)
  }

  private fun run(
    notifications: String?,
    agent: MaestroTrailblazeAgent?,
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
    revoke: List<String> = emptyList(),
    grantAll: Boolean = false,
    reset: List<String> = emptyList(),
    grant: List<String> = emptyList(),
    driverType: TrailblazeDriverType = TrailblazeDriverType.IOS_HOST,
  ): TrailblazeToolResult = runBlocking {
    IosSetPermissionsTrailblazeTool(appId = APP_ID, reset = reset, revoke = revoke, grant = grant, notifications = notifications, grantAll = grantAll).execute(
      TrailblazeToolExecutionContext(
        screenState = null,
        traceId = null,
        trailblazeDeviceInfo = if (platform == TrailblazeDevicePlatform.IOS) iosDevice.copy(trailblazeDriverType = driverType) else androidDevice,
        sessionProvider = sessionProvider,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        memory = AgentMemory(),
        maestroTrailblazeAgent = agent,
      ),
    )
  }

  private companion object {
    const val APP_ID = "com.example.app"

    val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    }

    val iosDevice = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-simulator",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
      ),
      trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
      widthPixels = 1179,
      heightPixels = 2556,
    )

    val iosAxeDevice = iosDevice.copy(trailblazeDriverType = TrailblazeDriverType.IOS_AXE)

    val androidDevice = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    )
  }
}
