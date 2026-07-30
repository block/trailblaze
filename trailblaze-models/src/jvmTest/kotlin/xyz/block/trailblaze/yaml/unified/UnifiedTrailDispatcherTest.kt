package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Pins [TrailblazeYaml.decodeTrailDocument]'s behavior now that the legacy v1 list format is gone:
 * every trail is parsed as the unified (mapping-root) format, and a non-unified document surfaces
 * the unified parse error directly.
 */
class UnifiedTrailDispatcherTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `unified mapping-root input decodes to a Unified document`() {
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
  fun `a legacy top-level list is no longer parseable and throws`() {
    // v1's top-level-list shape is not a mapping root, so the unified parser rejects it.
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeTrailDocument(
        """
        - config:
            id: x
            target: y
        - prompts:
            - step: hi
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `garbage YAML throws`() {
    assertFailsWith<Throwable> {
      yaml.decodeTrailDocument("not yaml at all: : : :")
    }
  }

  @Test
  fun `a mapping root that is not a valid unified trail throws a unified parse error`() {
    val ex = assertFailsWith<Throwable> {
      yaml.decodeTrailDocument(
        """
        somethingElse:
          foo: bar
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message?.contains("trail") == true || ex.message?.contains("config") == true ||
        ex.message?.contains("mapping") == true,
      "expected the unified parse error to mention trail/config/mapping requirements, got: ${ex.message}",
    )
  }
}
