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
 */
@Serializable
@TrailblazeToolClass("web_fillSecret")
@LLMDescription(
  """
INTERNAL — fills a form field with a value that must NOT be logged (passwords, tokens, OTPs).
Prefer plain web_type for any value that isn't sensitive, since the LLM-facing recording is
more useful when the value is visible. Use only from scripted tools where the value is loaded
from a trusted source (secrets store, fixture file, etc.).
""",
)
data class PlaywrightNativeFillSecretTool(
  @param:LLMDescription(
    "Element ID, ARIA descriptor (e.g., 'textbox \"Password\"'), or CSS selector with css= prefix.",
  )
  val ref: String,
  @param:LLMDescription(
    "The secret value to fill. Never logged anywhere on the host side.",
  )
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
