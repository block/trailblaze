package xyz.block.trailblaze.agent.blaze

import xyz.block.trailblaze.agent.BlazeState
import xyz.block.trailblaze.agent.KeyScreenshot
import xyz.block.trailblaze.agent.MemoryOperation
import xyz.block.trailblaze.agent.MemoryOperationResult
import xyz.block.trailblaze.agent.ScreenAnalysis
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.agent.WorkingMemory
import xyz.block.trailblaze.api.ScreenState
import java.util.Base64

/**
 * Memory node for cross-application workflows.
 *
 * Manages working memory that persists across app switches, enabling complex
 * multi-app workflows. The agent can store facts, take screenshots, and
 * transfer information between apps using a simulated clipboard.
 *
 * ## Capabilities
 *
 * - **Fact Storage**: Key-value pairs for prices, IDs, dates, etc.
 * - **Screenshot Memory**: Visual references of important states
 * - **Cross-App Clipboard**: Text transfer between applications
 * - **OCR Extraction**: Extract text from screenshots (via LLM)
 *
 * ## Example: Price Comparison Workflow
 *
 * ```kotlin
 * // In Amazon app
 * memoryNode.execute(state, MemoryOperation.Remember("amazon_price", "$24.99"))
 * memoryNode.execute(state, MemoryOperation.Snapshot("Amazon product page"))
 *
 * // Switch to Walmart app
 * // ... navigate to same product ...
 *
 * // Compare prices
 * val amazonPrice = memoryNode.execute(state, MemoryOperation.Recall("amazon_price"))
 * ```
 *
 * @param executor UI action executor for capturing screenshots
 * @param ocrExtractor Optional OCR function to extract text from screenshots
 *
 * @see WorkingMemory for the storage mechanism
 * @see MemoryOperation for available operations
 */
class MemoryNode(
  private val executor: UiActionExecutor,
  private val ocrExtractor: OcrExtractor? = null,
) {

  /**
   * Executes a memory operation and returns the updated state.
   *
   * @param state Current blaze state
   * @param operation The memory operation to perform
   * @return Pair of (updated state, operation result)
   */
  suspend fun execute(
    state: BlazeState,
    operation: MemoryOperation,
  ): Pair<BlazeState, MemoryOperationResult> {
    return when (operation) {
      is MemoryOperation.Remember -> executeRemember(state, operation)
      is MemoryOperation.Recall -> executeRecall(state, operation)
      is MemoryOperation.Snapshot -> executeSnapshot(state, operation)
      is MemoryOperation.CopyToClipboard -> executeCopyToClipboard(state, operation)
      is MemoryOperation.PasteFromClipboard -> executePasteFromClipboard(state)
      is MemoryOperation.ClearMemory -> executeClearMemory(state)
    }
  }

  /**
   * Stores a fact in working memory.
   */
  private fun executeRemember(
    state: BlazeState,
    operation: MemoryOperation.Remember,
  ): Pair<BlazeState, MemoryOperationResult> {
    val updatedMemory = state.workingMemory.remember(operation.key, operation.value)
    val updatedState = state.copy(
      workingMemory = updatedMemory,
      reflectionNotes = state.reflectionNotes +
        "[Memory] Stored: ${operation.key} = \"${operation.value}\"" +
        (operation.source?.let { " (from $it)" } ?: ""),
    )
    return updatedState to MemoryOperationResult.success()
  }

  /**
   * Recalls a fact from working memory.
   */
  private fun executeRecall(
    state: BlazeState,
    operation: MemoryOperation.Recall,
  ): Pair<BlazeState, MemoryOperationResult> {
    val value = state.workingMemory.recall(operation.key)

    return if (value != null) {
      val updatedState = state.copy(
        reflectionNotes = state.reflectionNotes +
          "[Memory] Recalled: ${operation.key} = \"$value\"",
      )
      updatedState to MemoryOperationResult.success(value)
    } else if (operation.required) {
      state to MemoryOperationResult.failure(
        "Required fact '${operation.key}' not found in memory. " +
          "Available keys: ${state.workingMemory.facts.keys.joinToString()}"
      )
    } else {
      state to MemoryOperationResult.success(null)
    }
  }

  /**
   * Takes a screenshot and stores it in working memory.
   */
  private suspend fun executeSnapshot(
    state: BlazeState,
    operation: MemoryOperation.Snapshot,
  ): Pair<BlazeState, MemoryOperationResult> {
    val screenState = executor.captureScreenState()
      ?: return state to MemoryOperationResult.failure("Failed to capture screenshot")

    // Extract text if OCR is available and keys are requested
    val extractedText = if (ocrExtractor != null && operation.extractKeys.isNotEmpty()) {
      try {
        ocrExtractor.extractText(screenState, operation.extractKeys)
      } catch (e: Exception) {
        emptyMap()
      }
    } else {
      emptyMap()
    }

    val screenshot = KeyScreenshot(
      description = operation.description,
      extractedText = extractedText,
      timestamp = System.currentTimeMillis(),
      // Note: In a full implementation, we'd encode the screenshot here
      screenshotData = null,
    )

    val updatedMemory = state.workingMemory.addScreenshot(screenshot)

    // Also store extracted text as facts for easy recall
    val memoryWithFacts = extractedText.entries.fold(updatedMemory) { mem, (key, value) ->
      mem.remember(key, value)
    }

    val updatedState = state.copy(
      workingMemory = memoryWithFacts,
      reflectionNotes = state.reflectionNotes +
        "[Memory] Snapshot: ${operation.description}" +
        if (extractedText.isNotEmpty()) " (extracted: ${extractedText.keys.joinToString()})" else "",
    )

    return updatedState to MemoryOperationResult.success()
  }

  /**
   * Copies text to the simulated clipboard.
   */
  private fun executeCopyToClipboard(
    state: BlazeState,
    operation: MemoryOperation.CopyToClipboard,
  ): Pair<BlazeState, MemoryOperationResult> {
    val updatedMemory = state.workingMemory.copyToClipboard(operation.text)
    val updatedState = state.copy(
      workingMemory = updatedMemory,
      reflectionNotes = state.reflectionNotes +
        "[Memory] Copied to clipboard: \"${operation.text.take(50)}${if (operation.text.length > 50) "..." else ""}\"",
    )
    return updatedState to MemoryOperationResult.success()
  }

  /**
   * Pastes text from the simulated clipboard.
   */
  private fun executePasteFromClipboard(
    state: BlazeState,
  ): Pair<BlazeState, MemoryOperationResult> {
    val clipboard = state.workingMemory.clipboard

    return if (clipboard != null) {
      val updatedState = state.copy(
        reflectionNotes = state.reflectionNotes +
          "[Memory] Pasted from clipboard: \"${clipboard.take(50)}${if (clipboard.length > 50) "..." else ""}\"",
      )
      updatedState to MemoryOperationResult.success(clipboard)
    } else {
      state to MemoryOperationResult.failure("Clipboard is empty")
    }
  }

  /**
   * Clears all working memory.
   */
  private fun executeClearMemory(
    state: BlazeState,
  ): Pair<BlazeState, MemoryOperationResult> {
    val updatedState = state.copy(
      workingMemory = WorkingMemory.EMPTY,
      reflectionNotes = state.reflectionNotes + "[Memory] Cleared all memory",
    )
    return updatedState to MemoryOperationResult.success()
  }

  /**
   * Suggests memory operations based on screen analysis.
   *
   * Analyzes the current screen and objective to determine if any
   * memory operations should be performed (e.g., storing a price,
   * recalling a previously saved fact).
   *
   * @param state Current blaze state
   * @param analysis Current screen analysis
   * @return List of suggested memory operations
   */
  fun suggestOperations(
    state: BlazeState,
    analysis: ScreenAnalysis,
  ): List<MemoryOperation> {
    val suggestions = mutableListOf<MemoryOperation>()

    // Check if screen contains information that should be remembered
    // This is a heuristic; in production, the LLM would make this decision
    val pricePattern = Regex("""\$\d+(?:\.\d{2})?""")
    val prices = pricePattern.findAll(analysis.screenSummary)
    prices.forEach { match ->
      if (state.workingMemory.recall("price") == null) {
        suggestions.add(
          MemoryOperation.Remember(
            key = "price",
            value = match.value,
            source = "screen analysis",
          )
        )
      }
    }

    // Check if objective mentions recalling previously stored information
    val recallKeywords = listOf("compare", "verify", "check", "same as", "match")
    if (recallKeywords.any { state.objective.contains(it, ignoreCase = true) }) {
      // Suggest recalling any stored facts that might be relevant
      state.workingMemory.facts.keys.forEach { key ->
        suggestions.add(MemoryOperation.Recall(key, required = false))
      }
    }

    return suggestions
  }

  /**
   * Checks if any required facts are missing from memory.
   *
   * Useful for workflows that depend on previously stored information.
   *
   * @param state Current blaze state
   * @param requiredKeys Keys that must be present in memory
   * @return List of missing keys
   */
  fun getMissingFacts(state: BlazeState, requiredKeys: List<String>): List<String> {
    return requiredKeys.filter { key -> state.workingMemory.recall(key) == null }
  }
}

/**
 * Interface for OCR text extraction from screenshots.
 *
 * Implementations typically use the LLM's vision capability to extract
 * specific text from screenshots.
 */
interface OcrExtractor {
  /**
   * Extracts text values for specified keys from a screen state.
   *
   * @param screenState The captured screen state
   * @param keysToExtract Which pieces of information to extract
   * @return Map of key to extracted value
   */
  suspend fun extractText(
    screenState: ScreenState,
    keysToExtract: List<String>,
  ): Map<String, String>
}

/**
 * Creates a MemoryNode with optional OCR capabilities.
 *
 * @param executor UI action executor for screenshots
 * @param ocrExtractor Optional OCR function (uses LLM vision if provided)
 * @return Configured MemoryNode
 */
fun createMemoryNode(
  executor: UiActionExecutor,
  ocrExtractor: OcrExtractor? = null,
): MemoryNode = MemoryNode(executor, ocrExtractor)

/**
 * LLM-based OCR extractor using vision capabilities.
 *
 * Uses the LLM's vision capability to extract specific text values
 * from screenshots. This is more flexible than traditional OCR as it
 * can understand context and extract semantic information.
 *
 * @param llmCall Function to call the LLM with a prompt and image
 */
class LlmOcrExtractor(
  private val llmCall: suspend (prompt: String, imageBase64: String?) -> String,
) : OcrExtractor {

  override suspend fun extractText(
    screenState: ScreenState,
    keysToExtract: List<String>,
  ): Map<String, String> {
    val prompt = buildString {
      appendLine("Extract the following information from this screenshot:")
      keysToExtract.forEach { key ->
        appendLine("- $key")
      }
      appendLine()
      appendLine("Respond with JSON mapping each key to its value, e.g.:")
      appendLine("""{"price": "$24.99", "product_name": "Widget"}""")
      appendLine("If a value is not visible, use null.")
    }

    // Convert screenshot bytes to Base64 for LLM
    val screenshotBase64 = screenState.screenshotBytes?.let {
      Base64.getEncoder().encodeToString(it)
    }

    val response = llmCall(prompt, screenshotBase64)

    // Parse the JSON response
    return try {
      // Simple parsing - in production, use proper JSON deserialization
      val pattern = Regex(""""(\w+)":\s*"([^"]+)"""")
      pattern.findAll(response).associate { it.groupValues[1] to it.groupValues[2] }
    } catch (e: Exception) {
      emptyMap()
    }
  }
}
