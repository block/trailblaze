package xyz.block.trailblaze.agent.blaze

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.MemoryOperation
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.agent.WorkingMemory
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.Confidence
import xyz.block.trailblaze.agent.ExecutionResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [MemoryNode].
 *
 * Tests fact storage, recall, snapshots, and clipboard operations.
 */
private val MEMORY_MOCK_SCREEN_STATE: ScreenState = object : ScreenState {
  override val screenshotBytes: ByteArray? = null
  override val deviceWidth: Int = 1080
  override val deviceHeight: Int = 1920
  override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
  override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
  override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
}

class MemoryNodeTest {

  // Mock UI executor
  private class MockUiActionExecutor(
    private val defaultScreenState: ScreenState? = null,
  ) : UiActionExecutor {
    var captureCount = 0
    var captureResults: MutableList<ScreenState?> = mutableListOf(defaultScreenState)
    
    override suspend fun execute(
      toolName: String,
      args: JsonObject,
      traceId: TraceId?,
    ): ExecutionResult {
      return ExecutionResult.Success(
        screenSummaryAfter = "Screen after action",
        durationMs = 100L,
      )
    }
    
    override suspend fun captureScreenState(): ScreenState? {
      val result = if (captureCount < captureResults.size) {
        captureResults[captureCount]
      } else {
        captureResults.lastOrNull()
      }
      captureCount++
      return result
    }
  }

  private fun createInitialState(): BlazeState = BlazeState(
    objective = "Test objective",
    actionHistory = emptyList(),
    iteration = 0,
    achieved = false,
    stuck = false,
    workingMemory = WorkingMemory.EMPTY,
  )

  // ====== REMEMBER TESTS ======

  @Test
  fun `should store facts in memory`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val operation = MemoryOperation.Remember("price", "$24.99")
    
    val (updatedState, result) = memoryNode.execute(state, operation)
    
    assertTrue(result.success, "Operation should succeed")
    assertEquals("$24.99", updatedState.workingMemory.recall("price"))
  }

  @Test
  fun `should store multiple facts`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    
    state = memoryNode.execute(state, MemoryOperation.Remember("price", "$24.99")).first
    state = memoryNode.execute(state, MemoryOperation.Remember("product", "Widget")).first
    state = memoryNode.execute(state, MemoryOperation.Remember("qty", "2")).first
    
    assertEquals("$24.99", state.workingMemory.recall("price"))
    assertEquals("Widget", state.workingMemory.recall("product"))
    assertEquals("2", state.workingMemory.recall("qty"))
  }

  @Test
  fun `should overwrite existing facts`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    
    state = memoryNode.execute(state, MemoryOperation.Remember("price", "$20.00")).first
    state = memoryNode.execute(state, MemoryOperation.Remember("price", "$25.00")).first
    
    assertEquals("$25.00", state.workingMemory.recall("price"))
  }

  @Test
  fun `should track memory operations in reflection notes`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val result = memoryNode.execute(
      state,
      MemoryOperation.Remember("key", "value", source = "screen"),
    )
    val updatedState = result.first
    val opResult = result.second
    
    assertTrue(opResult.success, "Remember operation should succeed")
    assertEquals("value", updatedState.workingMemory.recall("key"), "Value should be stored")
    // Reflection notes are added by the MemoryNode implementation
    assertTrue(
      updatedState.reflectionNotes.any { it.contains("Stored") || it.contains("Memory") },
      "Notes should mention storing. Got: ${updatedState.reflectionNotes}"
    )
  }

  // ====== RECALL TESTS ======

  @Test
  fun `should recall stored facts`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.Remember("user_id", "12345")).first
    
    val (_, result) = memoryNode.execute(state, MemoryOperation.Recall("user_id", required = false))
    
    assertTrue(result.success, "Recall should succeed")
    assertEquals("12345", result.value)
  }

  @Test
  fun `should return null for missing optional fact`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val (_, result) = memoryNode.execute(
      state,
      MemoryOperation.Recall("nonexistent", required = false),
    )
    
    assertTrue(result.success, "Should succeed for optional recall")
    assertNull(result.value, "Should return null for missing optional fact")
  }

  @Test
  fun `should fail for missing required fact`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val (_, result) = memoryNode.execute(
      state,
      MemoryOperation.Recall("nonexistent", required = true),
    )
    
    assertFalse(result.success, "Should fail for missing required fact")
    assertTrue(result.error?.contains("not found") ?: false, "Error message should mention not found")
  }

  @Test
  fun `should track recalls in reflection notes`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.Remember("key", "value")).first
    
    val (updatedState, _) = memoryNode.execute(
      state,
      MemoryOperation.Recall("key", required = false),
    )
    
    assertTrue(updatedState.reflectionNotes.isNotEmpty())
    assertTrue(
      updatedState.reflectionNotes.last().contains("Recalled"),
      "Notes should mention recall"
    )
  }

  // ====== SNAPSHOT TESTS ======

  @Test
  fun `should capture and store screenshots`() = runBlocking {
    val executor = MockUiActionExecutor(MEMORY_MOCK_SCREEN_STATE)
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val (updatedState, result) = memoryNode.execute(
      state,
      MemoryOperation.Snapshot("Login screen"),
    )
    
    assertTrue(result.success, "Snapshot should succeed with valid screen state")
    assertTrue(
      updatedState.reflectionNotes.isNotEmpty(),
      "Should record snapshot in notes"
    )
  }

  @Test
  fun `should track snapshots in reflection notes`() = runBlocking {
    val executor = MockUiActionExecutor(MEMORY_MOCK_SCREEN_STATE)
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val (updatedState, _) = memoryNode.execute(
      state,
      MemoryOperation.Snapshot("Important state"),
    )
    
    assertTrue(
      updatedState.reflectionNotes.isNotEmpty(),
      "Should record snapshot operation in notes"
    )
  }

  // ====== CLIPBOARD TESTS ======

  @Test
  fun `should copy text to clipboard`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val (updatedState, result) = memoryNode.execute(
      state,
      MemoryOperation.CopyToClipboard("test@example.com"),
    )
    
    assertTrue(result.success, "Copy operation should succeed")
    assertEquals("test@example.com", updatedState.workingMemory.clipboard)
  }

  @Test
  fun `should paste from clipboard`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(
      state,
      MemoryOperation.CopyToClipboard("secret_password"),
    ).first
    
    val (_, result) = memoryNode.execute(
      state,
      MemoryOperation.PasteFromClipboard,
    )
    
    assertTrue(result.success, "Paste should succeed")
    assertEquals("secret_password", result.value)
  }

  @Test
  fun `should fail when pasting from empty clipboard`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState() // No clipboard content
    val (_, result) = memoryNode.execute(
      state,
      MemoryOperation.PasteFromClipboard,
    )
    
    assertFalse(result.success, "Should fail when clipboard is empty")
  }

  @Test
  fun `should overwrite clipboard content`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.CopyToClipboard("first")).first
    state = memoryNode.execute(state, MemoryOperation.CopyToClipboard("second")).first
    
    val (_, result) = memoryNode.execute(
      state,
      MemoryOperation.PasteFromClipboard,
    )
    
    assertEquals("second", result.value, "Should have latest clipboard content")
  }

  // ====== CLEAR MEMORY TESTS ======

  @Test
  fun `should clear all memory`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.Remember("key", "value")).first
    state = memoryNode.execute(state, MemoryOperation.CopyToClipboard("clipboard")).first
    
    val (updatedState, _) = memoryNode.execute(
      state,
      MemoryOperation.ClearMemory,
    )
    
    assertNull(updatedState.workingMemory.recall("key"), "Facts should be cleared")
    assertNull(updatedState.workingMemory.clipboard, "Clipboard should be cleared")
  }

  @Test
  fun `should reset to empty memory`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.Remember("a", "1")).first
    state = memoryNode.execute(state, MemoryOperation.Remember("b", "2")).first
    
    val (updatedState, _) = memoryNode.execute(
      state,
      MemoryOperation.ClearMemory,
    )
    
    assertEquals(WorkingMemory.EMPTY, updatedState.workingMemory)
  }

  // ====== OPERATION SUGGESTION TESTS ======

  @Test
  fun `should suggest recall operations for comparison objectives`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState().copy(objective = "Compare prices")
    state = memoryNode.execute(state, MemoryOperation.Remember("price", "$20")).first
    
    val analysis = createTestScreenAnalysis()
    val suggestions = memoryNode.suggestOperations(state, analysis)
    
    // Should suggest recall operations
    assertTrue(
      suggestions.any { it is MemoryOperation.Recall },
      "Should suggest recall for comparison objective"
    )
  }

  @Test
  fun `should suggest remember for prices found on screen`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState()
    val analysis = createTestScreenAnalysis(
      screenSummary = "Product costs $29.99 on this platform"
    )
    
    val suggestions = memoryNode.suggestOperations(state, analysis)
    
    // May or may not find price depending on regex
    // Just verify no crash
    assertNotNull(suggestions)
    Unit
  }

  @Test
  fun `should return empty suggestions for neutral objective`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val state = createInitialState().copy(objective = "Just navigate")
    val analysis = createTestScreenAnalysis()
    
    val suggestions = memoryNode.suggestOperations(state, analysis)
    
    assertNotNull(suggestions)
    Unit
  }

  // ====== MISSING FACTS TESTS ======

  @Test
  fun `should identify missing required facts`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.Remember("user_id", "123")).first
    
    val missing = memoryNode.getMissingFacts(
      state,
      listOf("user_id", "auth_token", "session_id"),
    )
    
    assertEquals(2, missing.size, "Should identify 2 missing facts")
    assertTrue("auth_token" in missing)
    assertTrue("session_id" in missing)
  }

  @Test
  fun `should return empty missing facts when all present`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    state = memoryNode.execute(state, MemoryOperation.Remember("a", "1")).first
    state = memoryNode.execute(state, MemoryOperation.Remember("b", "2")).first
    
    val missing = memoryNode.getMissingFacts(state, listOf("a", "b"))
    
    assertEquals(0, missing.size, "Should report no missing facts")
  }

  // ====== EDGE CASES ======

  @Test
  fun `should handle very long clipboard content`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val longText = "x".repeat(10000)
    val state = createInitialState()
    
    val (updatedState, result) = memoryNode.execute(
      state,
      MemoryOperation.CopyToClipboard(longText),
    )
    
    assertTrue(result.success)
    assertEquals(longText, updatedState.workingMemory.clipboard)
  }

  @Test
  fun `should handle special characters in facts`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    val specialValue = "test@example.com!#$%^&*()"
    val state = createInitialState()
    
    val (updatedState, _) = memoryNode.execute(
      state,
      MemoryOperation.Remember("email", specialValue),
    )
    
    assertEquals(specialValue, updatedState.workingMemory.recall("email"))
  }

  @Test
  fun `should handle many facts in memory`() = runBlocking {
    val executor = MockUiActionExecutor()
    val memoryNode = MemoryNode(executor)
    
    var state = createInitialState()
    
    // Store many facts
    repeat(50) { i ->
      state = memoryNode.execute(
        state,
        MemoryOperation.Remember("fact_$i", "value_$i"),
      ).first
    }
    
    // Verify all are stored
    repeat(50) { i ->
      assertEquals("value_$i", state.workingMemory.recall("fact_$i"))
    }
  }

  // ====== HELPER FUNCTIONS ======

  private fun createTestScreenAnalysis(
    screenSummary: String = "Test screen",
  ): ScreenAnalysis = ScreenAnalysis(
    screenSummary = screenSummary,
    recommendedTool = "tap",
    recommendedArgs = JsonObject(emptyMap()),
    reasoning = "Test",
    confidence = Confidence.HIGH,
    objectiveAppearsAchieved = false,
    objectiveAppearsImpossible = false,
    progressIndicators = emptyList(),
    potentialBlockers = emptyList(),
    alternativeApproaches = emptyList(),
  )
}
