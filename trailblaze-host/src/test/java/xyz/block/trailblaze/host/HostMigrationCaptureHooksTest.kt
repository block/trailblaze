package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import maestro.DeviceInfo
import maestro.TreeNode
import maestro.device.Platform
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import kotlin.test.assertFailsWith

/**
 * The per-tool snapshot hooks are what `migrate-trail` reads a trail's `preTool:` / `postTool:`
 * frames out of, and both host replay paths (Android RPC and in-host iOS/web) now install the
 * same ones. These pin which tools get a frame, what each frame is named, and that a capture
 * failure can never take a replay down while a cancellation still unwinds it.
 */
class HostMigrationCaptureHooksTest {

  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 393
    override val deviceHeight: Int = 852
    override val viewHierarchy: ViewHierarchyTreeNode get() = error("not used by this test")
    override val trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }

  private val session = TrailblazeSession(
    sessionId = SessionId("hooks-test"),
    startTime = Clock.System.now(),
  )

  private val loggedDisplayNames = mutableListOf<String>()

  private fun hooks(
    enabled: Boolean = true,
    hasProducer: () -> Boolean = { true },
    captureScreenState: suspend () -> ScreenState? = { FakeScreenState() },
    sessionProvider: () -> TrailblazeSession? = { session },
  ) = HostMigrationCapture.recordedToolHooks(
    enabled = enabled,
    hasProducer = hasProducer,
    captureScreenState = captureScreenState,
    sessionProvider = sessionProvider,
    logSnapshot = { _, _, displayName -> loggedDisplayNames.add(displayName) },
  )

  private val tap = TapOnByElementSelector(reason = "tap it")
  private val assertVisible = AssertVisibleBySelectorTrailblazeTool(reason = "see it")
  private val launch = LaunchAppTrailblazeTool(appId = "com.example.app")

  @Test
  fun `disabled installs no hooks at all`() {
    assertThat(hooks(enabled = false)).isNull()
  }

  @Test
  fun `no producer logs no frame even with the switch on`() = runBlocking {
    // Without a producer every hook frame would carry one tree, which migrate-trail skips — so
    // the frames would only be extra snapshots in the session log.
    val hooks = hooks(hasProducer = { false })!!

    hooks.onBefore(tap)
    hooks.onAfter(assertVisible)

    assertThat(loggedDisplayNames).isEmpty()
  }

  @Test
  fun `the producer is asked per tool so a device switch stops the frames`() = runBlocking {
    // A multi-device run that switches to a web companion mid-trail has no producer from then on.
    var activeDeviceProduces = true
    val hooks = hooks(hasProducer = { activeDeviceProduces })!!

    hooks.onBefore(tap)
    activeDeviceProduces = false
    hooks.onBefore(assertVisible)
    hooks.onAfter(assertVisible)

    assertThat(loggedDisplayNames).containsExactly("preTool: TapOnByElementSelector")
  }

  @Test
  fun `only a Maestro-backed iOS device is a host producer`() {
    assertThat(HostMigrationCapture.hasHostProducer(maestroDevice(TrailblazeDriverType.IOS_HOST))).isTrue()
    // Android runs through the on-device RPC path, not this one.
    assertThat(HostMigrationCapture.hasHostProducer(maestroDevice(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)))
      .isFalse()
    assertThat(HostMigrationCapture.hasHostProducer(null)).isFalse()
  }

  @Test
  fun `a blank udid is not a producer, matching the producer's own check`() {
    // The producer reads Axe for a udid; with none it declines, so the hooks must not install.
    assertThat(HostMigrationCapture.hasHostProducer(maestroDevice(TrailblazeDriverType.IOS_HOST, instanceId = " ")))
      .isFalse()
  }

  /** A Maestro-backed device for [driverType]; the fake driver answers only what construction reads. */
  private fun maestroDevice(driverType: TrailblazeDriverType, instanceId: String = "fake-instance") = MaestroConnectedDevice(
    maestroDriver = ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(attributes = mutableMapOf("bounds" to "[0,0][400,800]")),
      deviceInfo = DeviceInfo(
        platform = Platform.ANDROID,
        widthPixels = 400,
        heightPixels = 800,
        widthGrid = 400,
        heightGrid = 800,
      ),
    ),
    trailblazeDriverType = driverType,
    instanceId = instanceId,
  )

  @Test
  fun `pre-tool snapshots the selector-bearing tools and names each frame after the tool`() = runBlocking {
    val hooks = hooks().also { assertThat(it).isNotNull() }!!

    hooks.onBefore(tap)
    hooks.onBefore(assertVisible)

    assertThat(loggedDisplayNames).containsExactly(
      "preTool: TapOnByElementSelector",
      "preTool: AssertVisibleBySelectorTrailblazeTool",
    )
  }

  @Test
  fun `pre-tool ignores a tool a migration never rewrites`() = runBlocking {
    hooks()!!.onBefore(launch)

    assertThat(loggedDisplayNames).isEmpty()
  }

  @Test
  fun `post-tool snapshots the assert only`() = runBlocking {
    val hooks = hooks()!!

    hooks.onAfter(tap)
    hooks.onAfter(launch)
    hooks.onAfter(assertVisible)

    assertThat(loggedDisplayNames).containsExactly("postTool: AssertVisibleBySelectorTrailblazeTool")
  }

  @Test
  fun `a capture failure is swallowed and logs no frame`() = runBlocking {
    val hooks = hooks(captureScreenState = { error("device went away") })!!

    hooks.onBefore(tap)
    hooks.onAfter(assertVisible)

    assertThat(loggedDisplayNames).isEmpty()
  }

  @Test
  fun `no session yet means no frame, not a failure`() = runBlocking {
    val hooks = hooks(sessionProvider = { null })!!

    hooks.onBefore(tap)

    assertThat(loggedDisplayNames).isEmpty()
  }

  @Test
  fun `cancellation propagates so an aborted trail still unwinds`() {
    val hooks = hooks(captureScreenState = { throw CancellationException("trail aborted") })!!

    assertFailsWith<CancellationException> { runBlocking { hooks.onBefore(tap) } }
    assertThat(loggedDisplayNames).isEmpty()
  }
}
