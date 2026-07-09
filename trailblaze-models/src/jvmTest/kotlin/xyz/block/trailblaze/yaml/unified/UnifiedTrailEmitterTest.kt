package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Pins the [UnifiedTrailEmitter]'s output shape so future "fix" changes to the
 * encoder can't silently drift the on-disk format. The migrator depends on the
 * exact emit shape for diffability (e.g., classifier keys insertion-ordered,
 * `recordable: false` rendered as a sibling line, explicit `[]` for no-op).
 */
class UnifiedTrailEmitterTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `single-line step NL emits as a quoted scalar — safe against ambiguous YAML`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Tap the Sign In button",
          recordings = mapOf("android-phone" to listOf(noopTool())),
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(
      "- step: \"Tap the Sign In button\"" in emitted,
      "expected quoted single-line step, got:\n$emitted",
    )
  }

  @Test
  fun `multi-line step NL emits as a YAML literal block`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Line one\nLine two\nLine three",
          recordings = mapOf("android-phone" to listOf(noopTool())),
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(
      "- step: |" in emitted,
      "expected literal-block marker `- step: |`, got:\n$emitted",
    )
    assertTrue(
      "      Line one" in emitted && "      Line two" in emitted,
      "expected each line indented under the step, got:\n$emitted",
    )
  }

  @Test
  fun `recordable false emits as a sibling line under the step entry`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "LLM-only step", recordings = emptyMap(), recordable = false),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(
      "    recordable: false" in emitted,
      "expected `recordable: false` indented to 4 spaces, got:\n$emitted",
    )
  }

  @Test
  fun `explicit empty classifier list emits as inline empty sequence`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Skip on tablet",
          recordings = linkedMapOf(
            "android-phone" to listOf(noopTool()),
            "android-tablet" to emptyList(),
          ),
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(
      "android-tablet: []" in emitted,
      "expected `android-tablet: []` (inline empty list), got:\n$emitted",
    )
  }

  @Test
  fun `leading comments emit at the top before the config block`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Step", recordings = mapOf("android-phone" to listOf(noopTool()))),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(
      trail,
      leadingComments = listOf("WARNING: drift detected", "  step 3: ..."),
    )
    val lines = emitted.lines()
    assertEquals("# WARNING: drift detected", lines[0])
    assertEquals("#   step 3: ...", lines[1])
    // Blank line separator, then `config:`.
    assertTrue(
      lines.subList(2, 5).any { it == "config:" },
      "expected `config:` shortly after comments, got first 5 lines: ${lines.take(5)}",
    )
  }

  @Test
  fun `classifier insertion order is preserved across emit`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Multi-classifier step",
          recordings = linkedMapOf(
            "ios-iphone" to listOf(noopTool()),
            "android-phone" to listOf(noopTool()),
            "lab-a" to listOf(noopTool()),
          ),
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    val iosIdx = emitted.indexOf("ios-iphone:")
    val androidIdx = emitted.indexOf("android-phone:")
    val labIdx = emitted.indexOf("lab-a:")
    assertTrue(iosIdx in 0 until androidIdx, "ios-iphone should come before android-phone")
    assertTrue(androidIdx in 0 until labIdx, "android-phone should come before lab-a")
  }

  @Test
  fun `emit then parse roundtrips all step features`() {
    val original = UnifiedTrail(
      config = UnifiedTrailConfig(
        id = "myapp/checkout",
        target = "myapp",
        description = "Open the checkout flow and complete a payment.",
        devices = mapOf("android" to "ANDROID_ONDEVICE_ACCESSIBILITY", "ios" to "IOS_HOST"),
        context = "Test context — one line",
        memory = mapOf("email" to "tb+test@example.com"),
        metadata = mapOf("jira" to "PROJ-123"),
      ),
      trail = listOf(
        UnifiedTrailStep(
          step = "Multi-line step\nwith two physical lines",
          recordings = linkedMapOf(
            "android" to listOf(noopTool()),
            "ios" to listOf(noopTool()),
          ),
        ),
        UnifiedTrailStep(
          step = "LLM step",
          recordings = emptyMap(),
          recordable = false,
        ),
        UnifiedTrailStep(
          step = "Skip on tablet",
          recordings = linkedMapOf(
            "android-phone" to listOf(noopTool()),
            "android-tablet" to emptyList(),
          ),
        ),
        UnifiedTrailStep(
          step = "Six-digit passcode entry needs extra budget",
          recordings = mapOf("android-phone" to listOf(noopTool())),
          maxRetries = 10,
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(original)
    val reparsed = yaml.decodeUnifiedTrail(emitted)
    assertEquals(original.config, reparsed.config)
    assertEquals(original.trail.size, reparsed.trail.size)
    assertEquals(original.trail[0].step, reparsed.trail[0].step)
    assertEquals(original.trail[0].recordings.keys, reparsed.trail[0].recordings.keys)
    assertFalse(reparsed.trail[1].recordable)
    assertEquals(emptyList(), reparsed.trail[2].recordings["android-tablet"])
    assertEquals(10, reparsed.trail[3].maxRetries, "maxRetries must round-trip from emit to parse")
    assertEquals(null, reparsed.trail[0].maxRetries, "absent maxRetries must round-trip as null")
  }

  @Test
  fun `maxRetries is emitted as a step-level scalar on the same indent as recordable`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Passcode entry",
          recordings = mapOf("android-phone" to listOf(noopTool())),
          maxRetries = 10,
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(
      emitted.contains("\n    maxRetries: 10\n"),
      "expected `    maxRetries: 10` line at step-key indent, got:\n$emitted",
    )
  }

  @Test
  fun `a verify step emits as a verify list entry and round-trips`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android-phone" to listOf(noopTool()))),
        UnifiedTrailStep(
          step = "The cart shows 2 items",
          verify = true,
          recordings = mapOf("android-phone" to listOf(noopTool())),
          maxRetries = 5,
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue(
      "- verify: \"The cart shows 2 items\"" in emitted,
      "expected `- verify:` list entry, got:\n$emitted",
    )
    assertEquals(trail, yaml.decodeUnifiedTrail(emitted), "verify step must survive emit → decode")
  }

  @Test
  fun `emitting a verify trailhead fails loud instead of flattening to step`() {
    // A verify trailhead can't come from parsing (the parser rejects it) — only from code. The
    // emitter must refuse rather than silently write it as `step:` and lose the kind.
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(step = "Signed in", verify = true),
      trail = listOf(UnifiedTrailStep(step = "hi")),
    )
    assertFailsWith<IllegalArgumentException> { yaml.encodeUnifiedTrailToString(trail) }
  }

  @Test
  fun `maxRetries is omitted from output when null`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "No override",
          recordings = mapOf("android-phone" to listOf(noopTool())),
        ),
      ),
    )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertFalse(
      emitted.contains("maxRetries"),
      "null maxRetries must not appear in emitted YAML, got:\n$emitted",
    )
  }

  /**
   * A trivial tool wrapper that round-trips through the existing tool-list
   * serializer without depending on classpath tool discovery. We use the
   * `OtherTrailblazeTool` raw shape so the test stays platform-agnostic.
   */
  private fun noopTool(): TrailblazeToolYamlWrapper = TrailblazeToolYamlWrapper(
    name = "noop",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "noop",
      raw = JsonObject(mapOf("note" to JsonPrimitive("placeholder"))),
    ),
  )
}
