package xyz.block.trailblaze.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.Clock
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins behavior of [InteractionRecorder.replaceInteractionTool] — the picker swap path.
 *
 * Two invariants matter:
 *  1. The interaction list AND the parallel log list are both updated atomically. The log
 *     entry is what feeds [generateRecordedYaml] (and therefore the trail YAML), so a swap
 *     that only updated `_interactions` would leave the saved YAML wrong.
 *  2. An unknown interaction (one already removed, or never recorded) is a quiet no-op.
 *     The picker UI lives in a Compose state stream so this race is real — the user can
 *     click "swap" on a card the recorder no longer knows about.
 */
class InteractionRecorderTest {

  private val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  private data class FakeTool(val tag: String) : TrailblazeTool

  private fun newRecorder(): InteractionRecorder = InteractionRecorder(
    logEmitter = LogEmitter { /* no-op */ },
    session = TrailblazeSession(sessionId = SessionId("test"), startTime = Clock.System.now()),
    scope = testScope,
    toolFactory = TestToolFactory(),
  )

  @Test
  fun `replaceInteractionTool updates both the interaction list and the log`() {
    val recorder = newRecorder()
    recorder.startRecording()
    recorder.buffer.onTap(node = null, x = 10, y = 20, screenshot = null, hierarchyText = null)
    val original = recorder.interactions.single()

    val newTool = FakeTool("replaced")
    recorder.replaceInteractionTool(original, newTool, "replacedToolName")

    val updated = recorder.interactions.single()
    assertSame(newTool, updated.tool)
    assertEquals("replacedToolName", updated.toolName)
    // Identity-preserving fields stay intact:
    assertEquals(original.timestamp, updated.timestamp)
    assertEquals(original.screenshotBytes, updated.screenshotBytes)

    // The underlying log was rewritten so generateRecordedYaml will pick up the new tool.
    val yaml = recorder.generateTrailYaml()
    // The fake tool serializes with toLogPayload; we just need to confirm the new tool name
    // appears (and the old one is gone). YAML formatting can vary; substring check is enough.
    assertEquals(true, yaml.contains("replacedToolName"), "yaml should contain new tool name: $yaml")
  }

  @Test
  fun `replaceInteractionTool is a no-op when interaction is not recorded`() {
    val recorder = newRecorder()
    recorder.startRecording()
    recorder.buffer.onTap(node = null, x = 1, y = 2, screenshot = null, hierarchyText = null)

    // Construct an interaction that was never recorded — different timestamp, different tool.
    val unknown = RecordedInteraction(
      tool = FakeTool("ghost"),
      toolName = "ghost",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = -1L,
    )
    val before = recorder.interactions

    recorder.replaceInteractionTool(unknown, FakeTool("new"), "newName")

    // No changes to the recorded list.
    assertEquals(before, recorder.interactions)
  }

  // -- insertInteraction --

  /**
   * Pins the position semantics the Tool Palette's "+ Insert" between-cards button relies on:
   * a position-N insertion lands at exactly index N, and the existing card at index N shifts
   * to N+1. Append (position = null) lands at the tail.
   */
  @Test
  fun `insertInteraction at a specific position splices in place`() {
    val recorder = newRecorder()
    recorder.startRecording()
    recorder.buffer.onTap(node = null, x = 1, y = 1, screenshot = null, hierarchyText = null)
    recorder.buffer.onTap(node = null, x = 2, y = 2, screenshot = null, hierarchyText = null)
    val originalSize = recorder.interactions.size

    val inserted = RecordedInteraction(
      tool = FakeTool("inserted"),
      toolName = "inserted",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = 999L,
    )
    recorder.insertInteraction(inserted, position = 1)

    val now = recorder.interactions
    assertEquals(originalSize + 1, now.size)
    assertEquals("inserted", now[1].toolName)
    // The inserted tool's log MUST land in the parallel logs list at the same position so
    // generateTrailYaml emits it in order; without that, mid-list inserts would silently
    // surface at the end of the saved YAML.
    val yaml = recorder.generateTrailYaml()
    val tapIndex = yaml.indexOf("tap")
    val insertedIndex = yaml.indexOf("inserted")
    val trailingTapIndex = yaml.lastIndexOf("tap")
    assertTrue(tapIndex >= 0 && insertedIndex > tapIndex && trailingTapIndex > insertedIndex,
      "tap → inserted → tap order in YAML; got: $yaml")
  }

  @Test
  fun `insertInteraction with null position appends`() {
    val recorder = newRecorder()
    recorder.startRecording()
    recorder.buffer.onTap(node = null, x = 1, y = 1, screenshot = null, hierarchyText = null)

    val appended = RecordedInteraction(
      tool = FakeTool("appended"),
      toolName = "appended",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = 999L,
    )
    recorder.insertInteraction(appended, position = null)

    assertEquals("appended", recorder.interactions.last().toolName)
  }

  /**
   * Out-of-range positions clamp rather than throw. The UI feeds positions derived from
   * `recordedActions.size`, which can race against a concurrent delete — clamping keeps the
   * insert from blowing up the user's session over a stale index.
   */
  @Test
  fun `insertInteraction clamps out-of-range position to the end`() {
    val recorder = newRecorder()
    recorder.startRecording()
    recorder.buffer.onTap(node = null, x = 1, y = 1, screenshot = null, hierarchyText = null)

    val inserted = RecordedInteraction(
      tool = FakeTool("clamped"),
      toolName = "clamped",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = 999L,
    )
    recorder.insertInteraction(inserted, position = 9999)
    assertEquals("clamped", recorder.interactions.last().toolName)
  }

  /**
   * `insertInteraction` deliberately doesn't gate on `isRecording` — the Tool Palette is
   * available after Stop so the author can compose steps without restarting the recorder.
   * This test pins that behavior; flipping it would silently make the palette's Insert button
   * a no-op for stopped sessions, and that's a regression worth catching at the test layer
   * rather than a confused user.
   */
  @Test
  fun `insertInteraction works while not recording`() {
    val recorder = newRecorder()
    val standalone = RecordedInteraction(
      tool = FakeTool("standalone"),
      toolName = "standalone",
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = 1L,
    )
    recorder.insertInteraction(standalone)

    assertEquals(1, recorder.interactions.size)
    assertEquals("standalone", recorder.interactions.single().toolName)
  }

  // -- interactionsToTrailYaml --

  @Test
  fun `interactionsToTrailYaml returns empty string for empty input`() {
    assertEquals("", InteractionRecorder.interactionsToTrailYaml(emptyList()))
  }

  /**
   * The replay-from-here path calls this with the tail of the recorded list. Encoding goes
   * through registered tool serializers (the wrapper-encoded path), so this test uses real
   * tools — `FakeTool` has no @Contextual serializer registered and would fail to encode.
   * That's the same pitfall callers will hit in production: only registered TrailblazeTools
   * survive the round-trip; unknown types must go through the log/payload path instead.
   */
  @Test
  fun `interactionsToTrailYaml round-trips a multi-tool slice through decode`() {
    val tap = realInteraction(TapOnPointTrailblazeTool(x = 10, y = 20), timestamp = 1L)
    val type = realInteraction(InputTextTrailblazeTool(text = "hi"), timestamp = 2L)

    val yaml = InteractionRecorder.interactionsToTrailYaml(listOf(tap, type))
    // Both tools must appear in order. Exact YAML formatting can vary; substring checks are
    // enough to catch a slice that lost an item or got reordered.
    val tapIndex = yaml.indexOf("tapOnPoint")
    val inputIndex = yaml.indexOf("inputText")
    assertTrue(tapIndex >= 0 && inputIndex > tapIndex, "expected tapOnPoint then inputText in: $yaml")
  }

  // -- decodeSingleToolYaml --

  /**
   * `decodeSingleToolYaml` is shared by the in-card YAML editor and the Tool Palette dialog
   * (via the host-side `interactionFromSingleToolYaml`). Both feed bare single-tool YAML
   * (`name:\n  field: value`) and need it parsed identically.
   *
   * Round-tripping through `singleToolToYaml` → `decodeSingleToolYaml` exercises the most
   * common shape and confirms the wrap-as-list-item indent rule matches what the encoder
   * produces — without that match, the in-card editor's "edit then save" path would parse
   * what it just rendered as malformed YAML.
   */
  @Test
  fun `decodeSingleToolYaml round-trips singleToolToYaml output`() {
    val interaction = realInteraction(TapOnPointTrailblazeTool(x = 5, y = 6), timestamp = 1L)

    val yaml = InteractionRecorder.singleToolToYaml(interaction)
    val wrapper = InteractionRecorder.decodeSingleToolYaml(yaml)

    assertEquals(interaction.toolName, wrapper.name)
    val decoded = wrapper.trailblazeTool as TapOnPointTrailblazeTool
    assertEquals(5, decoded.x)
    assertEquals(6, decoded.y)
  }

  // -- interactionsToTrailYamlWithTrailhead --

  /**
   * Pins the shape "Verify Trail" sends to `runYaml` — trailhead first, recorded interactions
   * after, all in one tools block. The runner replays them in order, so the trailhead resets
   * the device to a known state before the recorded steps execute.
   */
  @Test
  fun `interactionsToTrailYamlWithTrailhead splices trailhead id at top of tools list`() {
    val tap = realInteraction(TapOnPointTrailblazeTool(x = 10, y = 20), timestamp = 1L)
    val type = realInteraction(InputTextTrailblazeTool(text = "hi"), timestamp = 2L)

    val yaml = RecordingYamlCodec.interactionsToTrailYamlWithTrailhead(
      trailheadToolId = "sample_launchAppSignedIn",
      interactions = listOf(tap, type),
    )

    val trailheadIndex = yaml.indexOf("sample_launchAppSignedIn")
    val tapIndex = yaml.indexOf("tapOnPoint")
    val inputIndex = yaml.indexOf("inputText")
    assertTrue(
      trailheadIndex in 0 until tapIndex && tapIndex < inputIndex,
      "expected trailhead → tapOnPoint → inputText order in: $yaml",
    )
  }

  @Test
  fun `interactionsToTrailYamlWithTrailhead with empty interactions emits trailhead-only trail`() {
    val yaml = RecordingYamlCodec.interactionsToTrailYamlWithTrailhead(
      trailheadToolId = "sample_launchAppSignedIn",
      interactions = emptyList(),
    )
    // No interactions → just the trailhead in a single tools block. Useful for "I picked a
    // trailhead but recorded nothing — verify the trailhead alone gets me to the destination."
    assertTrue(yaml.contains("- tools:") && yaml.contains("sample_launchAppSignedIn"))
  }

  /**
   * Constructs a [RecordedInteraction] holding a real, registered [TrailblazeTool] so the
   * caller can exercise wrapper-encoded paths (`singleToolToYaml`, `interactionsToTrailYaml`,
   * `decodeSingleToolYaml`). Distinct from the [FakeTool]-based fixtures used elsewhere in
   * this file: those go through the log/payload encoding which tolerates unregistered tools,
   * but the wrapper-encoded paths do not.
   */
  private fun realInteraction(tool: TrailblazeTool, timestamp: Long): RecordedInteraction =
    RecordedInteraction(
      tool = tool,
      toolName = tool::class.toolName().toolName,
      screenshotBytes = null,
      viewHierarchyText = null,
      timestamp = timestamp,
    )
}

private class TestToolFactory : InteractionToolFactory {
  override fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode?,
  ): Pair<TrailblazeTool, String> = TestTool("tap($x,$y)") to "tap"

  override fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode?,
  ): Pair<TrailblazeTool, String> = TestTool("longPress") to "longPress"

  override fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long?,
  ): Pair<TrailblazeTool, String> = TestTool("swipe") to "swipe"

  override fun createInputTextTool(text: String): Pair<TrailblazeTool, String> =
    TestTool("inputText($text)") to "inputText"

  override fun createPressKeyTool(key: String): Pair<TrailblazeTool, String> =
    TestTool("pressKey($key)") to "pressKey"
}

private data class TestTool(val description: String) : TrailblazeTool
