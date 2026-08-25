package xyz.block.trailblaze.host.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Behavioral contract for resolving a unified trail's multi-device configuration to concrete
 * devices. Every case here is a way an operator can misconfigure a run; each must fail loud with
 * the offending names, because the alternative is running the trail against the wrong device set
 * and reporting success.
 */
class MultiDeviceConfigurationResolverTest {

  private val launchDevice = TrailblazeDeviceId(
    instanceId = "emulator-5560",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private fun pairTrail(
    sellerClassifier: String = "android-tablet",
    buyerClassifier: String = "android-phone",
    extra: String = "",
  ) = """
    |config:
    |  devices:
    |    pos-pair:
    |      devices:
    |        seller:
    |          classifier: $sellerClassifier
    |        buyer:
    |          classifier: $buyerClassifier
    |$extra
    |trail:
    |  - prompt: "tap checkout"
    |
  """.trimMargin()

  /** [pairTrail] with a per-device `target:` on the buyer, the device that runs a different app. */
  private fun pairTrailWithBuyerTarget(targetId: String) = pairTrail().replace(
    "        buyer:\n          classifier: android-phone",
    "        buyer:\n          classifier: android-phone\n          target: $targetId",
  )

  private fun fakeTarget(id: String): TrailblazeHostAppTarget = object : TrailblazeHostAppTarget(
    id = id,
    displayName = "Target $id",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String> =
      listOf("com.example.$id")

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val singleDeviceTrail = """
    config:
      devices:
        android:
          driver: ANDROID_ONDEVICE_ACCESSIBILITY
    trail:
      - prompt: "tap checkout"
  """.trimIndent()

  @Test
  fun `first declared device is the start device and the rest bind from the env value`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = pairTrail(),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-5562",
    )!!

    assertThat(resolved.configurationName).isEqualTo("pos-pair")
    assertThat(resolved.startDeviceName).isEqualTo("seller")
    assertThat(resolved.companionDeviceIds.keys).isEqualTo(setOf("buyer"))
    assertThat(resolved.companionDeviceIds.getValue("buyer").instanceId).isEqualTo("emulator-5562")
    // `android-phone` folds to the ANDROID platform.
    assertThat(resolved.companionDeviceIds.getValue("buyer").trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.ANDROID)
  }

  @Test
  fun `a companion's platform comes from its classifier, not the launch device`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = pairTrail(buyerClassifier = "web"),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=playwright-native",
    )!!

    assertThat(resolved.companionDeviceIds.getValue("buyer").trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.WEB)
  }

  @Test
  fun `a companion's pinned driver decides its platform when the classifier carries none`() {
    val trail = """
      config:
        devices:
          web-phone:
            devices:
              phone:
                classifier: android-phone
              dashboard:
                driver: PLAYWRIGHT_NATIVE
      trail:
        - prompt: "tap checkout"
    """.trimIndent()

    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = trail,
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "dashboard=playwright-native",
    )!!

    assertThat(resolved.companionDeviceIds.getValue("dashboard").trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.WEB)
  }

  @Test
  fun `an internal device-family classifier falls back to the launch device's platform`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = pairTrail(sellerClassifier = "lab-a", buyerClassifier = "lab-b"),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-5562",
    )!!

    assertThat(resolved.companionDeviceIds.getValue("buyer").trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.ANDROID)
  }

  @Test
  fun `a pinned driver that contradicts the classifier's platform is rejected`() {
    val trail = pairTrail().replace(
      "        buyer:\n          classifier: android-phone",
      "        buyer:\n          classifier: android-phone\n          driver: PLAYWRIGHT_NATIVE",
    )

    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(trail, launchDevice, "buyer=emulator-5562")
    }.message.orEmpty()

    assertThat(message).contains("PLAYWRIGHT_NATIVE")
    assertThat(message).contains("android-phone")
  }

  @Test
  fun `single-device and legacy trails resolve to no configuration`() {
    assertThat(
      MultiDeviceConfigurationResolver.resolve(
        yaml = singleDeviceTrail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
      ),
    ).isNull()
    assertThat(MultiDeviceConfigurationResolver.declaredConfigurationNames(singleDeviceTrail)).isEmpty()

    val v1Trail = """
      - prompt: "tap checkout"
    """.trimIndent()
    assertThat(
      MultiDeviceConfigurationResolver.resolve(
        yaml = v1Trail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562",
      ),
    ).isNull()
    assertThat(MultiDeviceConfigurationResolver.declaredConfigurationNames(v1Trail)).isEmpty()
  }

  @Test
  fun `declared configuration names are reported for the dispatch-path check`() {
    assertThat(MultiDeviceConfigurationResolver.declaredConfigurationNames(pairTrail()))
      .isEqualTo(setOf("pos-pair"))
  }

  @Test
  fun `an undecodable config on a unified trail fails loud instead of downgrading to one device`() {
    val malformed = """
      config:
        devices: "not a map"
      trail:
        - prompt: "tap checkout"
    """.trimIndent()

    // Both entry points must throw: a swallowed decode here would run a multi-device trail on the
    // launch device alone, since neither the dispatch check nor the session-start guard would see
    // a configuration.
    assertThat(
      assertFailsWith<TrailblazeException> {
        MultiDeviceConfigurationResolver.resolve(malformed, launchDevice, null)
      }.message.orEmpty(),
    ).contains("Failed to decode")
    assertThat(
      assertFailsWith<TrailblazeException> {
        MultiDeviceConfigurationResolver.declaredConfigurationNames(malformed)
      }.message.orEmpty(),
    ).contains("Failed to decode")
  }

  @Test
  fun `an unbound companion names the env var it needs`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(pairTrail(), launchDevice, rawDeviceBindings = null)
    }.message.orEmpty()

    assertThat(message).contains("declares device 'buyer'")
    assertThat(message).contains("TRAILBLAZE_DEVICE_BINDINGS=\"buyer=<deviceInstanceId>\"")
  }

  @Test
  fun `a misspelled binding name is rejected rather than silently ignored`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(pairTrail(), launchDevice, "byer=emulator-5562")
    }.message.orEmpty()

    assertThat(message).contains("byer")
  }

  @Test
  fun `binding the start device name is rejected because the launch device always wins`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = pairTrail(),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "seller=emulator-5570,buyer=emulator-5562",
      )
    }.message.orEmpty()

    assertThat(message).contains("START device")
    assertThat(message).contains("emulator-5570")
  }

  @Test
  fun `binding a companion to the launch device is rejected`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(pairTrail(), launchDevice, "buyer=emulator-5560")
    }.message.orEmpty()

    assertThat(message).contains("already the session's start device")
  }

  @Test
  fun `binding two names to one device is rejected`() {
    val threeDeviceTrail = """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: android-tablet
              buyer:
                classifier: android-phone
              observer:
                classifier: android-phone
      trail:
        - prompt: "tap checkout"
    """.trimIndent()

    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = threeDeviceTrail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562,observer=emulator-5562",
      )
    }.message.orEmpty()

    assertThat(message).contains("multiple names")
  }

  @Test
  fun `repeating a name in the env value is rejected instead of last-wins`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.parseDeviceBindings("buyer=emulator-5562,buyer=emulator-5564")
    }.message.orEmpty()

    assertThat(message).contains("same name more than once")
  }

  @Test
  fun `blank and malformed binding entries are ignored so trailing commas are harmless`() {
    assertThat(MultiDeviceConfigurationResolver.parseDeviceBindings("buyer=emulator-5562, ,noequals,"))
      .isEqualTo(mapOf("buyer" to "emulator-5562"))
    assertThat(MultiDeviceConfigurationResolver.parseDeviceBindings(null)).isEmpty()
  }

  @Test
  fun `per-device targets are reported per declared name, null where the device declares none`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = pairTrailWithBuyerTarget("buyer-app"),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-5562",
    )!!

    // The start device is included so a caller can resolve every device's target uniformly;
    // a device that declares no override reads null and inherits the session target.
    assertThat(resolved.memberTargetIds).isEqualTo(mapOf("seller" to null, "buyer" to "buyer-app"))
  }

  @Test
  fun `a device with no target override runs the session target`() {
    val sessionTarget = fakeTarget("seller-app")

    val effective = MultiDeviceConfigurationResolver.resolveMemberTargets(
      configurationName = "pos-pair",
      memberTargetIds = mapOf("seller" to null, "buyer" to null),
      sessionTarget = sessionTarget,
      findTargetById = { fail("no lookup should be needed when nothing overrides") },
    )

    assertThat(effective).isEqualTo(mapOf("seller" to sessionTarget, "buyer" to sessionTarget))
  }

  @Test
  fun `a device with a target override runs that target, not the session target`() {
    val sessionTarget = fakeTarget("seller-app")
    val buyerTarget = fakeTarget("buyer-app")

    val effective = MultiDeviceConfigurationResolver.resolveMemberTargets(
      configurationName = "pos-pair",
      memberTargetIds = mapOf("seller" to null, "buyer" to "buyer-app"),
      sessionTarget = sessionTarget,
      findTargetById = { id -> if (id == "buyer-app") buyerTarget else null },
    )

    assertThat(effective).isEqualTo(mapOf("seller" to sessionTarget, "buyer" to buyerTarget))
  }

  @Test
  fun `an override on the start device wins for the start device`() {
    val sessionTarget = fakeTarget("seller-app")
    val kioskTarget = fakeTarget("kiosk-app")

    val effective = MultiDeviceConfigurationResolver.resolveMemberTargets(
      configurationName = "pos-pair",
      memberTargetIds = mapOf("seller" to "kiosk-app", "buyer" to null),
      sessionTarget = sessionTarget,
      findTargetById = { id -> if (id == "kiosk-app") kioskTarget else null },
    )

    assertThat(effective).isEqualTo(mapOf("seller" to kioskTarget, "buyer" to sessionTarget))
  }

  @Test
  fun `an unknown per-device target fails the run instead of falling back to the session target`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolveMemberTargets(
        configurationName = "pos-pair",
        memberTargetIds = mapOf("seller" to null, "buyer" to "buyer-app"),
        sessionTarget = fakeTarget("seller-app"),
        findTargetById = { null },
      )
    }.message.orEmpty()

    // Names the device, the configuration and the id, because silently running the session target
    // on that device would launch the wrong app and still report the session as successful.
    assertThat(message).contains("buyer")
    assertThat(message).contains("pos-pair")
    assertThat(message).contains("buyer-app")
    assertThat(message).contains("not registered")
  }

  @Test
  fun `a per-device target on a path that cannot resolve targets fails instead of being ignored`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolveMemberTargets(
        configurationName = "pos-pair",
        memberTargetIds = mapOf("seller" to null, "buyer" to "buyer-app"),
        sessionTarget = fakeTarget("seller-app"),
        findTargetById = null,
      )
    }.message.orEmpty()

    assertThat(message).contains("cannot resolve per-device targets")
  }

  @Test
  fun `two configurations are rejected until explicit selection exists`() {
    val twoConfigurations = """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: android-tablet
              buyer:
                classifier: android-phone
          kiosk-pair:
            devices:
              a:
                classifier: android
              b:
                classifier: android
      trail:
        - prompt: "tap checkout"
    """.trimIndent()

    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(twoConfigurations, launchDevice, "buyer=emulator-5562")
    }.message.orEmpty()

    assertThat(message).contains("2 device configurations")
  }

  /**
   * The force-stop step runs before the configuration is resolved, so it reads the start device's
   * override through this. Without it a clean-start run stops the session default — leaving the app
   * actually under test running and killing an unrelated one.
   */
  @Test
  fun `the start device's target override is readable before the configuration resolves`() {
    val startOverride = pairTrail().replace(
      "        seller:\n          classifier: android-tablet",
      "        seller:\n          classifier: android-tablet\n          target: kiosk-app",
    )

    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId(startOverride))
      .isEqualTo("kiosk-app")
  }

  /** Only the START device's override counts here — a companion's app isn't on the launch device. */
  @Test
  fun `a companion-only target override leaves the start device on the session target`() {
    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId(pairTrailWithBuyerTarget("buyer-app")))
      .isNull()
  }

  /**
   * Total by design: this runs during app lifecycle, before the trail's shape has been validated.
   * [MultiDeviceConfigurationResolver.resolve] reports both of these properly moments later, with a
   * message about trail shape rather than about force-stopping an app.
   */
  @Test
  fun `unparseable and single-device trails report no start-device override instead of throwing`() {
    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId(singleDeviceTrail)).isNull()
    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId("config:\n  devices: [")).isNull()
    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId("")).isNull()
  }
}
