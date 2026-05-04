package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tests for the `shortcut:` metadata block on [ToolYamlConfig].
 *
 * Covers structural-validation rules — the `from`/`to` slash-format check, the required
 * `shortcut_` id prefix, the optional non-blank `variant`. The runtime contract (pre/post-
 * condition assertion at execution time) lives in `trailblaze-common` and is tested there.
 *
 * Test fixtures construct configs directly rather than via YAML decode so each test
 * exercises one rule cleanly. The kaml-decode path is covered separately in the loader
 * integration tests.
 */
class ToolYamlConfigShortcutTest {

  // A minimal valid `tools:` mode body so we can populate everything else around the
  // shortcut block without re-asserting the body invariants in every test.
  private val sampleBody: List<JsonObject> = listOf(
    JsonObject(mapOf("tap" to JsonPrimitive("ignored"))),
  )

  @Test
  fun `tools-mode tool with valid shortcut block passes validate`() {
    val config = ToolYamlConfig(
      id = "shortcut_create_alarm",
      description = "Create an alarm at a given time.",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(
        from = "clock/android/alarm_tab",
        to = "clock/android/alarm_saved",
      ),
    )
    config.validate() // does not throw
    assertEquals("clock/android/alarm_tab", config.shortcut?.from)
    assertEquals("clock/android/alarm_saved", config.shortcut?.to)
  }

  @Test
  fun `class-mode tool with valid shortcut block passes validate`() {
    // Shortcut metadata is independent of body mode — a Kotlin-class-backed tool can be a
    // shortcut just like a YAML-composed one. The class itself reflects description /
    // parameters / etc., so they stay null in YAML; the shortcut block adds from/to.
    val config = ToolYamlConfig(
      id = "shortcut_kotlin_class_backed",
      toolClass = "com.example.MyShortcutTool",
      shortcut = ShortcutMetadata(
        from = "app/main",
        to = "app/detail",
      ),
    )
    config.validate() // does not throw
  }

  @Test
  fun `shortcut block accepts any descriptive id (no prefix required)`() {
    // The earlier `shortcut_` id-prefix rule was dropped — file suffix
    // (`*.shortcut.yaml`) carries the type signal at the loader level, so the data
    // class no longer enforces an id prefix. Pin the new contract: any id works.
    val config = ToolYamlConfig(
      id = "clock_create_alarm",
      description = "Create an alarm.",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(
        from = "clock/android/alarm_tab",
        to = "clock/android/alarm_saved",
      ),
    )
    config.validate() // does not throw
  }

  @Test
  fun `shortcut block rejects blank from`() {
    val config = ToolYamlConfig(
      id = "shortcut_bad_from",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "", to = "app/detail"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("'from:'"))
  }

  @Test
  fun `shortcut block rejects blank to`() {
    val config = ToolYamlConfig(
      id = "shortcut_bad_to",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "app/main", to = ""),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("'to:'"))
  }

  @Test
  fun `shortcut block rejects from without slash separator`() {
    val config = ToolYamlConfig(
      id = "shortcut_no_slash_from",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "no_slash_here", to = "app/detail"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
    assertTrue(ex.message!!.contains("shortcut.from"))
  }

  @Test
  fun `shortcut block rejects to without slash separator`() {
    val config = ToolYamlConfig(
      id = "shortcut_no_slash_to",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "app/main", to = "noslash"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
    assertTrue(ex.message!!.contains("shortcut.to"))
  }

  @Test
  fun `shortcut block accepts variant when populated`() {
    val config = ToolYamlConfig(
      id = "shortcut_create_alarm_edit",
      description = "Edit existing alarm at this time.",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(
        from = "clock/android/alarm_tab",
        to = "clock/android/alarm_saved",
        variant = "edit",
      ),
    )
    config.validate() // does not throw
    assertEquals("edit", config.shortcut?.variant)
  }

  @Test
  fun `shortcut block rejects blank variant`() {
    // Distinguishing "field absent" (variant = null, fine) from "field present but blank"
    // (variant = "", malformed) — author should omit the field rather than empty it out.
    val config = ToolYamlConfig(
      id = "shortcut_bad_variant",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(
        from = "app/main",
        to = "app/detail",
        variant = "",
      ),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("'variant:'"))
  }

  @Test
  fun `null shortcut block leaves tool unchanged`() {
    // Pin: a regular tool (no shortcut block) validates and behaves exactly as before.
    // No prefix rule, no from/to rule, no variant rule. Backwards compatibility check.
    val config = ToolYamlConfig(
      id = "eraseText",
      description = "Erases characters.",
      toolsList = sampleBody,
      shortcut = null,
    )
    config.validate() // does not throw
    assertEquals(null, config.shortcut)
  }

  // ------------------------------------------------------------------
  // Tightened slash-shape rules on shortcut from/to.
  // ------------------------------------------------------------------

  @Test
  fun `shortcut block rejects from with single slash and empty segments`() {
    // `"/"` passes the old `'/' in from` check but has two empty segments. Pin the
    // tightened rule that all segments must be non-blank.
    val config = ToolYamlConfig(
      id = "shortcut_single_slash_from",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "/", to = "p/b"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
    assertTrue(ex.message!!.contains("shortcut.from"))
  }

  @Test
  fun `shortcut block rejects from with leading slash`() {
    // `"/foo"` splits to ["", "foo"] — first segment is blank.
    val config = ToolYamlConfig(
      id = "shortcut_leading_slash",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "/foo", to = "p/b"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
  }

  @Test
  fun `shortcut block rejects to with trailing slash`() {
    // `"foo/"` splits to ["foo", ""] — last segment is blank.
    val config = ToolYamlConfig(
      id = "shortcut_trailing_slash",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "p/a", to = "foo/"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
  }

  // ------------------------------------------------------------------
  // YAML decode / round-trip — the kaml-decode path the loader actually
  // uses at runtime. Constructor-only tests don't exercise serializer
  // behavior (default values, optional fields, nested types).
  // ------------------------------------------------------------------

  @Test
  fun `kaml decodes shortcut block from YAML with all fields populated`() {
    val source = """
      id: shortcut_create_alarm
      description: Create an alarm at a given time.
      parameters:
        - name: hour
          type: integer
          required: true
      tools:
        - tap: { selector: "ok" }
      shortcut:
        from: clock/android/alarm_tab
        to: clock/android/alarm_saved
        variant: standard
    """.trimIndent()

    val decoded = TrailblazeConfigYaml.instance
      .decodeFromString(ToolYamlConfig.serializer(), source)

    assertEquals("shortcut_create_alarm", decoded.id)
    val shortcut = assertNotNull(decoded.shortcut)
    assertEquals("clock/android/alarm_tab", shortcut.from)
    assertEquals("clock/android/alarm_saved", shortcut.to)
    assertEquals("standard", shortcut.variant)
    decoded.validate() // structurally valid
  }

  @Test
  fun `kaml decodes shortcut block without variant (variant defaults to null)`() {
    val source = """
      id: shortcut_open_picker
      description: Open the time picker.
      tools:
        - tap: { selector: "fab" }
      shortcut:
        from: clock/android/alarm_tab
        to: clock/android/alarm_time_picker
    """.trimIndent()

    val decoded = TrailblazeConfigYaml.instance
      .decodeFromString(ToolYamlConfig.serializer(), source)

    val shortcut = assertNotNull(decoded.shortcut)
    assertNull(shortcut.variant)
    decoded.validate()
  }

  @Test
  fun `kaml decodes tool without shortcut block (shortcut defaults to null)`() {
    // Backwards-compatibility lock: an existing tool YAML that pre-dates the shortcut
    // block decodes cleanly with `shortcut = null`. No surprise behavior change for
    // the entire existing tool corpus.
    val source = """
      id: eraseText
      description: Erases characters from the focused text field.
      parameters:
        - name: charactersToErase
          type: integer
          required: false
      tools:
        - tap: { selector: "x" }
    """.trimIndent()

    val decoded = TrailblazeConfigYaml.instance
      .decodeFromString(ToolYamlConfig.serializer(), source)

    assertNull(decoded.shortcut)
    decoded.validate()
  }

  @Test
  fun `shortcut block rejects from with empty middle segment`() {
    // `"a//b"` splits to ["a", "", "b"] — middle segment is blank.
    val config = ToolYamlConfig(
      id = "shortcut_empty_middle",
      description = "ok",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "a//b", to = "p/b"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
  }

  // ------------------------------------------------------------------
  // Trailhead block validation — same shape as shortcut tests above
  // but applied to the trailhead metadata path.
  // ------------------------------------------------------------------

  @Test
  fun `tools-mode tool with valid trailhead block passes validate`() {
    val config = ToolYamlConfig(
      id = "myapp_launchAppSignedIn",
      description = "Launch the app and reach the signed-in home screen.",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = "myapp/android/home_signed_in"),
    )
    config.validate() // does not throw
    assertEquals("myapp/android/home_signed_in", config.trailhead?.to)
  }

  @Test
  fun `class-mode tool with valid trailhead block passes validate`() {
    val config = ToolYamlConfig(
      id = "kotlin_backed_launcher",
      toolClass = "com.example.LaunchAppTool",
      trailhead = TrailheadMetadata(to = "app/home"),
    )
    config.validate() // does not throw
  }

  @Test
  fun `trailhead block rejects blank to`() {
    val config = ToolYamlConfig(
      id = "th_blank_to",
      description = "ok",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = ""),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("'to:'"))
  }

  @Test
  fun `trailhead block rejects malformed to (single slash)`() {
    val config = ToolYamlConfig(
      id = "th_single_slash",
      description = "ok",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = "/"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
    assertTrue(ex.message!!.contains("trailhead.to"))
  }

  @Test
  fun `trailhead block rejects malformed to (no slash)`() {
    val config = ToolYamlConfig(
      id = "th_no_slash",
      description = "ok",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = "noslash"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
  }

  @Test
  fun `trailhead block rejects to with leading slash`() {
    // `"/foo"` splits to ["", "foo"] — first segment is blank. Parallel to the same
    // rule applied to shortcut.from / shortcut.to.
    val config = ToolYamlConfig(
      id = "th_leading_slash",
      description = "ok",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = "/foo"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
    assertTrue(ex.message!!.contains("trailhead.to"))
  }

  @Test
  fun `trailhead block rejects to with trailing slash`() {
    // `"foo/"` splits to ["foo", ""] — last segment is blank.
    val config = ToolYamlConfig(
      id = "th_trailing_slash",
      description = "ok",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = "foo/"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
  }

  @Test
  fun `trailhead block rejects to with empty middle segment`() {
    // `"a//b"` splits to ["a", "", "b"] — middle segment is blank.
    val config = ToolYamlConfig(
      id = "th_empty_middle",
      description = "ok",
      toolsList = sampleBody,
      trailhead = TrailheadMetadata(to = "a//b"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("malformed"))
  }

  @Test
  fun `tool with both shortcut and trailhead blocks is rejected`() {
    // Mutual exclusion: a transition (gated on current waypoint) and a bootstrap
    // (always available) can't coexist in one tool. Pin this so a future schema
    // change can't accidentally silently allow both.
    val config = ToolYamlConfig(
      id = "both_blocks_populated",
      description = "Bad — declares both shortcut and trailhead.",
      toolsList = sampleBody,
      shortcut = ShortcutMetadata(from = "p/a", to = "p/b"),
      trailhead = TrailheadMetadata(to = "p/c"),
    )
    val ex = assertFailsWith<IllegalArgumentException> { config.validate() }
    assertTrue(ex.message!!.contains("both"))
    assertTrue(ex.message!!.contains("shortcut:"))
    assertTrue(ex.message!!.contains("trailhead:"))
  }

  @Test
  fun `kaml decodes trailhead block from YAML`() {
    val source = """
      id: myapp_launchAppSignedIn
      description: Launch the app and sign in.
      tools:
        - tap: { selector: "ok" }
      trailhead:
        to: myapp/android/home_signed_in
    """.trimIndent()

    val decoded = TrailblazeConfigYaml.instance
      .decodeFromString(ToolYamlConfig.serializer(), source)

    val trailhead = assertNotNull(decoded.trailhead)
    assertEquals("myapp/android/home_signed_in", trailhead.to)
    assertNull(decoded.shortcut)
    decoded.validate()
  }

  @Test
  fun `kaml decodes tool with neither shortcut nor trailhead block`() {
    // Both blocks default to null when absent. Pin backwards-compat for the
    // existing 154 classpath tools that pre-date both blocks.
    val source = """
      id: eraseText
      description: Erases characters.
      tools:
        - tap: { selector: "x" }
    """.trimIndent()

    val decoded = TrailblazeConfigYaml.instance
      .decodeFromString(ToolYamlConfig.serializer(), source)

    assertNull(decoded.shortcut)
    assertNull(decoded.trailhead)
    decoded.validate()
  }
}
