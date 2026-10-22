package xyz.block.trailblaze.config

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Tests for [ToolYamlLoader]'s suffix-driven validation.
 *
 * The loader walks `tools/` (regular tools) and `trailheads/` for
 * `.yaml` files; the resource-source contract returns map keys with `.yaml` already
 * stripped. So a file `eraseText.tool.yaml` becomes a map key `eraseText.tool` — the
 * trailing word identifies the operational class. The loader's job is to dispatch on
 * that trailing word and enforce that the parsed content matches what the suffix
 * promised.
 *
 * Tests go through [ToolYamlLoader.parseAllConfigs] (internal-visibility) so the
 * `List<ToolYamlConfig>` is directly observable. The lenient `loadAllYamlWithErrorHandling`
 * path catches `require()` failures and logs a warning — tests capture `Console` output
 * to assert on the operator-facing message.
 *
 * Console capture mirrors the pattern used in `TrailblazeTrailmapManifestLoaderTest`.
 */
class ToolYamlLoaderSuffixTest {

  private var capturedLog: ByteArrayOutputStream? = null
  private val originalConsoleStreams = mutableMapOf<String, PrintStream>()

  @BeforeTest fun setUpConsoleCapture() {
    val newCapture = ByteArrayOutputStream()
    val newStream = PrintStream(newCapture, /* autoFlush = */ true, Charsets.UTF_8)
    listOf("out", "userOut").forEach { fieldName ->
      val field = runCatching {
        Console::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
      }.getOrNull() ?: return@forEach
      originalConsoleStreams[fieldName] = field.get(Console) as PrintStream
      field.set(Console, newStream)
    }
    capturedLog = newCapture
  }

  @AfterTest fun cleanup() {
    originalConsoleStreams.forEach { (fieldName, original) ->
      Console::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
        .set(Console, original)
    }
    originalConsoleStreams.clear()
    capturedLog = null
  }

  private fun capturedText(): String =
    capturedLog?.toString(Charsets.UTF_8) ?: error("Console capture not initialized")

  // ----------------------------------------------------------------------
  // Happy paths — each suffix accepts the matching content shape.
  // ----------------------------------------------------------------------

  @Test
  fun `tool yaml suffix accepts a regular tool with no metadata blocks`() {
    val yamlContents = mapOf(
      "eraseText.tool" to """
        id: eraseText
        description: Erases characters.
        tools:
          - tap: { selector: "x" }
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(1, parsed.size)
    val config = parsed.single()
    assertEquals("eraseText", config.id)
    assertNull(config.trailhead)
  }

  @Test
  fun `trailhead yaml suffix accepts a tool with a trailhead block`() {
    val yamlContents = mapOf(
      "launchAppSignedIn.trailhead" to """
        id: myapp_launchAppSignedIn
        description: Launch the app and sign in.
        tools:
          - tap: { selector: "x" }
        trailhead:
          to: myapp/android/home_signed_in
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(1, parsed.size)
    assertNotNull(parsed.single().trailhead)
  }

  @Test
  fun `trailhead yaml suffix accepts an empty trailhead block with no destination`() {
    val yamlContents = mapOf(
      "launchApp.trailhead" to """
        id: myapp_launchApp
        description: Launch the app.
        tools:
          - tap: { selector: "x" }
        trailhead: {}
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(TrailheadMetadata(to = null), parsed.single().trailhead)
  }

  @Test
  fun `trailhead yaml suffix rejects a blank destination and says to omit it`() {
    val yamlContents = mapOf(
      "launchApp.trailhead" to """
        id: myapp_launchApp
        description: Launch the app.
        tools:
          - tap: { selector: "x" }
        trailhead:
          to: ""
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(0, parsed.size)
    val log = capturedText()
    assertTrue(log.contains("'trailhead.to' is blank") && log.contains("Omit it"), "Expected blank-destination error; got: $log")
  }

  // ----------------------------------------------------------------------
  // Suffix-content mismatches — the most important rule the loader
  // enforces beyond what ToolYamlConfig.validate() already covers.
  // ----------------------------------------------------------------------

  @Test
  fun `tool yaml suffix rejects content that declares a trailhead block`() {
    val yamlContents = mapOf(
      "wrongSuffixTH.tool" to """
        id: wrong_suffix_th
        description: Should be in a .trailhead.yaml file.
        tools:
          - tap: { selector: "x" }
        trailhead:
          to: a/b
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(0, parsed.size)
    val log = capturedText()
    assertTrue(
      log.contains(".tool.yaml") && log.contains("trailhead:"),
      "Expected mismatch warning; got: $log",
    )
  }

  @Test
  fun `trailhead yaml suffix rejects content missing the trailhead block`() {
    val yamlContents = mapOf(
      "missing_th_block.trailhead" to """
        id: missing_th_block
        description: A tool that claims to be a trailhead but has no trailhead block.
        tools:
          - tap: { selector: "x" }
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(0, parsed.size)
    val log = capturedText()
    assertTrue(
      log.contains(".trailhead.yaml") && log.contains("missing"),
      "Expected error saying the trailhead block is missing; got: $log",
    )
  }

  // ----------------------------------------------------------------------
  // Unrecognized suffix — files in tools/ MUST be one of the two.
  // ----------------------------------------------------------------------

  @Test
  fun `unrecognized suffix is rejected with an actionable error`() {
    val yamlContents = mapOf(
      // Missing trailing `.tool` / `.trailhead` — the file would have been
      // named just `eraseText.yaml`, leaving the stripped key as `eraseText`.
      "eraseText" to """
        id: eraseText
        description: ok
        tools:
          - tap: { selector: "x" }
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(0, parsed.size)
    val log = capturedText()
    assertTrue(
      log.contains("recognized type suffix") &&
        log.contains(".tool.yaml") &&
        log.contains(".trailhead.yaml"),
      "Expected unrecognized-suffix error listing both valid suffixes; got: $log",
    )
  }

  // ----------------------------------------------------------------------
  // Duplicate-id warning. Downstream consumers of `discoverYamlDefinedTools` /
  // `discoverAndLoadAll` collapse same-id entries via `.associate { ... }` —
  // the second entry silently overwrites the first. The loader now emits a
  // warning when this happens, so an author who creates two files with the
  // same `id:` (e.g. forgot to rename the id when promoting a tool) gets a
  // signal instead of a silent registry drop.
  // ----------------------------------------------------------------------

  @Test
  fun `loader warns when two tool YAMLs declare the same id`() {
    val yamlContents = mapOf(
      "first.tool" to """
        id: duplicated_id
        description: First copy.
        tools:
          - tap: { selector: "x" }
      """.trimIndent(),
      "second.tool" to """
        id: duplicated_id
        description: Second copy with the same id.
        tools:
          - tap: { selector: "y" }
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    // Both parse successfully — neither is invalid in isolation. The duplicate
    // is a cross-config invariant violation.
    assertEquals(2, parsed.size)
    val log = capturedText()
    assertTrue(
      log.contains("duplicated_id") &&
        log.contains("Tool id") &&
        log.contains("2"),
      "Expected duplicate-id warning naming the id and the count; got: $log",
    )
    assertTrue(
      log.contains("Rename") || log.contains("rename") || log.contains("unique"),
      "Expected actionable rename hint; got: $log",
    )
  }

  @Test
  fun `loader does not warn when ids are all unique`() {
    val yamlContents = mapOf(
      "alpha.tool" to """
        id: alpha_tool
        description: Alpha.
        tools:
          - tap: { selector: "a" }
      """.trimIndent(),
      "beta.tool" to """
        id: beta_tool
        description: Beta.
        tools:
          - tap: { selector: "b" }
      """.trimIndent(),
    )
    ToolYamlLoader.parseAllConfigs(yamlContents)
    val log = capturedText()
    assertTrue(
      !log.contains("Tool id") || !log.contains("declared by"),
      "Expected NO duplicate-id warning when ids are unique; got: $log",
    )
  }

  // ----------------------------------------------------------------------
  // Mixed batch — confirm one bad file doesn't take the rest down.
  // ----------------------------------------------------------------------

  @Test
  fun `valid and invalid files in same batch are processed independently`() {
    val yamlContents = mapOf(
      "good.tool" to """
        id: good_tool
        description: A valid tool.
        tools:
          - tap: { selector: "x" }
      """.trimIndent(),
      "bad.tool" to """
        id: bad_tool
        description: Has a stray trailhead block.
        tools:
          - tap: { selector: "x" }
        trailhead:
          to: a/b
      """.trimIndent(),
      "another_good.tool" to """
        id: another_good_tool
        description: Also valid.
        tools:
          - tap: { selector: "x" }
      """.trimIndent(),
    )
    val parsed = ToolYamlLoader.parseAllConfigs(yamlContents)
    assertEquals(
      setOf("good_tool", "another_good_tool"),
      parsed.map { it.id }.toSet(),
      "Both valid tools should load; the invalid one should be filtered out",
    )
  }
}
