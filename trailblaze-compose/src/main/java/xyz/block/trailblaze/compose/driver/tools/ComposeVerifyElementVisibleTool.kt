package xyz.block.trailblaze.compose.driver.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("compose_verify_element_visible", isVerification = true)
@LLMDescription("Assert an element is on screen, identified by elementId or testTag.")
data class ComposeVerifyElementVisibleTool(
  @param:LLMDescription("Element testTag.")
  val testTag: String? = null,
  @param:LLMDescription("Element ID, e.g. 'e5'.")
  val elementId: String? = null,
  @param:LLMDescription("Short description of the element, for logs.")
  val element: String = "",
) : ComposeExecutableTool {

  override suspend fun executeWithCompose(
    target: ComposeTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { elementId ?: testTag ?: "unknown" }
    Console.log("### Verifying element visible: $description")
    return try {
      val matcher =
        ComposeExecutableTool.resolveElement(elementId, testTag, null, context)
          ?: return TrailblazeToolResult.Error.ExceptionThrown(
            "Must provide elementId or testTag to identify the element."
          )
      val nodes = ComposeExecutableTool.findNodes(target, matcher)
      if (nodes.isEmpty()) {
        TrailblazeToolResult.Error.ExceptionThrown(
          "Assertion failed: element '$description' is not visible."
        )
      } else {
        TrailblazeToolResult.Success(message = "Verified element '$description' is visible.")
      }
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify element failed: ${e.message}")
    }
  }
}
