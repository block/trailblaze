package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    assertEquals(mapOf("android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION"), parsed.config.devices)
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
        "android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION",
        "android-tablet" to "ANDROID_ONDEVICE_INSTRUMENTATION",
        "ios" to "IOS_HOST",
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
    // Device classifiers must nest under `recording:`; a bare classifier key at the step level — or
    // any other unexpected key like the retired `verify:` — is rejected.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - step: hi
            verify: should not parse here
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("Unexpected step-level key") == true,
      "expected unexpected-step-key error, got: ${ex.message}",
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
  fun `unified missing top-level trail key is a parse error`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: y
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("non-empty top-level `trail:`") == true,
      "expected missing-trail error, got: ${ex.message}",
    )
  }

  @Test
  fun `an empty trail list is rejected — trail must be non-empty`() {
    // `trail:` is required AND non-empty: a trailhead-only / empty trail would run its bootstrap
    // and then pass with no real test steps (a vacuous always-pass).
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: y
        trail: []
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("non-empty top-level `trail:`") == true,
      "expected non-empty-trail error, got: ${ex.message}",
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
}
