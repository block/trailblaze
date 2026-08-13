package xyz.block.trailblaze.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool

/**
 * Pins the on-device-RPC per-tool dispatch envelope: the host serializes one authored tool as a
 * bare tool-wrapper list ([TrailblazeYaml.encodeTools]) and the device decodes it via
 * [TrailblazeYaml.decodeTrailOrToolEnvelope]. A bare tool envelope is a YAML list, and so was the
 * legacy v1 trail shape — so this test also guards that a real trail document (a unified `config:` /
 * `trail:` mapping) is recognized as a trail and not mis-read as a tool envelope. The legacy
 * `- config:` / `- prompts:` list is no longer a trail at all: its parser was removed in #5043.
 */
class ToolEnvelopeRoundTripTest {

  private val yaml = TrailblazeYaml.Default

  private fun toolWrapper(name: String, args: Map<String, String> = emptyMap()) =
    TrailblazeToolYamlWrapper(
      name = name,
      trailblazeTool =
        OtherTrailblazeTool(
          toolName = name,
          raw = JsonObject(args.mapValues { JsonPrimitive(it.value) }),
        ),
    )

  @Test
  fun `a tool encoded as an envelope decodes back through decodeTrailOrToolEnvelope`() {
    // Use an unregistered client-tool name so the decode round-trips as OtherTrailblazeTool with
    // its raw args intact (a registered tool would decode to its typed class). The envelope shape
    // is identical either way — this test pins the transport, not the tool registry.
    val envelope =
      yaml.encodeTools(listOf(toolWrapper("clientDefinedTool", mapOf("text" to "Home"))))

    val items = yaml.decodeTrailOrToolEnvelope(envelope)

    val toolItem = items.single() as TrailYamlItem.ToolTrailItem
    val decoded = toolItem.tools.single()
    assertEquals("clientDefinedTool", decoded.name)
    val raw = (decoded.trailblazeTool as OtherTrailblazeTool).raw
    assertEquals("Home", (raw["text"] as JsonPrimitive).content)
  }

  @Test
  fun `a multi-tool envelope round-trips every tool in order`() {
    val envelope =
      yaml.encodeTools(
        listOf(
          toolWrapper("clientToolA"),
          toolWrapper("clientToolB", mapOf("text" to "Next")),
        ),
      )

    val toolItem = yaml.decodeTrailOrToolEnvelope(envelope).single() as TrailYamlItem.ToolTrailItem
    assertEquals(listOf("clientToolA", "clientToolB"), toolItem.tools.map { it.name })
  }

  @Test
  fun `a unified trail document is NOT mis-routed as a tool envelope`() {
    val unified =
      """
      config:
        id: example-app/suite_1/section_1/case_1
        title: A trail
      trail:
        - step: Open the app
      """
        .trimIndent()

    val items = yaml.decodeTrailOrToolEnvelope(unified)

    // A trail document falls through to decodeTrail — it must contain the prompt step, never a
    // single ToolTrailItem swallowing the whole document.
    assertTrue(
      items.any { it is TrailYamlItem.PromptsTrailItem },
      "Unified trail must decode as a trail (prompts), not a tool envelope: $items",
    )
  }

  @Test
  fun `a unified trail with config and multiple steps is NOT mis-routed as a tool envelope`() {
    // A unified mapping root is never a bare tool-wrapper list, so it stays on the trail path even
    // though a naive tool decode would (wrongly) read `config`/`trail` as tool names.
    val unified =
      """
      config:
        id: example-app/suite_1/section_1/case_1
        title: A trail
      trail:
        - step: Open the app
        - step: Tap continue
      """
        .trimIndent()

    val items = yaml.decodeTrailOrToolEnvelope(unified)

    assertTrue(
      items.any { it is TrailYamlItem.PromptsTrailItem },
      "Unified trail must decode as a trail, not a tool envelope: $items",
    )
  }

  @Test
  fun `a list item that is a multi-key map is NOT treated as a tool envelope`() {
    // A genuine tool wrapper is always a single-entry `<toolName>: <args>` map. A multi-key list
    // item must NOT be routed to decodeTools (which reads only the first entry and would silently
    // drop the rest); it falls through to the trail parser instead.
    val multiKey =
      """
      - firstKey: a
        secondKey: b
      """
        .trimIndent()

    // Falls through to decodeTrail, which does not recognize this shape as a trail item → throws.
    // The contract under test is only that it is NOT decoded as a (data-dropping) tool envelope.
    val decodedAsEnvelope =
      try {
        val items = yaml.decodeTrailOrToolEnvelope(multiKey)
        items.singleOrNull() is TrailYamlItem.ToolTrailItem
      } catch (_: Throwable) {
        false
      }
    assertTrue(!decodedAsEnvelope, "A multi-key map item must not be decoded as a tool envelope")
  }

  @Test
  fun `a list of empty maps is NOT treated as a tool envelope`() {
    // An empty map (`- {}`) has no tool-name key; it must not be mis-classified as an envelope
    // (the reserved-key check on a null first-key previously let it through).
    val emptyMaps = "- {}"

    val decodedAsEnvelope =
      try {
        val items = yaml.decodeTrailOrToolEnvelope(emptyMaps)
        items.singleOrNull() is TrailYamlItem.ToolTrailItem
      } catch (_: Throwable) {
        false
      }
    assertTrue(!decodedAsEnvelope, "An empty-map list item must not be decoded as a tool envelope")
  }

  @Test
  fun `a legacy v1 trail list is rejected, not silently decoded as a tool envelope`() {
    // A v1 document and a bare tool envelope are both YAML lists, and this decoder is the one place
    // that has to tell them apart. Every v1 root key is reserved, so `isBareToolEnvelope` declines
    // and the document falls through to the trail parser, which no longer accepts a list root. The
    // edge shapes above pin the discriminator's parts; this pins the whole v1 shape end to end, so
    // a v1 file can never be quietly re-read as a pile of tools named `config` / `prompts`.
    val v1 =
      """
      - config:
          id: example-app/suite_1/section_1/case_1
      - prompts:
        - step: Open the app
      """
        .trimIndent()

    val failure = assertFailsWith<IllegalArgumentException> { yaml.decodeTrailOrToolEnvelope(v1) }

    assertTrue(
      failure.message.orEmpty().contains("root must be a mapping"),
      "expected the v1 list to be rejected for its non-mapping root, got: ${failure.message}",
    )
  }

  @Test
  fun `encodeTools rejects a tool whose name collides with a reserved trail-item key`() {
    // The envelope-vs-trail discrimination is structural (single-entry, non-reserved key). A tool
    // named e.g. `config` would encode to `- config:` and be silently mis-read as a ConfigTrailItem
    // on decode. encodeTools must fail loud rather than emit that ambiguous shape.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.encodeTools(listOf(toolWrapper("config", mapOf("id" to "x"))))
    }
    assertTrue(
      ex.message?.contains("reserved trail-item keys") == true,
      "expected reserved-key rejection, got: ${ex.message}",
    )
  }

  @Test
  fun `a bare tool envelope decodes to a recorded step (hasRecordedSteps is true)`() {
    // Regression guard for the on-device SessionStarted `hasRecordedSteps` flag: the host-drives-loop
    // path sends a bare envelope on the RPC `yaml` field. It must decode (via decodeTrailOrToolEnvelope)
    // to a ToolTrailItem, for which hasRecordedSteps is true — so single-tool dispatch is NOT
    // mislabeled as agent-driven. Plain decodeTrail throws on this shape, which is exactly why the
    // handler must use the envelope-aware decoder.
    val envelope = yaml.encodeTools(listOf(toolWrapper("clientDefinedTool")))

    assertTrue(
      yaml.hasRecordedSteps(yaml.decodeTrailOrToolEnvelope(envelope)),
      "A bare tool envelope must count as a recorded step",
    )
    assertFailsWith<Throwable> { yaml.decodeTrail(envelope) }
  }
}
