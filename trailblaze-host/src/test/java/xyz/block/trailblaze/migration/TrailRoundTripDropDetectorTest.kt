package xyz.block.trailblaze.migration

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Unit tests for [TrailRoundTripDropDetector]: the pure [TrailRoundTripDropDetector.pruneKeyByLocation]
 * tree surgery in isolation (plain parsed nodes, no strict-decode loop), and [TrailRoundTripDropDetector.detect]'s
 * observable contract (what it reports for a given input). The migrator-level round-trip is covered
 * end-to-end in [UnifiedTrailMigratorTest].
 */
class TrailRoundTripDropDetectorTest {

  private val strictYaml = createTrailblazeYaml(strict = true)

  private fun detect(text: String) =
    TrailRoundTripDropDetector.detect(strictYaml, "test.trail.yaml", text)

  @Test
  fun `pruneKeyByLocation drops only the entry at the given location, leaving a same-named key elsewhere`() {
    // Two `dup` keys live in different nested maps. Pruning by the FIRST one's exact source location
    // must remove only it — the identically-named key under `other` is a different location and must
    // survive. This is the precision the location match buys over a name-only prune.
    val root = Yaml.default.parseToYamlNode(
      """
      outer:
        dup: 1
      other:
        dup: 2
      """.trimIndent(),
    ) as YamlMap

    val outerDupLocation = root.get<YamlMap>("outer")!!.getKey("dup")!!.location
    val pruned = TrailRoundTripDropDetector.pruneKeyByLocation(root, "dup", outerDupLocation) as YamlMap

    assertNull(pruned.get<YamlMap>("outer")!!.getScalar("dup"), "the targeted `dup` should be gone")
    assertNotNull(
      pruned.get<YamlMap>("other")!!.getScalar("dup"),
      "a same-named key at a different location must be untouched",
    )
  }

  @Test
  fun `pruneKeyByLocation recurses through lists`() {
    // The unknown key can be nested inside a list element (recorded tool calls are a `tools:` list),
    // so the prune must descend into list items, again targeting only the matched location.
    val root = Yaml.default.parseToYamlNode(
      """
      items:
        - name: a
          bad: 1
        - name: b
          bad: 2
      """.trimIndent(),
    ) as YamlMap

    val firstItem = root.get<YamlList>("items")!!.items[0] as YamlMap
    val firstBadLocation = firstItem.getKey("bad")!!.location
    val pruned = TrailRoundTripDropDetector.pruneKeyByLocation(root, "bad", firstBadLocation) as YamlMap

    val prunedItems = pruned.get<YamlList>("items")!!
    assertNull((prunedItems.items[0] as YamlMap).getScalar("bad"), "the first item's `bad` should be gone")
    assertNotNull(
      (prunedItems.items[1] as YamlMap).getScalar("bad"),
      "the second item's `bad` (different location) must survive",
    )
  }

  @Test
  fun `detect enumerates every unknown key in a single tool, not just the first`() {
    // kaml aborts on the FIRST unknown key, so the detector's value is the prune-and-retry loop that
    // keeps going. Two malformed tool-level anchors (`below` + `above`, siblings of `nodeSelector`,
    // which the tool has no field for) must BOTH be reported from one decode pass.
    val dropped = detect(
      """
      - config: {id: x/y, target: x, platform: android}
      - prompts:
        - step: Verify the totals block
          recording:
            tools:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Total
                below:
                  androidAccessibility:
                    textRegex: Subtotal
                above:
                  androidAccessibility:
                    textRegex: Header
      """.trimIndent(),
    )

    // detect() orders by source line, so the two anchors come back in document order.
    assertEquals(
      listOf("below", "above"),
      dropped.map { it.key },
      "both malformed sibling anchors must be enumerated in source order, got: $dropped",
    )
    assertTrue(dropped.all { it.file == "test.trail.yaml" }, "each entry names the source file")
    assertTrue(dropped.all { it.line > 0 }, "each entry carries a source line, got: $dropped")
  }

  @Test
  fun `detect flags every extra sibling key in a single tool-list item, not just the first`() {
    // Mechanism 2, plural case: one `tools:` item map carries the tool PLUS two dedented anchors
    // (`below` + `above`). The wrapper serializer keeps only the first entry, so BOTH siblings are
    // dropped with no decode error — the structural pre-scan must enumerate every one of them.
    val dropped = detect(
      """
      - config: {id: x/y, target: x, platform: android}
      - prompts:
        - step: Verify the total row
          recording:
            tools:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Total
              below:
                androidAccessibility:
                  textRegex: Subtotal
              above:
                androidAccessibility:
                  textRegex: Header
      """.trimIndent(),
    )

    assertEquals(
      listOf("below", "above"),
      dropped.map { it.key },
      "every item-level sibling must be reported, got: $dropped",
    )
    assertTrue(
      dropped.all { it.path.endsWith("tools[0].${it.key}") },
      "each sibling is reported directly under the tools item, got: ${dropped.map { it.path }}",
    )
  }

  @Test
  fun `detect flags a sibling key wrongly dedented out of a tool's args, which strict decode cannot see`() {
    // The `tools:` list item is a map with TWO keys — the tool (`assertVisibleBySelector`) and a
    // sibling `below` at the item level (an anchor the author meant to nest under the selector, but
    // dedented one level). TrailblazeToolYamlWrapperSerializer decodes only the first entry, so
    // `below` is silently dropped AND strict decode never sees it — only the structural pre-scan
    // catches this. The reported path proves it: `...tools[0].below` (a direct child of the item),
    // NOT `...tools[0].assertVisibleBySelector.below` (which is the schema-unknown-key mechanism).
    val dropped = detect(
      """
      - config: {id: x/y, target: x, platform: android}
      - prompts:
        - step: Verify the total row
          recording:
            tools:
            - assertVisibleBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Total
              below:
                androidAccessibility:
                  textRegex: Subtotal
      """.trimIndent(),
    )

    val entry = dropped.single()
    assertEquals("below", entry.key)
    assertTrue(
      entry.path.endsWith("tools[0].below"),
      "the sibling key must be reported directly under the tools item, got: ${entry.path}",
    )
  }

  @Test
  fun `detect returns empty for input that is not a decodable v1 trail, rather than throwing`() {
    // The detector is best-effort by contract: a file that isn't a v1 trail list (here a bare map)
    // fails strict decode with something OTHER than an unknown-key error, which must degrade to "no
    // drops to report" — never propagate and break a migration.
    assertTrue(detect("hello: world").isEmpty())
  }

  @Test
  fun `detect reports nothing for a fully schema-valid trail`() {
    val dropped = detect(
      """
      - config: {id: x/y, target: x, platform: android}
      - prompts:
        - step: Open the app
          recording:
            tools:
            - tapOnPoint: {x: 1, y: 2}
      """.trimIndent(),
    )
    assertTrue(dropped.isEmpty(), "a clean trail must produce no dropped-content entries, got: $dropped")
  }
}
