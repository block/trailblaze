package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Parser-shape tests for Trail YAML (unified format). These pin the singleton-mapping root,
 * the `step:` + dynamic-classifier-key step shape, and the parse-time validations
 * documented in `docs/devlog/2026-05-22-trail-yaml-unified-syntax.md`.
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
          - android-phone
      trail:
        - step: Open the app
          android-phone:
            - launchApp:
                appId: com.example.myapp
      """.trimIndent(),
    )

    assertEquals("myapp/login", parsed.config.id)
    assertEquals("myapp", parsed.config.target)
    assertEquals(listOf("android-phone"), parsed.config.devices)
    assertEquals(1, parsed.trail.size)
    assertEquals("Open the app", parsed.trail[0].step)
    assertTrue(parsed.trail[0].recordable)
    assertEquals(setOf("android-phone"), parsed.trail[0].recordings.keys)
    assertEquals(1, parsed.trail[0].recordings.getValue("android-phone").size)
  }

  @Test
  fun `full-shape the unified format — multi-device classifier hierarchy + recordable false + explicit empty`() {
    val parsed = yaml.decodeUnifiedTrail(
      """
      config:
        id: myapp/checkout
        target: myapp
        devices:
          - android-phone
          - android-tablet
          - ios
        context: |-
          Test context goes here.
        memory:
          email: tb+test@example.com
        metadata:
          jira: PROJ-123
      trail:
        - step: Sign in to myapp
          android:
            - launchApp:
                appId: com.example.myapp
          ios:
            - launchApp:
                appId: com.example.myapp.ios

        - step: LLM always handles this
          recordable: false

        - step: Skip on tablet
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
    assertEquals(listOf("android-phone", "android-tablet", "ios"), parsed.config.devices)
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
  fun `unknown reserved key at step level is a parse error`() {
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
      ex.message?.contains("reserved by the schema") == true,
      "expected reserved-key error, got: ${ex.message}",
    )
  }

  @Test
  fun `missing step key on a step entry fails clearly`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - android-phone: []
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("missing required `step:`") == true,
      "expected missing-step error, got: ${ex.message}",
    )
  }

  @Test
  fun `recordable false combined with non-empty classifier recordings is rejected`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          id: x
          target: x
        trail:
          - step: hi
            recordable: false
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
  fun `unified missing top-level config key is a parse error`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        trail:
          - step: hi
            android-phone: []
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("missing required top-level `config:` key") == true,
      "expected missing-config error, got: ${ex.message}",
    )
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
      ex.message?.contains("missing required top-level `trail:` key") == true,
      "expected missing-trail error, got: ${ex.message}",
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
            android-phone: []
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
          android-phone: []
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
          android-phone: []
      """.trimIndent(),
    )
    assertEquals(
      mapOf("accountTier" to "5", "enabled" to "true", "quotedNum" to "10"),
      parsed.config.memory,
    )
  }
}
