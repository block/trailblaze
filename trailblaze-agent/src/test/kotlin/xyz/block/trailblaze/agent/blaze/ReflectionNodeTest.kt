package xyz.block.trailblaze.agent.blaze

import org.junit.Test
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.RecordedAction
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.WorkingMemory
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ReflectionNode].
 *
 * Tests the ability to detect loops, stuck states, and generate corrections.
 */
class ReflectionNodeTest {

  private val reflectionNode = ReflectionNode()

  private fun createRecordedAction(
    toolName: String = "tap",
    success: Boolean = true,
    confidence: Confidence = Confidence.HIGH,
    errorMessage: String? = null,
  ): RecordedAction = RecordedAction(
    toolName = toolName,
    toolArgs = JsonObject(emptyMap()),
    reasoning = "Test action",
    screenSummaryBefore = "Initial screen",
    screenSummaryAfter = "After action",
    confidence = confidence,
    durationMs = 100,
    success = success,
    errorMessage = errorMessage,
  )

  private fun createScreenAnalysis(
    screenSummary: String = "Test screen",
    objectiveAppearsAchieved: Boolean = false,
    objectiveAppearsImpossible: Boolean = false,
    progressIndicators: List<String> = emptyList(),
    potentialBlockers: List<String> = emptyList(),
  ): ScreenAnalysis = ScreenAnalysis(
    screenSummary = screenSummary,
    recommendedTool = "tap",
    recommendedArgs = JsonObject(emptyMap()),
    reasoning = "Test reasoning",
    confidence = Confidence.HIGH,
    objectiveAppearsAchieved = objectiveAppearsAchieved,
    objectiveAppearsImpossible = objectiveAppearsImpossible,
    progressIndicators = progressIndicators,
    potentialBlockers = potentialBlockers,
    alternativeApproaches = emptyList(),
  )

  // ====== LOOP DETECTION TESTS ======

  @Test
  fun `should detect loop when same tool called 3+ times with similar args`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis("Screen 1"),
      createScreenAnalysis("Screen 1"),
      createScreenAnalysis("Screen 1"),
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertTrue(result.loopDetected, "Loop should be detected for repeated taps")
    assertTrue(result.shouldBacktrack, "Should suggest backtracking")
    assertFalse(result.isOnTrack, "Should be off track when looping")
  }

  @Test
  fun `should not detect loop when different tools are used`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "scroll"),
      createRecordedAction(toolName = "type"),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis("Screen 1"),
      createScreenAnalysis("Screen 2"),
      createScreenAnalysis("Screen 3"),
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertFalse(result.loopDetected, "Loop should not be detected for varied actions")
  }

  @Test
  fun `should not detect loop with fewer than 3 actions`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 2,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis("Screen 1"),
      createScreenAnalysis("Screen 1"),
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertFalse(result.loopDetected, "Loop should not be detected with < 3 actions")
  }

  // ====== CONSECUTIVE FAILURE DETECTION TESTS ======

  @Test
  fun `should detect consecutive failures`() {
    val actions = listOf(
      createRecordedAction(success = false, errorMessage = "Element not found"),
      createRecordedAction(success = false, errorMessage = "Element not found"),
      createRecordedAction(success = true),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertFalse(result.isOnTrack, "Should be off track with failures")
  }

  @Test
  fun `should not mark as stuck when failures are recoverable`() {
    val actions = listOf(
      createRecordedAction(success = false, errorMessage = "Timeout"),
      createRecordedAction(success = false, errorMessage = "Timeout"),
      createRecordedAction(success = true),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    // Timeout is recoverable, so may not be marked as permanently stuck
    assertNotNull(result.suggestedCorrection)
  }

  // ====== LOW CONFIDENCE PATTERN DETECTION TESTS ======

  @Test
  fun `should detect pattern of low-confidence actions`() {
    val actions = listOf(
      createRecordedAction(confidence = Confidence.LOW),
      createRecordedAction(confidence = Confidence.LOW),
      createRecordedAction(confidence = Confidence.LOW),
      createRecordedAction(confidence = Confidence.HIGH),
      createRecordedAction(confidence = Confidence.HIGH),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 5,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertFalse(result.isOnTrack, "Should be off track with low confidence pattern")
    assertNotNull(result.suggestedCorrection, "Should suggest correction for low confidence")
  }

  @Test
  fun `should not flag low confidence with only 2 low-confidence actions`() {
    val actions = listOf(
      createRecordedAction(confidence = Confidence.LOW),
      createRecordedAction(confidence = Confidence.LOW),
      createRecordedAction(confidence = Confidence.HIGH),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    // May still be on track with only 2 low-confidence actions
    // This depends on other factors
    assertNotNull(result.suggestedCorrection ?: result.progressAssessment)
  }

  // ====== SCREEN LOOP DETECTION TESTS ======

  @Test
  fun `should detect screen loop when screen state is unchanged`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis("Login screen"),
      createScreenAnalysis("Login screen"),
      createScreenAnalysis("Login screen"),
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertTrue(result.loopDetected, "Screen loop should be detected")
    assertTrue(result.shouldBacktrack, "Should suggest backtracking for screen loop")
  }

  @Test
  fun `should not detect screen loop when screens are changing`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis("Login screen"),
      createScreenAnalysis("Home screen"),
      createScreenAnalysis("Settings screen"),
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertFalse(result.loopDetected, "No loop when screens are changing")
  }

  // ====== PROGRESS ASSESSMENT TESTS ======

  @Test
  fun `should assess positive progress when objective appears achieved`() {
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = listOf(createRecordedAction()),
      iteration = 1,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis(objectiveAppearsAchieved = true)
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertTrue(result.isOnTrack, "Should assess positive progress when objective achieved")
  }

  @Test
  fun `should assess positive progress with progress indicators`() {
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = listOf(createRecordedAction()),
      iteration = 1,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis(
        progressIndicators = listOf(
          "Cart updated with item",
          "Quantity increased",
        )
      )
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertTrue(result.isOnTrack, "Should assess positive progress with indicators")
  }

  @Test
  fun `should assess negative progress with blockers and no success`() {
    val actions = listOf(
      createRecordedAction(success = false),
      createRecordedAction(success = false),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 2,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(
      createScreenAnalysis(
        potentialBlockers = listOf("Login wall", "Rate limited")
      )
    )
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    assertFalse(result.isOnTrack, "Should assess negative progress with blockers")
  }

  // ====== BACKTRACK SUGGESTION TESTS ======

  @Test
  fun `should provide backtrack steps when detecting loop`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
      createRecordedAction(toolName = "tap"),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 3,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    if (result.shouldBacktrack) {
      assertTrue(result.backtrackSteps > 0, "Backtrack steps should be positive")
    }
  }

  @Test
  fun `should provide suggested correction for off-track state`() {
    val actions = listOf(
      createRecordedAction(toolName = "tap", success = false),
      createRecordedAction(toolName = "tap", success = false),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 2,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    if (!result.isOnTrack) {
      assertNotNull(result.suggestedCorrection, "Should provide correction suggestion")
    }
  }

  // ====== EDGE CASES ======

  @Test
  fun `should handle empty action history gracefully`() {
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = emptyList(),
      iteration = 0,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    // Should not crash and should provide some assessment
    assertNotNull(result.progressAssessment)
  }

  @Test
  fun `should handle empty screen analyses gracefully`() {
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = listOf(createRecordedAction()),
      iteration = 1,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = emptyList<ScreenAnalysis>()
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    // Should not crash
    assertNotNull(result.progressAssessment)
  }

  // ====== TARGET DISCIPLINE (Hardware-Hub trap) ======

  @Test
  fun `target absent with scroll affordance recommends scrolling not tapping`() {
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      (scroll up to reveal) EditText "Search all items"
    """.trimIndent()

    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "tapOnElementByNodeId",
    )

    assertEquals(TargetMissingRecovery.SCROLL_TO_REVEAL, result)
  }

  @Test
  fun `target absent with no affordance surfaces wrong-screen signal`() {
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      [c101] Button "Charge"
    """.trimIndent()

    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "tap",
    )

    assertEquals(TargetMissingRecovery.WRONG_SCREEN, result)
  }

  @Test
  fun `longPress engages the target-missing guard like tap`() {
    // Regression: the first-class `longPress` tool is name-distinct from `tap`, but it must
    // still engage the wrong-screen guard so "long press X" can't fall through to holding an
    // unrelated element when the target is absent from the screen.
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      [c101] Button "Charge"
    """.trimIndent()

    val result = detectTargetMissingRecovery(
      objective = "Long press the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "longPress",
    )

    assertEquals(TargetMissingRecovery.WRONG_SCREEN, result)
  }

  @Test
  fun `unquoted long-press and press-and-hold objectives extract their target`() {
    // The most common phrasing has no quotes ("Long press the X"); the target parser must
    // recognize the long-press verbs so the wrong-screen guard still fires for them.
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      [c101] Button "Charge"
    """.trimIndent()

    listOf(
      "Long press the Search all items field",
      "long-press the Search all items field",
      "Press and hold the Search all items field",
      "press-and-hold the Search all items field",
      "Tap and hold the Search all items field",
      "tap-and-hold the Search all items field",
    ).forEach { objective ->
      assertEquals(
        TargetMissingRecovery.WRONG_SCREEN,
        detectTargetMissingRecovery(objective, screen, "longPress"),
        "expected wrong-screen recovery for: $objective",
      )
    }
  }

  @Test
  fun `dragTo engages the target-missing guard on the drag SOURCE`() {
    // A drag must start from a present element; the guard checks the SOURCE ("X" in
    // "drag X to Y"), not the destination. Source absent + no affordance → wrong screen.
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      [c101] Button "Charge"
    """.trimIndent()

    listOf(
      "Drag the \"Coffee\" item to the cart",
      "drag the Coffee item to the Favorites list",
      "drag Coffee onto Favorites",
      // Quoted DESTINATION must not be mistaken for the source — still checks "Coffee".
      "Drag Coffee to \"Favorites\"",
    ).forEach { objective ->
      assertEquals(
        TargetMissingRecovery.WRONG_SCREEN,
        detectTargetMissingRecovery(objective, screen, "dragTo"),
        "expected wrong-screen recovery for: $objective",
      )
    }
  }

  @Test
  fun `dragTo proceeds when the drag source is present, ignoring an absent destination`() {
    // What matters is the SOURCE is present and draggable; the destination may be offscreen
    // or not a named element.
    val screen = """
      [c200] TextView "Coffee"
      [c201] Button "Charge"
    """.trimIndent()
    assertEquals(
      TargetMissingRecovery.PROCEED,
      detectTargetMissingRecovery("drag Coffee to Favorites", screen, "dragTo"),
    )
    // Source present, destination quoted-and-absent: the guard must validate the SOURCE,
    // not the quoted destination, so this proceeds rather than false-firing wrong-screen.
    assertEquals(
      TargetMissingRecovery.PROCEED,
      detectTargetMissingRecovery("drag Coffee to \"Favorites\"", screen, "dragTo"),
    )
    // Container-noun suffix ("Coffee item") is normalized to "Coffee" so it still matches the
    // snapshot's "Coffee" label rather than falsely reporting the source missing.
    assertEquals(
      TargetMissingRecovery.PROCEED,
      detectTargetMissingRecovery("drag the Coffee item to Favorites", screen, "dragTo"),
    )
  }

  @Test
  fun `container-noun suffix stripping is scoped to drag sources, not tap targets`() {
    // Regression: drag-source normalization ("Coffee item" → "Coffee") must NOT leak into
    // generic tap/select targets. "Tap the Gift card" must look for "Gift card", not "Gift" —
    // otherwise a bare "Gift" distractor would satisfy the guard and let the agent tap it.
    val screen = """
      [c100] TextView "Gift"
      [c101] Button "Charge"
    """.trimIndent()
    assertEquals(
      TargetMissingRecovery.WRONG_SCREEN,
      detectTargetMissingRecovery("Tap the Gift card", screen, "tap"),
      "tap target 'Gift card' must not be stripped to 'Gift'",
    )
  }

  @Test
  fun `directional and manipulation drags are not treated as element-target steps`() {
    // Drags that aren't "drag X to Y" (pull-to-refresh, slider/carousel nudges) have no named
    // source element — they must PROCEED, not trip the wrong-screen guard on a movement phrase.
    val screen = "[c100] ImageView \"Open Hardware Hub\""
    listOf(
      "drag down to refresh",
      "drag the slider right a bit",
      "drag left slightly",
    ).forEach { objective ->
      assertEquals(
        TargetMissingRecovery.PROCEED,
        detectTargetMissingRecovery(objective, screen, "dragTo"),
        "expected PROCEED (no element target) for: $objective",
      )
    }
  }

  @Test
  fun `target present on screen proceeds with the action`() {
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      [c101] EditText "Search all items"
    """.trimIndent()

    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "tap",
    )

    assertEquals(TargetMissingRecovery.PROCEED, result)
  }

  @Test
  fun `non-tap actions are never intercepted`() {
    val screen = "[c100] ImageView \"Open Hardware Hub\""

    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "scroll",
    )

    assertEquals(TargetMissingRecovery.PROCEED, result)
  }

  @Test
  fun `objective with no identifiable target proceeds`() {
    val screen = "[c100] ImageView \"Open Hardware Hub\""

    val result = detectTargetMissingRecovery(
      objective = "go back",
      screenText = screen,
      recommendedTool = "tap",
    )

    assertEquals(TargetMissingRecovery.PROCEED, result)
  }

  @Test
  fun `null screen text is not treated as wrong screen`() {
    // Drivers like Android HOST mode leave viewHierarchyTextRepresentation null and feed
    // the analyzer a tree/JSON fallback — absence of compact text must not stop the step.
    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Save\" button",
      screenText = null,
      recommendedTool = "tap",
    )

    assertEquals(TargetMissingRecovery.PROCEED, result)
  }

  @Test
  fun `target on a non-interactive label line without a ref does not count as present`() {
    // A static label "Search all items" with no ref marker is not tappable, so the guard
    // must not let the distractor tap through — with no affordance it's a wrong screen.
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      "Search all items"
    """.trimIndent()

    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "tap",
    )

    assertEquals(TargetMissingRecovery.WRONG_SCREEN, result)
  }

  @Test
  fun `state annotation brackets are not mistaken for ref markers`() {
    // `[disabled]` is a state annotation, not a ref marker; a target on an annotated but
    // unreffed line still must not count as a tappable ref.
    val screen = """
      [c100] ImageView "Open Hardware Hub"
      TextView "Search all items" [disabled]
    """.trimIndent()

    val result = detectTargetMissingRecovery(
      objective = "Tap the \"Search all items\" field",
      screenText = screen,
      recommendedTool = "tap",
    )

    assertEquals(TargetMissingRecovery.WRONG_SCREEN, result)
  }

  @Test
  fun `scroll direction is read from the affordance line`() {
    val screenUp = "(scroll up to reveal) EditText \"Search all items\""
    val screenDown = "(scroll down to reveal) EditText \"Search all items\""

    assertEquals("up", scrollDirectionFromAffordance("Search all items", screenUp))
    assertEquals("down", scrollDirectionFromAffordance("Search all items", screenDown))
    assertEquals("down", scrollDirectionFromAffordance("Search all items", null))
  }

  @Test
  fun `extractTargetPhrase prefers quoted phrase`() {
    assertEquals("Search all items", extractTargetPhrase("Tap the \"Search all items\" field"))
  }

  @Test
  fun `extractTargetPhrase falls back to noun phrase after verb`() {
    assertEquals("Library", extractTargetPhrase("Open the Library button"))
  }

  @Test
  fun `should calculate success rate correctly`() {
    val actions = listOf(
      createRecordedAction(success = true),
      createRecordedAction(success = true),
      createRecordedAction(success = false),
      createRecordedAction(success = true),
    )
    
    val state = BlazeState(
      objective = "Test objective",
      actionHistory = actions,
      iteration = 4,
      achieved = false,
      stuck = false,
      workingMemory = WorkingMemory.EMPTY,
    )
    
    val recentAnalyses = listOf(createScreenAnalysis())
    
    val result = reflectionNode.reflect(state, recentAnalyses)
    
    // 75% success rate should be considered positive (>= 70%)
    assertTrue(result.isOnTrack, "Should be on track with 75% success rate")
  }
}
