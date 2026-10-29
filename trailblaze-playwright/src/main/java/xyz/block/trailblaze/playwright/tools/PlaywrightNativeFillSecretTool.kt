package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Fill-an-input variant that NEVER logs the value it typed. Identical to [web_type] in
 * effect, but the value never appears in [Console.log], the success message, or anywhere
 * else the framework writes diagnostics.
 *
 * Intended for scripted tools that feed credentials into login forms — the alternative
 * (calling `web_type` with the password) leaks the cleartext to console output and any
 * downstream log aggregator.
 *
 * Note: when invoked over MCP stdio the args ARE serialized in the JSON-RPC frame, so an
 * MCP transport with verbose request logging would still see the value. The framework
 * does not currently log MCP request bodies; this tool's masking covers the in-process
 * surfaces we control.
 *
 * Prefer plain web_type for non-sensitive values: the recording is more useful when the value is
 * visible. The value should come from a trusted source (secrets store, fixture file, etc.).
 */
@Serializable
@TrailblazeToolClass("web_fillSecret")
@LLMDescription(
  """
For scripted tools: fill a field with a value that must not be logged (password, token, OTP). Use web_type for anything else.
""",
)
data class PlaywrightNativeFillSecretTool(
  @param:LLMDescription("Element ID ('e5'), ARIA descriptor ('textbox \"Password\"'), or 'css=<selector>'.")
  val ref: String,
  @param:LLMDescription("Secret value to fill; never logged.")
  val value: String,
) : PlaywrightExecutableTool {
  override val targetRef: String?
    get() = ref

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = PlaywrightExecutableTool.describeTarget(nodeSelector = null, ref = ref)
    Console.log("### Filling secret into $description")
    return try {
      val (locator, error) =
        PlaywrightExecutableTool.validateAndResolveRef(
          page = page,
          ref = ref,
          description = description,
          context = context,
          nodeSelector = null,
        )
      if (error != null) return error
      locator!!.fill(value)
      TrailblazeToolResult.Success(message = "Filled secret into '$description'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("web_fillSecret failed on '$description': ${e.message}")
    }
  }
}
