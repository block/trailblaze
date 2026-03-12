package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("playwright_verify_value")
@LLMDescription(
  """
Verify the value of an element on the page. Supports checking:
- TEXT: the text content of any element
- VALUE: the input value of a form field (input, textarea, select)
- ATTRIBUTE: the value of an HTML attribute
""",
)
class PlaywrightNativeVerifyValueTool(
  @param:LLMDescription(
    "Element ID (e.g., 'e5'), ARIA descriptor (e.g., 'textbox \"Email\"'), " +
      "or CSS selector with css= prefix (e.g., 'css=#email-input').",
  )
  val ref: String,
  @param:LLMDescription(
    "What property of the element to verify. " +
      "TEXT checks visible text content, VALUE checks form field input values, " +
      "ATTRIBUTE checks a specific HTML attribute.",
  )
  val type: VerifyValueType = VerifyValueType.TEXT,
  @param:LLMDescription("The expected value to verify against.")
  val expected: String,
  @param:LLMDescription("The attribute name to check (required when type is ATTRIBUTE).")
  val attribute: String = "",
  @param:LLMDescription("Human-readable description of the element being verified, for logging.")
  val element: String = "",
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val elementDescriptor: String? get() = element.ifBlank { null }
  override val targetRef: String get() = ref
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeVerifyValueTool(ref = ref, type = type, expected = expected, attribute = attribute, element = element, reasoning = reasoning, nodeSelector = selector)

  @Serializable
  enum class VerifyValueType {
    TEXT,
    VALUE,
    ATTRIBUTE,
  }

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { ref }
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Verifying $type of '$description' equals '$expected'")
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
      if (error != null) return error
      val resolved = locator!!
      when (type) {
        VerifyValueType.TEXT -> {
          try {
            assertThat(resolved.first()).containsText(expected)
          } catch (_: AssertionError) {
            // Text content check failed — fall back to input value check for form fields
            // (input, textarea, select) where the content lives in .value, not .textContent.
            Console.log("### Text check failed, retrying as input value for '$description'")
            assertThat(resolved.first()).hasValue(expected)
          }
        }
        VerifyValueType.VALUE -> assertThat(resolved.first()).hasValue(expected)
        VerifyValueType.ATTRIBUTE -> {
          if (attribute.isBlank()) {
            return TrailblazeToolResult.Error.ExceptionThrown(
              "Attribute name is required when type is 'attribute'.",
            )
          }
          assertThat(resolved.first()).hasAttribute(attribute, expected)
        }
      }
      TrailblazeToolResult.Success(
        message = "Verified $type of '$description' matches '$expected'.",
      )
    } catch (e: AssertionError) {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Assertion failed: $type of '$description' does not match '$expected'.",
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Verify value failed: ${e.message}")
    }
  }
}
