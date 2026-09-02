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

  /**
   * Forwards to the object under test with `sessionDriverType` defaulted to null.
   *
   * The production signature deliberately has NO default — a new runner path must state the driver
   * it resolved rather than inherit a stance by omission. Null is the strictest stance (it honors
   * no driver-dependent pin), which is what every case that isn't about the driver rule wants.
   */
  private fun resolve(
    yaml: String,
    primaryDeviceId: TrailblazeDeviceId,
    rawDeviceBindings: String?,
    sessionDriverType: TrailblazeDriverType? = null,
  ) = MultiDeviceConfigurationResolver.resolve(
    yaml = yaml,
    primaryDeviceId = primaryDeviceId,
    rawDeviceBindings = rawDeviceBindings,
    sessionDriverType = sessionDriverType,
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
    val resolved = resolve(
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
    val resolved = resolve(
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

    val resolved = resolve(
      yaml = trail,
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "dashboard=playwright-native",
    )!!

    assertThat(resolved.companionDeviceIds.getValue("dashboard").trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.WEB)
  }

  @Test
  fun `an internal device-family classifier falls back to the launch device's platform`() {
    val resolved = resolve(
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
      resolve(trail, launchDevice, "buyer=emulator-5562")
    }.message.orEmpty()

    assertThat(message).contains("PLAYWRIGHT_NATIVE")
    assertThat(message).contains("android-phone")
  }

  // ---------------------------------------------------------------------------
  // Per-device `driver:` — honored when it restates what the device actually runs, rejected loud
  // otherwise. A session picks ONE driver for its whole cast, so a differing pin can only be
  // ignored, and an ignored pin replays the trail on the other driver's selectors while reporting
  // success.
  // ---------------------------------------------------------------------------

  /** [pairTrail] with a `driver:` pinned on the buyer, the companion device. */
  private fun pairTrailWithBuyerDriver(driver: String) = pairTrail().replace(
    "        buyer:\n          classifier: android-phone",
    "        buyer:\n          classifier: android-phone\n          driver: $driver",
  )

  @Test
  fun `a member driver the session will not run is rejected, naming both drivers`() {
    val message = assertFailsWith<TrailblazeException> {
      resolve(
        yaml = pairTrailWithBuyerDriver("ANDROID_ONDEVICE_ACCESSIBILITY"),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562",
        sessionDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      )
    }.message.orEmpty()

    assertThat(message).contains("buyer")
    assertThat(message).contains("ANDROID_ONDEVICE_ACCESSIBILITY")
    assertThat(message).contains("ANDROID_ONDEVICE_INSTRUMENTATION")
  }

  @Test
  fun `a member driver the session already runs is accepted`() {
    val resolved = resolve(
      yaml = pairTrailWithBuyerDriver("ANDROID_ONDEVICE_ACCESSIBILITY"),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-5562",
      sessionDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )!!

    assertThat(resolved.companionDeviceIds.keys).isEqualTo(setOf("buyer"))
  }

  @Test
  fun `a WEB companion's Playwright pin is accepted whatever driver the session resolved`() {
    // A web companion is a host-owned Playwright browser, not a device the session's Android
    // driver reaches — so PLAYWRIGHT_NATIVE on it IS what runs, and the committed web-phone demo
    // trail declares exactly that.
    val resolved = resolve(
      yaml = pairTrail(buyerClassifier = "web").replace(
        "        buyer:\n          classifier: web",
        "        buyer:\n          classifier: web\n          driver: PLAYWRIGHT_NATIVE",
      ),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=web-dashboard",
      sessionDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
    )!!

    assertThat(resolved.companionDeviceIds.getValue("buyer").trailblazeDevicePlatform)
      .isEqualTo(TrailblazeDevicePlatform.WEB)
  }

  @Test
  fun `a WEB START member's Playwright pin is rejected — no browser is built for the launch device`() {
    // Same pin, same platform, opposite verdict from the companion case above: the start device is
    // the launch device, so it runs the session's own driver and no Playwright browser is ever
    // built for it. Exempting every WEB member would let this pin through unhonored.
    val message = assertFailsWith<TrailblazeException> {
      resolve(
        yaml = pairTrail(sellerClassifier = "web").replace(
          "        seller:\n          classifier: web",
          "        seller:\n          classifier: web\n          driver: PLAYWRIGHT_NATIVE",
        ),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562",
        sessionDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      )
    }.message.orEmpty()

    assertThat(message).contains("seller")
    assertThat(message).contains("PLAYWRIGHT_NATIVE")
    assertThat(message).contains("ANDROID_ONDEVICE_INSTRUMENTATION")
  }

  @Test
  fun `the START device's driver pin is rejected too`() {
    // The start device is the launch device, whose driver the session resolved from --driver / the
    // classifier-keyed pin / the app setting. A configuration member is invisible to all three, so
    // its pin reaches nothing.
    val message = assertFailsWith<TrailblazeException> {
      resolve(
        yaml = pairTrail().replace(
          "        seller:\n          classifier: android-tablet",
          "        seller:\n          classifier: android-tablet\n          driver: ANDROID_ONDEVICE_ACCESSIBILITY",
        ),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562",
        sessionDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      )
    }.message.orEmpty()

    assertThat(message).contains("seller")
  }

  @Test
  fun `a caller with no resolved session driver rejects every per-device pin`() {
    // The permissive reading of "no session driver" would be to skip the check, which is exactly
    // how the pin got silently ignored in the first place.
    val message = assertFailsWith<TrailblazeException> {
      resolve(
        yaml = pairTrailWithBuyerDriver("ANDROID_ONDEVICE_ACCESSIBILITY"),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562",
      )
    }.message.orEmpty()

    assertThat(message).contains("ANDROID_ONDEVICE_ACCESSIBILITY")
  }

  @Test
  fun `single-device and legacy trails resolve to no configuration`() {
    assertThat(
      resolve(
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
      resolve(
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
        resolve(malformed, launchDevice, null)
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
      resolve(pairTrail(), launchDevice, rawDeviceBindings = null)
    }.message.orEmpty()

    assertThat(message).contains("declares device 'buyer'")
    assertThat(message).contains("TRAILBLAZE_DEVICE_BINDINGS=\"buyer=<deviceInstanceId>\"")
  }

  @Test
  fun `a misspelled binding name is rejected rather than silently ignored`() {
    val message = assertFailsWith<TrailblazeException> {
      resolve(pairTrail(), launchDevice, "byer=emulator-5562")
    }.message.orEmpty()

    assertThat(message).contains("byer")
  }

  @Test
  fun `binding the start device name is rejected because the launch device always wins`() {
    val message = assertFailsWith<TrailblazeException> {
      resolve(
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
      resolve(pairTrail(), launchDevice, "buyer=emulator-5560")
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
      resolve(
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
    val resolved = resolve(
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

  /**
   * Two configurations of the same journey — a paired-display shape and a phone+browser shape.
   * `pos-pair` is declared FIRST, so every case selecting `web-phone` also proves the selection is
   * honored rather than the first-declared entry being taken.
   */
  private val twoConfigurations = """
    config:
      devices:
        pos-pair:
          devices:
            seller:
              classifier: android-tablet
              target: pos-app
            buyer:
              classifier: android-phone
        web-phone:
          devices:
            phone:
              classifier: android-phone
              target: phone-app
            dashboard:
              classifier: web
    trail:
      - prompt: "tap checkout"
  """.trimIndent()

  @Test
  fun `an unselected two-configuration trail names both selection mechanisms`() {
    val message = assertFailsWith<TrailblazeException> {
      resolve(twoConfigurations, launchDevice, "buyer=emulator-5562")
    }.message.orEmpty()

    assertThat(message).contains("2 device configurations")
    assertThat(message).contains("RunYamlRequest.deviceConfiguration")
    assertThat(message).contains("TRAILBLAZE_DEVICE_CONFIGURATION")
  }

  @Test
  fun `a request selection binds the named configuration, not the first declared one`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = twoConfigurations,
      primaryDeviceId = launchDevice,
      rawDeviceBindings = null,
      requestDeviceBindings = mapOf("dashboard" to "playwright-native"),
      requestConfigurationName = "web-phone",
      sessionDriverType = null,
    )!!

    assertThat(resolved.configurationName).isEqualTo("web-phone")
    assertThat(resolved.startDeviceName).isEqualTo("phone")
    assertThat(resolved.companionDeviceIds.keys).isEqualTo(setOf("dashboard"))
    assertThat(resolved.memberTargetIds).isEqualTo(mapOf("phone" to "phone-app", "dashboard" to null))
  }

  @Test
  fun `the env var selects a configuration when the request names none`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = twoConfigurations,
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "dashboard=playwright-native",
      environmentConfigurationName = "web-phone",
      sessionDriverType = null,
    )!!

    assertThat(resolved.configurationName).isEqualTo("web-phone")
  }

  /**
   * The whole point of the request field: one daemon lifetime, whose env var was snapshotted at
   * launch, binding a different configuration for this trail.
   */
  @Test
  fun `the request selection wins over the env var`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = twoConfigurations,
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-5562",
      requestConfigurationName = "pos-pair",
      environmentConfigurationName = "web-phone",
      sessionDriverType = null,
    )!!

    assertThat(resolved.configurationName).isEqualTo("pos-pair")
    assertThat(resolved.startDeviceName).isEqualTo("seller")
  }

  @Test
  fun `a selection naming an undeclared configuration is rejected by the source that named it`() {
    val fromRequest = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = twoConfigurations,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        requestConfigurationName = "web-tablet",
        sessionDriverType = null,
      )
    }.message.orEmpty()
    assertThat(fromRequest).contains("RunYamlRequest.deviceConfiguration")
    assertThat(fromRequest).contains("'web-tablet'")
    assertThat(fromRequest).contains("pos-pair")

    val fromEnvironment = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = twoConfigurations,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        environmentConfigurationName = "web-tablet",
        sessionDriverType = null,
      )
    }.message.orEmpty()
    assertThat(fromEnvironment).contains("TRAILBLAZE_DEVICE_CONFIGURATION")
  }

  /**
   * The two selection sources diverge here on purpose. A request field is trail-specific, so naming
   * a configuration this trail lacks means the caller would silently get a single-device run. The
   * env var is a daemon-wide default, and the same daemon serves ordinary single-device trails —
   * failing those would make the variable unusable.
   */
  @Test
  fun `a request selection on a trail with no configuration is rejected while the env var is ignored`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = singleDeviceTrail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        requestConfigurationName = "pos-pair",
        sessionDriverType = null,
      )
    }.message.orEmpty()
    assertThat(message).contains("declares no device configuration")

    assertThat(
      MultiDeviceConfigurationResolver.resolve(
        yaml = singleDeviceTrail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        environmentConfigurationName = "pos-pair",
        sessionDriverType = null,
      ),
    ).isNull()
  }

  /**
   * A v1 trail (bare prompt list, no `config:`) declares no configuration just as surely as a
   * unified single-device trail does, so it gets the same answer. The trail shape is not a reason to
   * drop a caller's selection unreported — that IS the silent single-device run.
   */
  @Test
  fun `a request selection on a legacy v1 trail is rejected, not dropped with the trail shape`() {
    val v1Trail = """
      - prompt: "tap checkout"
    """.trimIndent()

    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = v1Trail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        requestConfigurationName = "pos-pair",
        sessionDriverType = null,
      )
    }.message.orEmpty()
    assertThat(message).contains("declares no device configuration")

    assertThat(
      MultiDeviceConfigurationResolver.resolve(
        yaml = v1Trail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        environmentConfigurationName = "pos-pair",
        sessionDriverType = null,
      ),
    ).isNull()
  }

  /**
   * Bindings follow the selection field: a caller that bound companions for THIS trail and got a
   * single-device run needs to hear about it. The env value stays lenient for the daemon-wide
   * reason — it is set once and outlives every trail the daemon serves.
   */
  @Test
  fun `request bindings on a trail with no configuration are rejected while the env value is ignored`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = singleDeviceTrail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        requestDeviceBindings = mapOf("buyer" to "emulator-5562"),
        sessionDriverType = null,
      )
    }.message.orEmpty()
    assertThat(message).contains("RunYamlRequest.deviceBindings")
    assertThat(message).contains("buyer")
    assertThat(message).contains("declares no device configuration")

    assertThat(
      MultiDeviceConfigurationResolver.resolve(
        yaml = singleDeviceTrail,
        primaryDeviceId = launchDevice,
        rawDeviceBindings = "buyer=emulator-5562",
        sessionDriverType = null,
      ),
    ).isNull()
  }

  /**
   * The ladder has one home. `startDeviceTargetId`'s caller needs the effective selection before a
   * full `resolve`, and a second copy of the rule there drifts silently — it force-stops the wrong
   * app on the launch device.
   */
  @Test
  fun `the effective selection prefers the request field and treats blank as absent`() {
    fun effective(request: String?, environment: String?) =
      MultiDeviceConfigurationResolver.effectiveConfigurationName(
        requestConfigurationName = request,
        environmentConfigurationName = environment,
      )

    assertThat(effective("web-phone", "pos-pair")).isEqualTo("web-phone")
    assertThat(effective(null, "pos-pair")).isEqualTo("pos-pair")
    assertThat(effective("  ", "pos-pair")).isEqualTo("pos-pair")
    assertThat(effective(null, "  ")).isNull()
    assertThat(effective(null, null)).isNull()
  }

  /**
   * Whole-map replacement, not a merge. The daemon in this case was launched for the `pos-pair`
   * lane, so its env var still binds `buyer`; the caller now runs the `web-phone` configuration and
   * binds `dashboard`. A merge would carry `buyer` into a device set that doesn't declare it and
   * fail the run on a binding nobody asked for.
   */
  @Test
  fun `request bindings replace a stale env value instead of merging with it`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = twoConfigurations,
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-9999",
      requestDeviceBindings = mapOf("dashboard" to "playwright-native"),
      requestConfigurationName = "web-phone",
      sessionDriverType = null,
    )!!

    assertThat(resolved.companionDeviceIds.keys).isEqualTo(setOf("dashboard"))
  }

  /** And on the same name, the request's device is the one bound — not the daemon-wide one. */
  @Test
  fun `a request binding overrides the env binding for the same name`() {
    val resolved = MultiDeviceConfigurationResolver.resolve(
      yaml = pairTrail(),
      primaryDeviceId = launchDevice,
      rawDeviceBindings = "buyer=emulator-9999",
      requestDeviceBindings = mapOf("buyer" to "emulator-5562"),
      sessionDriverType = null,
    )!!

    assertThat(resolved.companionDeviceIds.getValue("buyer").instanceId).isEqualTo("emulator-5562")
  }

  /**
   * The 2x2 contract: two pairs running the SAME trail at once. Both casts name `buyer`, which the
   * daemon-wide env var structurally cannot express (one `TRAILBLAZE_DEVICE_BINDINGS` value, one
   * `buyer`), and both must resolve to their own devices. Per-request bindings are what make that
   * possible, so this pins that resolution carries no state between calls.
   */
  @Test
  fun `two concurrent runs bind the same device name to different devices`() {
    fun resolvePair(startDevice: String, buyerDevice: String) =
      MultiDeviceConfigurationResolver.resolve(
        yaml = pairTrail(),
        primaryDeviceId = TrailblazeDeviceId(
          instanceId = startDevice,
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        rawDeviceBindings = null,
        requestDeviceBindings = mapOf("buyer" to buyerDevice),
        sessionDriverType = null,
      )!!

    val pairA = resolvePair(startDevice = "emulator-5560", buyerDevice = "emulator-5562")
    val pairB = resolvePair(startDevice = "emulator-5564", buyerDevice = "emulator-5566")

    assertThat(pairA.companionDeviceIds.getValue("buyer").instanceId).isEqualTo("emulator-5562")
    assertThat(pairB.companionDeviceIds.getValue("buyer").instanceId).isEqualTo("emulator-5566")
    // Re-read pair A after pair B resolved: a resolver that cached bindings anywhere would now
    // report B's device for A's `buyer`.
    assertThat(pairA.companionDeviceIds.getValue("buyer").instanceId).isEqualTo("emulator-5562")
  }

  /** Telling a caller to fix an env var they never set is a wrong instruction. */
  @Test
  fun `a misspelled request binding names the request field, not the env var`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = pairTrail(),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        requestDeviceBindings = mapOf("byer" to "emulator-5562"),
        sessionDriverType = null,
      )
    }.message.orEmpty()

    assertThat(message).contains("RunYamlRequest.deviceBindings")
    assertThat(message).contains("byer")
  }

  /**
   * A map literal can carry what the env-var parser drops for free. An empty instance id would
   * otherwise reach a connect call as an empty serial.
   */
  @Test
  fun `a blank name or device id in request bindings is rejected`() {
    val message = assertFailsWith<TrailblazeException> {
      MultiDeviceConfigurationResolver.resolve(
        yaml = pairTrail(),
        primaryDeviceId = launchDevice,
        rawDeviceBindings = null,
        requestDeviceBindings = mapOf("buyer" to ""),
        sessionDriverType = null,
      )
    }.message.orEmpty()

    assertThat(message).contains("blank name or device id")
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

  /**
   * Each configuration in [twoConfigurations] overrides its start device's `target:` with a
   * different app. Without the selection this reads neither, and a clean-start run force-stops the
   * session default — killing an unrelated app and leaving the app under test running, which is the
   * exact defect [MultiDeviceConfigurationResolver.startDeviceTargetId] exists to prevent.
   */
  @Test
  fun `the start-device override follows the selected configuration`() {
    assertThat(
      MultiDeviceConfigurationResolver.startDeviceTargetId(twoConfigurations, "web-phone"),
    ).isEqualTo("phone-app")
    assertThat(
      MultiDeviceConfigurationResolver.startDeviceTargetId(twoConfigurations, "pos-pair"),
    ).isEqualTo("pos-app")
  }

  /** Total, as above: `resolve` reports both of these with a message about trail shape. */
  @Test
  fun `an unselected or unmatched multi-configuration trail reports no start-device override`() {
    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId(twoConfigurations)).isNull()
    assertThat(MultiDeviceConfigurationResolver.startDeviceTargetId(twoConfigurations, "web-tablet"))
      .isNull()
  }
}
