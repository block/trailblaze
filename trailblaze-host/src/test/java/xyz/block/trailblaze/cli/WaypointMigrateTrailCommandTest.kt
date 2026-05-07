package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.VerificationStep

/**
 * Tests for [WaypointMigrateTrailCommand]'s helper functions — the pieces that drive the
 * deterministic Maestro→accessibility selector migration's tool-to-snapshot pairing.
 *
 * The full `call()` flow needs real session-log JSON files with both view-hierarchy trees
 * populated, which is heavy to fixture; the helpers below cover the cleverest pieces in
 * isolation:
 *
 *  - [WaypointMigrateTrailCommand.collectMaestroSelectors] — walks a parsed trail tree and
 *    pulls every `tapOnElementBySelector` / `assertVisibleBySelector` tool's
 *    `TrailblazeElementSelector` in YAML document order. The order it produces is the
 *    pairing key used against session logs in pass 2 — wrong order = wrong migrations.
 *  - [WaypointMigrateTrailCommand.classNameFromYamlToolName] — maps a YAML tool name to the
 *    runtime class name the per-tool snapshot hook embeds in `displayName`. Hardcoded
 *    `when` table; this test locks the table in so a rename doesn't silently break pairing.
 *  - [WaypointMigrateTrailCommand.logHasBothTrees] — peek-only filter for usable logs (must
 *    have both `viewHierarchy` and `trailblazeNodeTree` keys).
 *  - [WaypointMigrateTrailCommand.readDisplayName] — reads the snapshot log's `displayName`
 *    via regex; powers the first-tier "preTool: ClassName" pairing.
 *  - [WaypointMigrateTrailCommand.listSnapshotLogs] — combined filter + sort that decides
 *    which files in a session dir are even considered.
 *
 * The full pairing logic (two-tier match + cursor) sits inside `call()` and exercises real
 * matchers against captured trees — that's an integration concern, not a unit test target.
 */
class WaypointMigrateTrailCommandTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
  }

  // ----- collectMaestroSelectors --------------------------------------------------

  @Test
  fun `collectMaestroSelectors emits zero entries on a trail with no selector tools`() {
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "do something custom",
            recording = ToolRecording(
              tools = listOf(wrap("customTool", FakeNonSelectorTool)),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertTrue(result.isEmpty(), "Expected no entries; got: $result")
  }

  @Test
  fun `collectMaestroSelectors emits tapOnElementBySelector entries in YAML order`() {
    val cmd = WaypointMigrateTrailCommand()
    val foo = TrailblazeElementSelector(textRegex = "Foo")
    val bar = TrailblazeElementSelector(textRegex = "Bar")
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "tap foo",
            recording = ToolRecording(
              tools = listOf(
                wrap("tapOnElementBySelector", TapOnByElementSelector(selector = foo)),
              ),
            ),
          ),
          DirectionStep(
            step = "tap bar",
            recording = ToolRecording(
              tools = listOf(
                wrap("tapOnElementBySelector", TapOnByElementSelector(selector = bar)),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(2, result.size)
    assertEquals("Foo", result[0].maestroSelector.textRegex)
    assertEquals("tapOnElementBySelector", result[0].toolName)
    assertEquals("Bar", result[1].maestroSelector.textRegex)
  }

  @Test
  fun `collectMaestroSelectors mixes tapOnElementBySelector and assertVisibleBySelector in document order`() {
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "tap then assert",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(selector = TrailblazeElementSelector(textRegex = "T1")),
                ),
                wrap(
                  "assertVisibleBySelector",
                  AssertVisibleBySelectorTrailblazeTool(
                    selector = TrailblazeElementSelector(textRegex = "A1"),
                  ),
                ),
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(selector = TrailblazeElementSelector(textRegex = "T2")),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(3, result.size)
    assertEquals(listOf("T1", "A1", "T2"), result.map { it.maestroSelector.textRegex })
    assertEquals(
      listOf("tapOnElementBySelector", "assertVisibleBySelector", "tapOnElementBySelector"),
      result.map { it.toolName },
    )
  }

  @Test
  fun `collectMaestroSelectors skips TapOnByElementSelector entries with null selector`() {
    // The TapOnByElementSelector class allows `selector = null` for accessibility-only
    // recordings post-cutover (only `nodeSelector` is set). The migration is a no-op for
    // those — they're already migrated. Confirm collect drops them so the pairing index
    // stays in sync with what's actually migrate-able.
    val cmd = WaypointMigrateTrailCommand()
    val items = listOf(
      TrailYamlItem.PromptsTrailItem(
        promptSteps = listOf(
          DirectionStep(
            step = "two taps, one already migrated",
            recording = ToolRecording(
              tools = listOf(
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(selector = null),
                ),
                wrap(
                  "tapOnElementBySelector",
                  TapOnByElementSelector(
                    selector = TrailblazeElementSelector(textRegex = "Pending"),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val result = cmd.collectMaestroSelectors(items)
    assertEquals(1, result.size)
    assertEquals("Pending", result[0].maestroSelector.textRegex)
  }

  // ----- classNameFromYamlToolName ------------------------------------------------

  @Test
  fun `classNameFromYamlToolName maps known tool names to runtime class names`() {
    val cmd = WaypointMigrateTrailCommand()
    // The hook in [AndroidTrailblazeRule] / [TrailblazeHostYamlRunner] writes the runtime
    // class name into the snapshot's `displayName` as `preTool: <ClassName>`. The mapping
    // must round-trip with [TapOnByElementSelector::class.simpleName] etc. exactly,
    // otherwise first-tier pairing silently misses every snapshot.
    assertEquals(
      TapOnByElementSelector::class.simpleName,
      cmd.classNameFromYamlToolName("tapOnElementBySelector"),
    )
    assertEquals(
      AssertVisibleBySelectorTrailblazeTool::class.simpleName,
      cmd.classNameFromYamlToolName("assertVisibleBySelector"),
    )
  }

  @Test
  fun `classNameFromYamlToolName falls through to input for unknown tool names`() {
    // Defensive default — doesn't crash on a tool the migration doesn't know about.
    // Pairing won't find a matching `preTool: <unknown>` snapshot, so fallback cursor
    // takes over. Confirm the no-crash contract.
    val cmd = WaypointMigrateTrailCommand()
    assertEquals("someOtherTool", cmd.classNameFromYamlToolName("someOtherTool"))
  }

  // ----- logHasBothTrees ----------------------------------------------------------

  @Test
  fun `logHasBothTrees requires viewHierarchy + trailblazeNodeTree + driverMigrationTreeNode`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }

    // The "all three" file represents a migration-mode capture — viewHierarchy is the
    // UiAutomator XML, trailblazeNodeTree is the canonical driver tree, and
    // driverMigrationTreeNode is the side-channel accessibility tree. Only this shape
    // is safe for migrate-trail to feed into TapSelectorV2.
    val all = File(dir, "all.json").apply {
      writeText(
        """{"viewHierarchy": {}, "trailblazeNodeTree": {}, "driverMigrationTreeNode": {}}""",
      )
    }
    // Pre-migration captures (or non-migration-mode runs) lack driverMigrationTreeNode.
    // They can't be safely consumed because their viewHierarchy could be the
    // accessibility-projected shape — gating prevents wrong-coordinate matches.
    val noMigTree = File(dir, "no-mig.json").apply {
      writeText("""{"viewHierarchy": {}, "trailblazeNodeTree": {}}""")
    }
    val onlyVh = File(dir, "vh-only.json").apply {
      writeText("""{"viewHierarchy": {}}""")
    }
    val onlyTnt = File(dir, "tnt-only.json").apply {
      writeText("""{"trailblazeNodeTree": {}}""")
    }
    val empty = File(dir, "empty.json").apply { writeText("{}") }

    assertTrue(cmd.logHasBothTrees(all))
    assertFalse(cmd.logHasBothTrees(noMigTree))
    assertFalse(cmd.logHasBothTrees(onlyVh))
    assertFalse(cmd.logHasBothTrees(onlyTnt))
    assertFalse(cmd.logHasBothTrees(empty))
  }

  // ----- readDisplayName ----------------------------------------------------------

  @Test
  fun `readDisplayName extracts displayName from a snapshot log`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    val log = File(dir, "010_TrailblazeSnapshotLog.json").apply {
      writeText(
        """{"class":"...","displayName":"preTool: TapOnByElementSelector","viewHierarchy":{}}""",
      )
    }
    assertEquals("preTool: TapOnByElementSelector", cmd.readDisplayName(log))
  }

  @Test
  fun `readDisplayName returns null when displayName is absent`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }
    val log = File(dir, "001_TrailblazeLlmRequestLog.json").apply {
      writeText("""{"class":"...","viewHierarchy":{}}""")
    }
    assertNull(cmd.readDisplayName(log))
  }

  // ----- listSnapshotLogs ---------------------------------------------------------

  @Test
  fun `listSnapshotLogs returns LlmRequestLog and SnapshotLog files in name-sorted order, filtered to dual-tree`() {
    val cmd = WaypointMigrateTrailCommand()
    val dir = createTempDirectory("migrate-trail-test").toFile().also { tempDirs += it }

    // `good` represents migration-mode captures — all three required keys present.
    // `noMigTree` represents non-migration captures — gated out by [logHasBothTrees].
    // `bad` is missing trailblazeNodeTree entirely (also gated out).
    val good = """{"viewHierarchy":{},"trailblazeNodeTree":{},"driverMigrationTreeNode":{}}"""
    val noMigTree = """{"viewHierarchy":{},"trailblazeNodeTree":{}}"""
    val bad = """{"viewHierarchy":{}}"""
    val unrelatedShape = good

    File(dir, "001_TrailblazeSessionStatusChangeLog.json").writeText(unrelatedShape) // wrong type
    val s1 = File(dir, "008_TrailblazeLlmRequestLog.json").apply { writeText(good) }
    val s2 = File(dir, "010_TrailblazeSnapshotLog.json").apply { writeText(good) }
    File(dir, "012_TrailblazeLlmRequestLog.json").writeText(bad) // missing trailblazeNodeTree
    File(dir, "013_TrailblazeLlmRequestLog.json").writeText(noMigTree) // pre-migration capture, gated out
    val s3 = File(dir, "015_TrailblazeSnapshotLog.json").apply { writeText(good) }

    val result = cmd.listSnapshotLogs(dir)
    // Only the two file types that match the suffix AND have all three required keys, sorted by name.
    assertEquals(listOf(s1.name, s2.name, s3.name), result.map { it.name })
  }

  // ----- helpers ------------------------------------------------------------------

  private fun wrap(name: String, tool: xyz.block.trailblaze.toolcalls.TrailblazeTool) =
    TrailblazeToolYamlWrapper(name = name, trailblazeTool = tool)

  private object FakeNonSelectorTool : xyz.block.trailblaze.toolcalls.TrailblazeTool
}
