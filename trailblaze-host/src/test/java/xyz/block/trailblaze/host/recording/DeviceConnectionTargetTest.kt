package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.recording.DeviceConnectionService.ConnectTarget
import xyz.block.trailblaze.host.FakeHostAppTarget
import xyz.block.trailblaze.host.recording.DeviceConnectionService.Companion.bindsTargetApp
import xyz.block.trailblaze.host.recording.DeviceConnectionService.Companion.connectionBinding
import xyz.block.trailblaze.host.recording.DeviceConnectionService.Companion.resolveConnectTarget
import xyz.block.trailblaze.host.recording.rpc.HostDeviceSessionManager.Binding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which app target a connect binds. The Android connect installs the target's instrumentation
 * runner, the iOS connect builds the target's own driver wrapper, and the web connect loads its
 * custom tools, so binding a different app than the caller named runs the trail against unrelated
 * automation while still reporting the named target, the same failure the run path's `config.target`
 * rule exists to prevent.
 *
 * Pure decision, driven with plain ids: no daemon, no device.
 */
class DeviceConnectionTargetTest {

  private val registered = setOf("alpha-app", "beta-app")

  @Test
  fun `a caller that names no target keeps the daemon's selection`() {
    assertEquals(ConnectTarget.DaemonSelected, resolveConnectTarget(null, registered))
  }

  @Test
  fun `a blank id is treated as naming no target rather than failing the connect`() {
    assertEquals(ConnectTarget.DaemonSelected, resolveConnectTarget("", registered))
    assertEquals(ConnectTarget.DaemonSelected, resolveConnectTarget("   ", registered))
  }

  @Test
  fun `a registered id wins over whatever the daemon has selected`() {
    // The whole point: the Run dialog knows which app this device is being connected for, and the
    // daemon-wide selection is a different app whenever the two disagree.
    assertEquals(ConnectTarget.Requested("beta-app"), resolveConnectTarget("beta-app", registered))
    assertEquals(ConnectTarget.Requested("beta-app"), resolveConnectTarget("  beta-app  ", registered))
  }

  @Test
  fun `an unregistered id fails the connect instead of falling back`() {
    assertEquals(ConnectTarget.Unregistered("gone-app"), resolveConnectTarget("gone-app", registered))
    assertEquals(ConnectTarget.Unregistered("alpha-app"), resolveConnectTarget("alpha-app", emptySet()))
  }

  // ─── Which connects a target can be bound to ───
  //
  // Only a connect that installs or launches the target can be REFUSED for a different one. Binding
  // a driver whose connect ignores the target would reject a request that disconnecting and
  // reconnecting satisfies with the identical stream.

  @Test
  fun `the android connect binds its target`() {
    // It installs that target's instrumentation runner, so the connection IS the app.
    assertTrue(bindsTargetApp(TrailblazeDevicePlatform.ANDROID, TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION))
    assertTrue(bindsTargetApp(TrailblazeDevicePlatform.ANDROID, TrailblazeDriverType.DEFAULT_ANDROID))
  }

  @Test
  fun `a launched browser binds its target but an electron attach does not`() {
    // Launching loads the target's custom tools into the fresh browser; attaching takes whatever
    // Electron app is already running, target or no target.
    assertTrue(bindsTargetApp(TrailblazeDevicePlatform.WEB, TrailblazeDriverType.PLAYWRIGHT_NATIVE))
    assertFalse(bindsTargetApp(TrailblazeDevicePlatform.WEB, TrailblazeDriverType.PLAYWRIGHT_ELECTRON))
  }

  @Test
  fun `the maestro ios connect binds its target but a host-native one does not`() {
    // The Maestro driver is built per-target: a target declaring `hasCustomIosDriver` wraps the
    // base IOSDriver in its own subclass, so the target a connect names decides which driver it
    // gets. The host-native drivers talk to the simulator directly and build no wrapper.
    assertTrue(bindsTargetApp(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_HOST))
    assertTrue(bindsTargetApp(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.DEFAULT_IOS))
    assertFalse(bindsTargetApp(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_AXE))
  }

  @Test
  fun `every host-native ios driver is exempt, not just the one spelled out above`() {
    // The exemption keys off `hostNativeSimulatorDriver`, so declaring it on a new driver (the
    // documented way to add one) exempts it here too rather than silently binding a target that
    // its connect never receives.
    TrailblazeDriverType.entries.filter { it.hostNativeSimulatorDriver }.forEach {
      assertFalse(bindsTargetApp(TrailblazeDevicePlatform.IOS, it), "$it should not bind a target")
    }
  }

  @Test
  fun `only an ios connect records the driver its target would build`() {
    // The driver half exists to separate "no target" from "a target that wraps the driver", which
    // is a distinction only the Maestro iOS connect can make. Everywhere else it stays null so this
    // change cannot alter who may share an Android or web connection.
    val custom = FakeHostAppTarget("square", hasCustomIosDriver = true)
    assertEquals(
      Binding(targetId = "square", driverKey = "square", buildsMaestroDriver = true),
      connectionBinding(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_HOST, custom),
    )
    assertEquals(
      Binding(targetId = "square", driverKey = null, buildsMaestroDriver = false),
      connectionBinding(TrailblazeDevicePlatform.ANDROID, TrailblazeDriverType.DEFAULT_ANDROID, custom),
    )
    assertEquals(
      Binding(targetId = "square", driverKey = null, buildsMaestroDriver = false),
      connectionBinding(TrailblazeDevicePlatform.WEB, TrailblazeDriverType.PLAYWRIGHT_NATIVE, custom),
    )
  }

  @Test
  fun `a plain ios target records no driver, so it shares with a targetless connect`() {
    // Both really do produce the identical base driver. Recording a driver here would ask the user
    // to disconnect and reconnect to rebuild the very same thing.
    val plain = FakeHostAppTarget("plain")
    assertEquals(
      Binding(targetId = "plain", driverKey = null, buildsMaestroDriver = true),
      connectionBinding(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_HOST, plain),
    )
    assertEquals(
      Binding(buildsMaestroDriver = true),
      connectionBinding(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_HOST, null),
    )
  }

  @Test
  fun `a connect that binds nothing records nothing, whatever target is selected`() {
    // Otherwise these would refuse a second connect over a difference that makes no difference to
    // the stream they hand back.
    val custom = FakeHostAppTarget("square", hasCustomIosDriver = true)
    assertEquals(
      Binding(buildsMaestroDriver = false),
      connectionBinding(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_AXE, custom),
    )
    assertEquals(
      Binding(buildsMaestroDriver = false),
      connectionBinding(TrailblazeDevicePlatform.WEB, TrailblazeDriverType.PLAYWRIGHT_ELECTRON, custom),
    )
  }

  @Test
  fun `a host-native ios connect says it has no driver here, not that it has the base one`() {
    // The distinction the driver axis needs and `null` alone cannot carry. AXe talks to the
    // simulator directly and never enters `HostIosDriverFactory`, so it can neither rebuild nor be
    // rebuilt - unlike a targetless Maestro connect, which really is holding the base driver and
    // really does conflict with a wrapper target. Both have no driver key; only one of them is in
    // the running for a driver conflict at all.
    val custom = FakeHostAppTarget("square", hasCustomIosDriver = true)
    val axe = connectionBinding(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_AXE, custom)
    val targetlessMaestro = connectionBinding(TrailblazeDevicePlatform.IOS, TrailblazeDriverType.IOS_HOST, null)

    assertNull(axe.driverKey)
    assertNull(targetlessMaestro.driverKey)
    assertFalse(axe.buildsMaestroDriver, "AXe builds no driver this registry has a stake in")
    assertTrue(targetlessMaestro.buildsMaestroDriver, "a targetless Maestro connect holds the base driver")
    assertNotEquals(axe, targetlessMaestro, "and so the two must not be the same binding")
  }
}
