package xyz.block.trailblaze.toolcalls

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

class MultiDevicePromptContextTest {

  @Test
  fun `renders roster metadata and the active device`() {
    val bindings = bindings()

    val section = bindings.renderMultiDevicePromptSection(handoverToolAdvertised = true)

    assertThat(section).contains("## Multi-device session")
    assertThat(section).contains("`seller`: Merchant-facing POS; classifiers: android-tablet-primary; target: pos")
    assertThat(section).contains("`buyer`: Customer-facing display; classifiers: android-tablet-secondary; target: posBuyer")
    assertThat(section).contains("currently active device is `seller`")
    assertThat(section).doesNotContain("emulator-5560")
  }

  @Test
  fun `describes a device bound without probed info by name, role and target`() {
    // An interactive caller binds a device it was handed by name and has nothing to probe. The
    // roster must still let the model pick it as a handover destination.
    val bindings = SessionDeviceBindings(
      linkedMapOf(
        "seller" to bound("emulator-5560", "android-tablet-primary", "Merchant-facing POS", "pos"),
        "buyer" to SessionDeviceBindings.BoundDevice(
          trailblazeDeviceId = TrailblazeDeviceId("emulator-5562", TrailblazeDevicePlatform.ANDROID),
          trailblazeDeviceInfo = null,
          description = "Customer-facing display",
          targetId = "posBuyer",
        ),
      ),
    )

    val section = bindings.renderMultiDevicePromptSection(handoverToolAdvertised = true)

    assertThat(section).contains("`buyer`: Customer-facing display; target: posBuyer")
    assertThat(section).contains("`seller`: Merchant-facing POS; classifiers: android-tablet-primary; target: pos")
  }

  @Test
  fun `states the handover contract when switchDevice is advertised`() {
    val section = bindings().renderMultiDevicePromptSection(handoverToolAdvertised = true)

    assertThat(section).contains("switchDevice")
    assertThat(section).contains("`name:`")
  }

  @Test
  fun `omits the handover contract when switchDevice is not advertised`() {
    // A session can bind a cast and still not advertise the tool — a target's `excluded_tools:`
    // drops it. The roster is still worth rendering; a contract for a tool the model was never
    // offered is not.
    val section = bindings().renderMultiDevicePromptSection(handoverToolAdvertised = false)

    assertThat(section).contains("## Multi-device session")
    assertThat(section).contains("currently active device is `seller`")
    assertThat(section).doesNotContain("switchDevice")
  }

  @Test
  fun `re-render follows a switch without rebuilding the bindings`() {
    val bindings = bindings()
    assertThat(bindings.renderMultiDevicePromptSection(handoverToolAdvertised = true))
      .contains("currently active device is `seller`")

    bindings.switchTo("buyer")

    assertThat(bindings.renderMultiDevicePromptSection(handoverToolAdvertised = true))
      .contains("currently active device is `buyer`")
  }

  private fun bindings() = SessionDeviceBindings(
    linkedMapOf(
      "seller" to bound("emulator-5560", "android-tablet-primary", "Merchant-facing POS", "pos"),
      "buyer" to bound("emulator-5562", "android-tablet-secondary", "Customer-facing display", "posBuyer"),
    ),
  )

  private fun bound(instanceId: String, classifier: String, description: String, target: String) =
    SessionDeviceBindings.BoundDevice(
      trailblazeDeviceId = TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID),
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 1920,
        classifiers = listOf(TrailblazeDeviceClassifier(classifier)),
      ),
      description = description,
      targetId = target,
    )
}
