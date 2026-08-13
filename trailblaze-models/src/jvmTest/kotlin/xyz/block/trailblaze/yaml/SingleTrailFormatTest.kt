package xyz.block.trailblaze.yaml

import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Unified is the only format a trail is ever written in. The parser for the legacy v1 list shape
 * (`- config:` / `- prompts:` / `- tools:`) was removed in #5043 and the last writers in #5422, so a
 * v1 document is unreadable by every runtime that consumes a trail. That makes re-introducing a v1
 * *emitter* the expensive regression: it writes files nothing can read, and the breakage lands on
 * whoever next runs the trail rather than on the change that caused it.
 *
 * What is and is not covered here, so nobody over-trusts it:
 * - **Covered:** everything `TrailblazeYaml` itself writes a trail through, and the one pluggable
 *   slot a v1 emitter historically came back through (a contextual `TrailYamlItem` serializer) —
 *   judged by what it emits, not by whether it is registered.
 * - **Not covered: string-building emitters that never touch `TrailblazeYaml`.** Four still exist
 *   and are tracked in #5773 — `WaypointShortcutVerifyCommand.buildTrailYaml` and
 *   `BlazeRoutes.prependTrailheadTool` / `prependClearAppData` (`:trailblaze-host`),
 *   `WebGesture.toTrailYaml` (`:trailblaze-ui`), and `RecordingYamlCodec.renderTrailheadBlock`
 *   (this module, but it string-builds rather than encoding). Three of those live in modules this
 *   test cannot see. The trail *save-back* paths are not among them: `TrailFileManager` and
 *   `RecordedTrailsRepoJvm` both write unified through `UnifiedRecordingWriter`.
 * - **The durable on-disk half** is `TrailYamlValidationTest`, which parses every committed
 *   `*.trail.yaml`. With no v1 parser, "parses" means "is unified".
 */
class SingleTrailFormatTest {

  private val trailblazeYaml = createTrailblazeYaml()

  @Test
  fun `a written trail round-trips through the parser that runs it`() {
    // The strongest statement of the invariant: writing and running agree. A v1 emitter breaks
    // exactly here — it produces a document the runtime cannot decode.
    val trail = sampleTrail()

    val yaml = trailblazeYaml.encodeUnifiedTrailToString(trail)

    assertTrue(yaml.isNotBlank(), "encoding a trail produced no output")
    assertFalse(
      isV1ListShape(yaml),
      "a written trail must be a unified mapping, not a v1 list; got:\n$yaml",
    )
    assertTrue(ROOT_CONFIG_KEY.containsMatchIn(yaml), "expected a root `config:` key in:\n$yaml")
    assertTrue(ROOT_TRAIL_KEY.containsMatchIn(yaml), "expected a root `trail:` key in:\n$yaml")

    val decoded = trailblazeYaml.decodeTrailDocument(yaml) as TrailDocument.Unified
    assertEquals(trail.config, decoded.trail.config, "config did not survive the round trip")
    assertEquals(trail.trail, decoded.trail.trail, "steps did not survive the round trip")
  }

  @Test
  fun `a trail written with leading comments is still a unified mapping`() {
    // The migrator writes drift warnings as `# ` lines above `config:`. Those lines precede the
    // root node, so a shape check that reads the literal first line would misread this file.
    val yaml = trailblazeYaml.encodeUnifiedTrailToString(
      sampleTrail(),
      leadingComments = listOf("NL drift: step 1 was reworded"),
    )

    assertFalse(isV1ListShape(yaml), "a commented trail must still be a unified mapping:\n$yaml")
    assertTrue(
      trailblazeYaml.decodeTrailDocument(yaml) is TrailDocument.Unified,
      "a commented trail must still decode:\n$yaml",
    )
  }

  @Test
  fun `the legacy v1 list shape is not readable`() {
    // Why writing v1 is a bug rather than a style choice: nothing can read it back.
    val v1 = V1_TRAIL

    // Proves the shape check below can actually recognize a v1 document — without this, a helper
    // that always returned false would leave every `assertFalse(isV1ListShape(...))` vacuous.
    assertTrue(isV1ListShape(v1), "the fixture is not the legacy v1 list shape:\n$v1")

    val failure = assertFailsWith<IllegalArgumentException> {
      trailblazeYaml.decodeTrailDocument(v1)
    }
    // Assert WHY it failed, not merely that it did: a v1 list must be rejected for its non-mapping
    // root. `assertFalse(decoded is TrailDocument.Unified)` would not say this — TrailDocument is
    // deliberately kept sealed so a format could return as a sibling, and a restored v1 parser
    // returning `TrailDocument.V1` would satisfy that assertion while the runtime acted on it.
    assertTrue(
      failure.message.orEmpty().contains("root must be a mapping"),
      "expected the v1 list to be rejected for its non-mapping root, got: ${failure.message}",
    )
  }

  @Test
  fun `nothing wired into the yaml instance can emit a v1 trail document`() {
    // The slot a v1 emitter came back through is a contextual serializer for TrailYamlItem — the
    // executor's in-memory shape, which the deleted TrailYamlItemSerializer encoded as the legacy
    // list. Assert what that slot PRODUCES, never that it is empty: a serializer that emits
    // unified satisfies the invariant just as well, and requiring an empty slot would fail a
    // behavior-preserving refactor. Nothing is registered today, so this is a for-all over an
    // empty set — it holds trivially now and bites the moment something is wired in. The
    // assertions that carry weight either way are the round-trip and v1-rejection tests above.
    val yamlInstance = trailblazeYaml.getInstance()
    val serializer = yamlInstance.serializersModule.getContextual(TrailYamlItem::class)

    // A serializer that refuses to encode also satisfies the invariant — the failure mode that
    // matters is one that succeeds and hands back the legacy shape.
    val encoded = serializer?.let {
      runCatching { yamlInstance.encodeToString(ListSerializer(it), TRAIL_ITEMS) }.getOrNull()
    }

    assertFalse(
      encoded != null && isV1ListShape(encoded),
      "a contextual TrailYamlItem serializer is wired in that emits the legacy v1 list shape. " +
        "Nothing can parse that shape any more, so everything it writes is unreadable. A trail is " +
        "written via encodeUnifiedTrailToString; encodeTools writes a bare tool envelope, which " +
        "is a list of tools and not a trail document.\n$encoded",
    )
  }

  private fun sampleTrail() = UnifiedTrail(
    config = UnifiedTrailConfig(id = "app/login", target = "app"),
    trail = listOf(
      UnifiedTrailStep(step = "Sign in"),
      UnifiedTrailStep(step = "The home screen is shown", verify = true),
    ),
  )

  private companion object {
    /** The executor's in-memory shape, as the deleted v1 serializer would have been handed it. */
    val TRAIL_ITEMS = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/login", target = "app")),
      TrailYamlItem.PromptsTrailItem(listOf(DirectionStep(step = "Sign in"))),
    )

    val ROOT_CONFIG_KEY = Regex("(?m)^config:")
    val ROOT_TRAIL_KEY = Regex("(?m)^trail:")

    val V1_TRAIL = """
      - config:
          id: app/login
          target: app
      - prompts:
        - step: Sign in
    """.trimIndent()

    /**
     * A v1 trail document is a YAML sequence at the root; a unified one is a mapping rooted at
     * `config:` / `trail:`. Leading `#` comments (emitted by `encodeUnifiedTrailToString`'s
     * `leadingComments`) and a `---` document-start marker precede the root node, so they are
     * skipped rather than read as the shape.
     */
    fun isV1ListShape(yaml: String): Boolean = yaml.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it != "---" }
      ?.let { it.startsWith("- ") || it.startsWith("[") } == true
  }
}
