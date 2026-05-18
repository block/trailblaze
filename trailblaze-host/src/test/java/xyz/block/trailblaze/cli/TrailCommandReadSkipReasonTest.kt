package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [TrailCommand.Companion.readSkipReason] — the single source the
 * in-process and daemon-delegate per-file loops both consult to decide whether to short-circuit
 * a trail. The helper normalizes three otherwise-ambiguous inputs into "not skipped" so the
 * loop body can rely on a single null check:
 *
 *  1. Missing `skip:` field → null
 *  2. Empty / blank `skip:` value → null (an accidental `skip: ""` must not silently disable a
 *     trail; the schema convention requires a reason)
 *  3. Parse failure on a malformed trail → null, deferring the actual error to the runner so
 *     the user sees one clear "failed to decode" message rather than two
 *
 * The positive case (a real reason string) round-trips with surrounding whitespace trimmed,
 * because the CLI prints the reason verbatim in `Skipped: <reason>` and a trailing newline in
 * the YAML would otherwise leak into the log output.
 */
class TrailCommandReadSkipReasonTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeTrail(yaml: String): File {
    val file = File(tempFolder.root, "sample.trail.yaml")
    file.writeText(yaml.trimIndent())
    return file
  }

  @Test
  fun `returns null when skip field is absent`() {
    val file = writeTrail(
      """
      - config:
          title: Sample trail
          platform: android
      - tools:
        - pressBack: {}
      """,
    )
    assertNull(TrailCommand.readSkipReason(file))
  }

  @Test
  fun `returns null when skip value is empty`() {
    val file = writeTrail(
      """
      - config:
          title: Sample trail
          platform: android
          skip: ""
      - tools:
        - pressBack: {}
      """,
    )
    assertNull(TrailCommand.readSkipReason(file))
  }

  @Test
  fun `returns null when skip value is whitespace only`() {
    val file = writeTrail(
      """
      - config:
          title: Sample trail
          platform: android
          skip: "   "
      - tools:
        - pressBack: {}
      """,
    )
    assertNull(TrailCommand.readSkipReason(file))
  }

  @Test
  fun `returns trimmed reason string when skip has a real value`() {
    val file = writeTrail(
      """
      - config:
          title: Sample trail
          platform: android
          skip: "  Compact element list regression — see #2194  "
      - tools:
        - pressBack: {}
      """,
    )
    assertEquals(
      "Compact element list regression — see #2194",
      TrailCommand.readSkipReason(file),
    )
  }

  @Test
  fun `returns null when YAML is malformed - parse error is deferred to runner`() {
    val file = writeTrail(
      """
      not: a: valid: trail: at: all
        nesting: chaos
      """,
    )
    assertNull(TrailCommand.readSkipReason(file))
  }

  @Test
  fun `templated skip reason is resolved via TrailYamlTemplateResolver before being returned`() {
    // Pre-pass parse path must use the SAME template-resolution step as the runtime, otherwise
    // a `{{var}}` placeholder in `config.skip:` would be returned literally by readSkipReason
    // while the runtime would substitute it cleanly. Test using the built-in CWD variable so
    // no environment setup is needed — the assertion is that resolution happened (placeholder
    // is gone), not the specific resolved value.
    val file = writeTrail(
      """
      - config:
          title: Sample trail
          platform: android
          skip: "blocked at {{CWD}}"
      - tools:
        - pressBack: {}
      """,
    )
    val reason = TrailCommand.readSkipReason(file)
    assertNotNull(reason, "templated skip reason must resolve and be returned")
    assertFalse(
      reason.contains("{{CWD}}"),
      "template placeholders must be resolved before the reason is returned (got: '$reason')",
    )
    assertTrue(reason.startsWith("blocked at "), "non-template content must round-trip unchanged")
  }
}
