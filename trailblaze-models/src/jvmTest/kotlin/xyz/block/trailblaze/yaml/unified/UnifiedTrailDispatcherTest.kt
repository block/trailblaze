package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Pins the version-aware [TrailblazeYaml.decodeTrailDocument] dispatcher's
 * routing behavior:
 *
 *  1. v1 (the original format, present in the vast majority of trail files)
 *     is the default fast path.
 *  2. the unified format is the fallback — engages only when v1 parsing throws.
 *  3. When both fail, the error message surfaces *both* underlying errors
 *     because the right one to read depends on which version the author
 *     intended.
 */
class UnifiedTrailDispatcherTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `v1 input takes the fast path — fallback to the unified format does not engage`() {
    val doc = yaml.decodeTrailDocument(
      """
      - config:
          id: x
          target: y
      - prompts:
          - step: hi
      """.trimIndent(),
    )
    assertTrue(doc is TrailDocument.V1)
    val items = doc.items
    assertEquals(2, items.size)
    assertTrue(items[0] is TrailYamlItem.ConfigTrailItem)
    assertTrue(items[1] is TrailYamlItem.PromptsTrailItem)
  }

  @Test
  fun `unified input falls back from v1 — KAML throws on the wrong root shape, the unified format picks up`() {
    val doc = yaml.decodeTrailDocument(
      """
      config:
        id: x
        target: y
      trail:
        - step: hi
          recording:
            android-phone: []
      """.trimIndent(),
    )
    assertTrue(doc is TrailDocument.Unified)
    assertEquals("x", doc.trail.config.id)
    assertEquals(1, doc.trail.trail.size)
  }

  @Test
  fun `v1 fast path returns v1 even for trails that look unified-shaped but are valid v1`() {
    // A v1 file that happens to contain `step:` and a classifier-shaped string
    // — still a top-level list, so v1 wins. We're guarding against the
    // dispatcher second-guessing itself when v1 parsing succeeds.
    val doc = yaml.decodeTrailDocument(
      """
      - config:
          id: x
          target: y
          platform: android
      - prompts:
        - step: Tap something
          recording:
            tools:
            - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    assertTrue(doc is TrailDocument.V1)
  }

  @Test
  fun `garbage YAML rethrows v1 error verbatim with the unified format attached as suppressed`() {
    // We preserve v1's exception type (typically SerializationException) so
    // callers that catch the specific KAML exception keep working unchanged.
    // The the unified format attempt's error is attached as a suppressed exception so it's
    // still visible in stack traces / `.suppressed`.
    val ex = assertFailsWith<Throwable> {
      yaml.decodeTrailDocument("not yaml at all: : : :")
    }
    assertTrue(
      ex.suppressed.isNotEmpty(),
      "expected the the unified format error to be attached as suppressed, got: ${ex.suppressed.toList()}",
    )
  }

  @Test
  fun `mapping root that is neither v1 nor a complete the unified format trail surfaces v1 error`() {
    // A YAML mapping with random keys can't be v1 (which is a list) and isn't
    // a valid unified trail either. The thrown exception is v1's, with the unified attempt's
    // attached as suppressed — so the user has both diagnostics available.
    val ex = assertFailsWith<Throwable> {
      yaml.decodeTrailDocument(
        """
        somethingElse:
          foo: bar
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.suppressed.any { it.message?.contains("trail") == true || it.message?.contains("config") == true },
      "expected the suppressed the unified format error to mention trail/config requirements, " +
        "got: ${ex.suppressed.map { it.message }}",
    )
  }
}
