package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Parser-shape tests for Trail YAML (unified format). These pin the singleton-mapping root, the
 * `step:` + `recording:`-grouped step shape (device classifiers nest under `recording:`, never at the
 * step level), and the parse-time validations documented in
 * `docs/devlog/2026-05-22-trail-yaml-unified-syntax.md`.
 */
class UnifiedTrailParserTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `minimal valid the unified format — single device, single step`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: myapp/login
        target: myapp
        devices:
          android-phone: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: Open the app
          recording:
            android-phone:
              - launchApp:
                  appId: com.example.myapp
      """.trimIndent(),
    )

    assertEquals("myapp/login", parsed.config.id)
    assertEquals("myapp", parsed.config.target)
    assertEquals(mapOf("android-phone" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION")), parsed.config.devices)
    assertEquals(1, parsed.trail.size)
    assertEquals("Open the app", parsed.trail[0].step)
    assertTrue(parsed.trail[0].recordable)
    assertEquals(setOf("android-phone"), parsed.trail[0].recordings.keys)
    assertEquals(1, parsed.trail[0].recordings.getValue("android-phone").size)
  }

  @Test
  fun `config parses per-classifier skip map and flat tags list`() {
    val src =
      """
      config:
        target: myapp
        tags: [smoke, flaky]
        skip:
          android: "blocked on #123"
          ios: "not implemented on iOS yet"
      trail:
        - step: Do the thing
          recording:
            android:
              - launchApp:
                  appId: com.example.myapp
      """.trimIndent()

    val parsed = yaml.decodeUnifiedTrail(src)
    assertEquals(listOf("smoke", "flaky"), parsed.config.tags)
    assertEquals(
      mapOf("android" to "blocked on #123", "ios" to "not implemented on iOS yet"),
      parsed.config.skip,
    )

    // Device-agnostic config extraction (the CLI pre-flight skip gate) lowers the per-classifier
    // skip map to a v1 scalar: skipped if any classifier declares a reason. Tags lower verbatim.
    val v1 = yaml.extractTrailConfig(src)
    assertEquals("blocked on #123", v1?.skip)
    assertEquals(listOf("smoke", "flaky"), v1?.tags)
  }

  @Test
  fun `full-shape the unified format — multi-device classifier hierarchy + recordable false + explicit empty`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: myapp/checkout
        target: myapp
        devices:
          android-phone: ANDROID_ONDEVICE_INSTRUMENTATION
          android-tablet: ANDROID_ONDEVICE_INSTRUMENTATION
          ios: IOS_HOST
        context: |-
          Test context goes here.
        memory:
          email: tb+test@example.com
        metadata:
          jira: PROJ-123
      trail:
        - step: Sign in to myapp
          recording:
            android:
              - launchApp:
                  appId: com.example.myapp
            ios:
              - launchApp:
                  appId: com.example.myapp.ios

        - step: LLM always handles this
          recordable: false

        - step: Skip on tablet
          recording:
            android-phone:
              - tap:
                  x: 100
                  y: 100
            android-tablet: []
            ios:
              - tap:
                  x: 200
                  y: 200
      """.trimIndent(),
    )

    assertEquals("myapp/checkout", parsed.config.id)
    assertEquals(
      mapOf(
        "android-phone" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION"),
        "android-tablet" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION"),
        "ios" to devicePin("IOS_HOST"),
      ),
      parsed.config.devices,
    )
    assertEquals(mapOf("email" to "tb+test@example.com"), parsed.config.memory)
    assertEquals(mapOf("jira" to "PROJ-123"), parsed.config.metadata)
    assertEquals(3, parsed.trail.size)

    val step0 = parsed.trail[0]
    assertEquals(setOf("android", "ios"), step0.recordings.keys)

    val step1 = parsed.trail[1]
    assertTrue(step1.recordings.isEmpty())
    assertEquals(false, step1.recordable)

    val step2 = parsed.trail[2]
    assertEquals(setOf("android-phone", "android-tablet", "ios"), step2.recordings.keys)
    // Explicit no-op preserves the key with an empty list.
    assertEquals(emptyList(), step2.recordings.getValue("android-tablet"))
  }

  @Test
  fun `a device classifier at the step level (not under recording) is a parse error`() {
    // Device classifiers must nest under `recording:`; a bare classifier key at the step level is
    // rejected.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - step: hi
            android-phone: []
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("Unexpected step-level key") == true,
      "expected unexpected-step-key error, got: ${ex.message}",
    )
  }

  @Test
  fun `a verify step parses with the NL text and the same optional keys as a step`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: x
        target: x
      trail:
        - step: Open the cart
        - verify: The cart shows 2 items
          recording:
            android-phone:
              - assertVisibleWithText:
                  text: 2 items
          maxRetries: 5
        - verify: The LLM checks the receipt banner
          recordable: false
      """.trimIndent(),
    )
    val direction = parsed.trail[0]
    assertTrue(!direction.verify, "a `step:` step must not be marked verify")
    val verify = parsed.trail[1]
    assertTrue(verify.verify, "a `verify:` step must be marked verify")
    assertEquals("The cart shows 2 items", verify.step, "verify NL lands in the shared step field")
    assertEquals(setOf("android-phone"), verify.recordings.keys)
    assertEquals(5, verify.maxRetries)
    val llmVerify = parsed.trail[2]
    assertTrue(llmVerify.verify && !llmVerify.recordable, "verify combines with recordable: false")
  }

  @Test
  fun `a step with both step and verify keys is rejected`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - step: hi
            verify: also hi
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("mutually exclusive") == true,
      "expected step/verify mutual-exclusion error, got: ${ex.message}",
    )
  }

  @Test
  fun `verify on the trailhead is rejected — a trailhead is a bootstrap, not an assertion`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trailhead:
          verify: signed in
          recording:
            android:
              launchApp:
                appId: com.example.myapp
        trail:
          - step: hi
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("trailhead does not support `verify:`") == true,
      "expected trailhead-verify rejection, got: ${ex.message}",
    )
  }

  @Test
  fun `a step missing its NL step is rejected — NL is required`() {
    // `step` (natural language) is required on every step; `recording` is optional.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - recordable: true
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("required `step:`") == true,
      "expected missing-step error, got: ${ex.message}",
    )
  }

  @Test
  fun `recordable false combined with non-empty recordings is rejected`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - step: hi
            recordable: false
            recording:
              android-phone:
                - tap:
                    x: 1
                    y: 2
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("mutually exclusive") == true,
      "expected mutually-exclusive error, got: ${ex.message}",
    )
  }

  @Test
  fun `a recording-only step (no NL) is rejected — NL is required`() {
    // We force NL: a step may not be recording-only. Every step must carry its intent.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - recording:
              android-phone:
                - tap:
                    x: 1
                    y: 2
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("required `step:`") == true,
      "expected missing-step error, got: ${ex.message}",
    )
  }

  @Test
  fun `unified with no config key decodes to an empty config`() {
    // `config:` is optional — every UnifiedTrailConfig field defaults, so an absent config decodes
    // to an empty config. `trail:` is the only required top-level key.
    val decoded = yaml.decodeUnifiedTrail(
      """
      trail:
        - step: hi
      """.trimIndent(),
    )
    assertEquals(UnifiedTrailConfig(), decoded.config)
    assertEquals(1, decoded.trail.size)
  }

  @Test
  fun `empty config is omitted on emit and round-trips`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(),
      trail = listOf(UnifiedTrailStep(step = "hi", recordings = mapOf("android-phone" to emptyList()))),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(!emitted.contains("config:"), "empty config should be omitted, got:\n$emitted")
    assertEquals(trail, yaml.decodeUnifiedTrail(emitted))
  }

  @Test
  fun `a config-only doc (config, no trail, no trailhead) decodes as a stepless metadata doc`() {
    // The ONE stepless shape that's allowed: a `config:` block with no `trail:` and no `trailhead:`.
    // It preserves case metadata (e.g. a test case with no runnable steps yet) and is not meant
    // to run as a test.
    val decoded = yaml.decodeUnifiedTrail(
      """
      config:
        id: x
        target: y
      """.trimIndent(),
    )
    assertEquals("x", decoded.config.id)
    assertTrue(decoded.trail.isEmpty(), "config-only doc has no trail steps")
    assertNull(decoded.trailhead)
  }

  @Test
  fun `a config-only doc with an explicit empty trail list decodes as stepless`() {
    val decoded = yaml.decodeUnifiedTrail(
      """
      config:
        id: x
        target: y
      trail: []
      """.trimIndent(),
    )
    assertEquals("x", decoded.config.id)
    assertTrue(decoded.trail.isEmpty())
  }

  @Test
  fun `a stepless doc with a fully-default config emits config braces and round-trips`() {
    // A blank test-case step lowers to zero steps with a default config. The
    // emitter must NOT produce an empty document for this — it anchors on a minimal `config: {}` so
    // the result decodes as a stepless config-only doc rather than failing to parse.
    val trail = UnifiedTrail(config = UnifiedTrailConfig(), trail = emptyList())
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(emitted.contains("config:"), "stepless default-config doc must anchor on config:, got:\n$emitted")
    assertTrue(emitted.isNotBlank(), "must not emit an empty document")
    val decoded = yaml.decodeUnifiedTrail(emitted)
    assertEquals(UnifiedTrailConfig(), decoded.config)
    assertTrue(decoded.trail.isEmpty())
    assertNull(decoded.trailhead)
  }

  @Test
  fun `a stepless doc with neither config nor trail is rejected`() {
    // Nothing to preserve and nothing to run — not a valid trail nor a config-only metadata doc.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail("trail: []")
    }
    assertTrue(
      ex.message?.contains("non-empty top-level `trail:`") == true,
      "expected missing-trail error, got: ${ex.message}",
    )
  }

  @Test
  fun `a trailhead-only doc (no trail steps) is rejected — a bootstrap alone is a vacuous pass`() {
    // A trailhead + no trail would run its bootstrap and then pass with no real test steps. Only a
    // pure config-only doc (no trailhead) is allowed to be stepless.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: y
        trailhead:
          step: launch the app
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("non-empty top-level `trail:`") == true,
      "expected non-empty-trail error, got: ${ex.message}",
    )
  }

  @Test
  fun `emitting a trailhead-only trail is rejected — stays symmetric with decode`() {
    // decode rejects a trailhead-only stepless doc (above); the emitter must reject the same shape,
    // otherwise it would produce config+trailhead YAML with no `trail:` that can no longer be
    // re-decoded — a silent round-trip break.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.encodeUnifiedTrailToString(
        UnifiedTrail(
          config = UnifiedTrailConfig(id = "x"),
          trailhead = UnifiedTrailStep(step = "launch the app"),
          trail = emptyList(),
        ),
      )
    }
    assertTrue(
      ex.message?.contains("trailhead-only") == true,
      "expected trailhead-only rejection, got: ${ex.message}",
    )
  }

  @Test
  fun `unified with unknown top-level key fails with a clear message`() {
    // IllegalArgumentException for "bad input" consistency with the other
    // parse validations (require / requireNotNull). The dispatcher catches
    // it as part of its v1-then-unified try/catch chain.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: y
        trail:
          - step: hi
        somethingExtra: nope
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("somethingExtra") == true,
      "expected unknown-key error, got: ${ex.message}",
    )
  }

  @Test
  fun `omitted config fields decode to null`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: x
        target: y
      trail:
        - step: hi
      """.trimIndent(),
    )
    assertNull(parsed.config.devices)
    assertNull(parsed.config.memory)
    assertNull(parsed.config.metadata)
    assertNull(parsed.config.context)
  }

  @Test
  fun `config memory coerces numeric and boolean YAML scalars to strings`() {
    // kaml's natural behavior: numeric/boolean YAML scalars decode to their string form
    // when the schema is Map<String, String>. Trail authors don't have to remember to
    // quote `accountTier: 5` — both quoted and unquoted variants round-trip to the same
    // `"5"` string. Pin the behavior so a future kaml/serialization upgrade that
    // tightens this can't silently regress user-facing YAML compatibility.
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: x
        target: y
        memory:
          accountTier: 5
          enabled: true
          quotedNum: "10"
      trail:
        - step: hi
      """.trimIndent(),
    )
    assertEquals(
      mapOf("accountTier" to "5", "enabled" to "true", "quotedNum" to "10"),
      parsed.config.memory,
    )
  }

  @Test
  fun `devices entries decode from the canonical object form`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: myapp/login
        target: myapp
        devices:
          android-phone:
            driver: ANDROID_ONDEVICE_INSTRUMENTATION
          web: { driver: PLAYWRIGHT_NATIVE }
          lab-a: {}
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )

    assertEquals(
      mapOf(
        "android-phone" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION"),
        "web" to devicePin("PLAYWRIGHT_NATIVE"),
        // `{}` declares the classifier without pinning a driver — inexpressible in the old
        // string form, and the reason the object form is canonical.
        "lab-a" to TrailblazeDeviceDefinition(driver = null),
      ),
      parsed.config.devices,
    )
  }

  @Test
  fun `a devices entry with no value declares the classifier and pins no driver`() {
    // `web:` with nothing after it is the shape a writer reaches for to declare a classifier
    // without a pin, and it must mean exactly what `{}` means rather than failing the parse.
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        target: myapp
        devices:
          web:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Do the thing
          recordable: false
      """.trimIndent(),
    )

    assertEquals(
      mapOf(
        "web" to TrailblazeDeviceDefinition(driver = null),
        "android" to devicePin("ANDROID_ONDEVICE_ACCESSIBILITY"),
      ),
      parsed.config.devices,
    )
  }

  @Test
  fun `a JSON devices entry with a null value pins no driver rather than a driver named null`() {
    // JsonNull is itself a JsonPrimitive, so the JSON branch has to rule it out before treating a
    // primitive value as a driver name — otherwise a driverless entry reports `unknown driver 'null'`.
    val config = TrailblazeJson.defaultWithoutToolsInstance.decodeFromString(
      UnifiedTrailConfig.serializer(),
      """{"target":"myapp","devices":{"web":null,"android":"ANDROID_ONDEVICE_ACCESSIBILITY"}}""",
    )

    assertEquals(
      mapOf(
        "web" to TrailblazeDeviceDefinition(driver = null),
        "android" to devicePin("ANDROID_ONDEVICE_ACCESSIBILITY"),
      ),
      config.devices,
    )
  }

  @Test
  fun `devices map mixing the legacy string form and the object form parses`() {
    // The bare-string value is DEPRECATED decode-only compatibility; during the
    // migration window a trail may carry both forms at once and must parse to the same model.
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        target: myapp
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
          ios:
            driver: IOS_HOST
      trail:
        - step: Do the thing
          recordable: false
      """.trimIndent(),
    )

    assertEquals(
      mapOf(
        "android" to devicePin("ANDROID_ONDEVICE_ACCESSIBILITY"),
        "ios" to devicePin("IOS_HOST"),
      ),
      parsed.config.devices,
    )
  }

  @Test
  fun `a legacy string devices entry naming an unknown driver fails loud`() {
    // Same fail-loud contract as the CLI's driver-string validation: a typo'd pin must never
    // silently run on the default driver.
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          target: myapp
          devices:
            android: ANDROID_ONDEVICE_ACESSIBILITY
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    val messages = generateSequence(failure as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString("\n")
    assertTrue(
      "unknown driver" in messages && "ANDROID_ONDEVICE_ACCESSIBILITY" in messages,
      "expected an unknown-driver error listing the valid driver types, got:\n$messages",
    )
  }

  @Test
  fun `an object-form devices entry naming an unknown driver fails loud with its classifier`() {
    // The typed exception must carry which devices: entry is bad, so per-device callers
    // (DesktopYamlRunner.trailPinnedDriverResolution) can leave another platform's typo to that
    // platform instead of failing this device's run.
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          target: myapp
          devices:
            android:
              driver: ANDROID_ONDEVICE_ACESSIBILITY
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    val unknownDriver = assertNotNull(
      generateSequence(failure as Throwable?) { it.cause }
        .filterIsInstance<UnknownDriverException>()
        .firstOrNull(),
      "expected an UnknownDriverException in the cause chain, got: $failure",
    )
    assertEquals("ANDROID_ONDEVICE_ACESSIBILITY", unknownDriver.driverName)
    assertEquals("android", unknownDriver.classifier)
  }

  @Test
  fun `an unknown driver failure still reports every cleanly-decoded devices entry`() {
    // The exception must describe the WHOLE devices map — every valid entry plus every bad one —
    // so a per-device caller can run its own closest-wins decision: a valid pin for the running
    // device must survive another platform's typo, regardless of entry order.
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          target: myapp
          devices:
            ios: IOS_TYPO_DRIVER
            android:
              driver: ANDROID_ONDEVICE_INSTRUMENTATION
            web: PLAYWRIGHT_TYPO
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    val unknownDriver = assertNotNull(
      generateSequence(failure as Throwable?) { it.cause }
        .filterIsInstance<UnknownDriverException>()
        .firstOrNull(),
      "expected an UnknownDriverException in the cause chain, got: $failure",
    )
    assertEquals(mapOf("ios" to "IOS_TYPO_DRIVER", "web" to "PLAYWRIGHT_TYPO"), unknownDriver.unknownDrivers)
    assertEquals(mapOf("android" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION")), unknownDriver.decodedDevices)
  }

  @Test
  fun `a multi-device configuration entry parses with its named devices in declaration order`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        target: myapp
        devices:
          pos-pair:
            description: Dual-display pair
            devices:
              seller:
                classifier: lab-a
                description: merchant-facing display
              buyer:
                classifier: lab-b
          android:
            driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Do the thing
          recordable: false
      """.trimIndent(),
    )

    val configuration = assertNotNull(parsed.config.devices?.get("pos-pair"))
    assertTrue(configuration.isConfiguration)
    assertEquals("Dual-display pair", configuration.description)
    // Declaration order is the contract: the FIRST named device is where the trail starts.
    assertEquals(listOf("seller", "buyer"), configuration.devices?.keys?.toList())
    assertEquals("lab-a", configuration.devices?.get("seller")?.classifier)
    assertEquals("merchant-facing display", configuration.devices?.get("seller")?.description)
    assertEquals("lab-b", configuration.devices?.get("buyer")?.classifier)
    // Single-device entries coexist with a configuration in the same map.
    assertEquals(devicePin("ANDROID_ONDEVICE_ACCESSIBILITY"), parsed.config.devices?.get("android"))
    assertEquals(setOf("pos-pair"), parsed.config.multiDeviceConfigurationNames)
  }

  @Test
  fun `a multi-device configuration entry round-trips through encode`() {
    val source = yaml.decodeUnifiedTrail(
      """
      config:
        target: myapp
        devices:
          pos-pair:
            description: Dual-display pair
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
          android:
            driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Do the thing
          recordable: false
      """.trimIndent(),
    )
    val reDecoded = yaml.decodeUnifiedTrail(yaml.encodeUnifiedTrailToString(source))
    assertEquals(source.config.devices, reDecoded.config.devices)
    // Declaration order (the start-device contract) survives the round-trip.
    assertEquals(
      listOf("seller", "buyer"),
      reDecoded.config.devices?.get("pos-pair")?.devices?.keys?.toList(),
    )
  }

  @Test
  fun `a configuration entry declaring its own driver is rejected`() {
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          devices:
            pos-pair:
              driver: ANDROID_ONDEVICE_ACCESSIBILITY
              devices:
                seller: { classifier: lab-a }
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    assertTrue(
      messageChain(failure).contains("cannot also declare"),
      "expected the configuration/driver contradiction message, got: $failure",
    )
  }

  @Test
  fun `a configuration with an empty devices map is rejected`() {
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          devices:
            pos-pair:
              devices: {}
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    assertTrue(
      messageChain(failure).contains("empty `devices:` map"),
      "expected the empty-configuration message, got: $failure",
    )
  }

  @Test
  fun `a configuration nested inside a configuration is rejected`() {
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          devices:
            pos-pair:
              devices:
                seller:
                  devices:
                    inner: { classifier: lab-a }
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    assertTrue(
      messageChain(failure).contains("configurations don't nest"),
      "expected the nesting message, got: $failure",
    )
  }

  @Test
  fun `a single-device entry whose classifier field contradicts its key is rejected`() {
    val failure = assertFailsWith<Exception> {
      yaml.decodeUnifiedTrail(
        """
        config:
          devices:
            android-phone:
              classifier: android-tablet
        trail:
          - step: Do the thing
            recordable: false
        """.trimIndent(),
      )
    }
    assertTrue(
      messageChain(failure).contains("map key IS its classifier"),
      "expected the key-is-classifier message, got: $failure",
    )
  }

  @Test
  fun `a single-device entry whose classifier field restates its key parses`() {
    // The key IS the classifier; restating it is redundant but not a contradiction.
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        devices:
          android-phone:
            classifier: android-phone
      trail:
        - step: Do the thing
          recordable: false
      """.trimIndent(),
    )
    assertEquals("android-phone", parsed.config.devices?.get("android-phone")?.classifier)
  }

  @Test
  fun `a per-device target parses as data`() {
    // `target:` is legal trail data from day one; the session runner (not the parser) rejects
    // it until per-device target plumbing lands.
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        devices:
          pos-pair:
            devices:
              seller: { classifier: lab-a, target: myapp }
      trail:
        - step: Do the thing
          recordable: false
      """.trimIndent(),
    )
    assertEquals("myapp", parsed.config.devices?.get("pos-pair")?.devices?.get("seller")?.target)
  }

  @Test
  fun `decodeUnifiedTrailConfig reads config from a trail whose steps would not decode`() {
    // The steps are malformed (a step must be a mapping): a full decodeUnifiedTrail throws, but
    // pre-run resolvers only need `config:` — the config-only decode must succeed so a
    // multi-device trail is never silently treated as single-device just because its steps have
    // a problem the run itself will report with full context.
    val doc =
      """
      config:
        target: myapp
        devices:
          pos-pair:
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
      trail:
        - 42
      """.trimIndent()
    assertFailsWith<Exception>("precondition: the full decode must reject the malformed step") {
      yaml.decodeUnifiedTrail(doc)
    }
    val config = yaml.decodeUnifiedTrailConfig(doc)
    assertEquals(setOf("pos-pair"), config.multiDeviceConfigurationNames)
  }

  @Test
  fun `decodeUnifiedTrailConfig fails loud on a malformed config block`() {
    assertFailsWith<Exception> {
      yaml.decodeUnifiedTrailConfig(
        """
        config:
          devices: "not a map"
        trail:
          - step: do the thing
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `decodeUnifiedTrailConfig returns an empty config when the document declares none`() {
    val config = yaml.decodeUnifiedTrailConfig(
      """
      trail:
        - step: do the thing
      """.trimIndent(),
    )
    assertEquals(UnifiedTrailConfig(), config)
  }

  private fun messageChain(e: Throwable): String =
    generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString("\n")
}

/** The canonical devices-map value for a driver pin, keeping test fixtures terse. */
private fun devicePin(driverName: String): TrailblazeDeviceDefinition =
  TrailblazeDeviceDefinition(driver = TrailblazeDriverType.fromString(driverName)!!)
