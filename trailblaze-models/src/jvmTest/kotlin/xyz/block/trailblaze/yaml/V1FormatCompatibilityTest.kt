package xyz.block.trailblaze.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * Compatibility guarantee for the **legacy v1 trail format** (top-level list of
 * `- config:` / `- tools:` / `- prompts:` items). The unified single-file format
 * ([UnifiedTrailParserTest]) is now the authoring default and the example trails
 * have been migrated to it, so no committed sample file exercises the v1 shape
 * end-to-end anymore. These inline fixtures keep v1 parsing tested on its own,
 * independent of what happens to be on disk, so v1 support stays intentional.
 *
 * v1 remains a supported input until it is deliberately retired (targeted a couple
 * of months out — the recorder still emits v1 session recordings and `blaze.yaml`
 * prose is v1). **When v1 is removed, delete this whole file** along with the v1
 * decode path in [TrailblazeYaml].
 */
class V1FormatCompatibilityTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `a v1 config plus tools plus prompts trail still parses as V1`() {
    val doc = yaml.decodeTrailDocument(
      """
      - config:
          id: "sample-app/catalog/overlay-tap"
          target: "xyz.block.trailblaze.examples.sampleapp"
      - tools:
          - launchApp:
              appId: "xyz.block.trailblaze.examples.sampleapp"
              launchMode: "REINSTALL"
      - prompts:
          - step: Navigate to the "Catalog" tab
          - verify: A dialog showing "Coffee Latte" is visible
      """.trimIndent(),
    )

    // The version-aware dispatcher must classify this as v1 (list root), not unified.
    assertTrue(doc is TrailDocument.V1, "expected v1 classification, got ${doc::class.simpleName}")
    val items = doc.items

    val config = items.filterIsInstance<TrailYamlItem.ConfigTrailItem>().single().config
    assertEquals("sample-app/catalog/overlay-tap", config.id)
    assertEquals("xyz.block.trailblaze.examples.sampleapp", config.target)

    // The top-level `- tools:` block (a launchApp bootstrap) survives as a ToolTrailItem.
    val toolItem = items.filterIsInstance<TrailYamlItem.ToolTrailItem>().single()
    assertEquals(1, toolItem.tools.size)

    val steps = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    assertEquals(2, steps.size)
  }

  @Test
  fun `a v1 verify step lowers to VerificationStep and a step to DirectionStep`() {
    // The load-bearing kind distinction: a v1 `verify:` step is a VerificationStep (assertion-
    // scoped surface, never self-healed), a `step:` is a DirectionStep. Losing this would
    // silently downgrade verifications — the exact regression the unified format had to avoid.
    val doc = yaml.decodeTrailDocument(
      """
      - config:
          id: kinds/example
          target: x
      - prompts:
          - step: Do a thing
          - verify: The thing happened
      """.trimIndent(),
    )

    assertTrue(doc is TrailDocument.V1)
    val steps = doc.items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps

    val direction = steps[0]
    assertTrue(direction is DirectionStep, "step: must parse to DirectionStep, got ${direction::class.simpleName}")
    assertEquals("Do a thing", direction.step)

    val verification = steps[1]
    assertTrue(
      verification is VerificationStep,
      "verify: must parse to VerificationStep, got ${verification::class.simpleName}",
    )
    assertEquals("The thing happened", verification.verify)
  }

  @Test
  fun `a v1 bare tools-only trail with no prompts still parses`() {
    // A bare v1 `- tools:` block: config + a tools block pinning two MCP subprocess tools, with NO
    // natural-language prompts. A standalone parse guard for that shape — no committed sample file
    // exercises it anymore (the mcp-tools-demo trail this once mirrored is now unified).
    // generateTestUser / currentEpochMillis aren't classpath-registered tool classes here, so they
    // decode via the OtherTrailblazeTool fallback — which is itself part of what v1 parsing must do.
    val doc = yaml.decodeTrailDocument(
      """
      - config:
          id: "sampleapp/mcp-tools-demo"
          target: sampleapp
      - tools:
          - generateTestUser: {}
          - currentEpochMillis: {}
      """.trimIndent(),
    )

    assertTrue(doc is TrailDocument.V1)
    assertEquals(2, doc.items.filterIsInstance<TrailYamlItem.ToolTrailItem>().single().tools.size)
    assertTrue(
      doc.items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().isEmpty(),
      "tools-only trail must have no prompt items",
    )
  }

  @Test
  fun `decodeTrail lowers a v1 trail unchanged for the runtime`() {
    // decodeTrail is the runtime entry point. A v1 trail passes straight through (no
    // classifier lowering) so existing v1 execution is unaffected by the unified work.
    val items = yaml.decodeTrail(
      """
      - config:
          id: run/v1
          target: x
      - prompts:
          - step: Only step
      """.trimIndent(),
    )

    val steps = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    assertEquals(1, steps.size)
    assertEquals("Only step", (steps.single() as DirectionStep).step)
  }
}
