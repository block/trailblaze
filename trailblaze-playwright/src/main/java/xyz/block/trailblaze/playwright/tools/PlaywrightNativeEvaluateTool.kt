package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Internal framework utility for executing an arbitrary JavaScript expression in the
 * current page's context, returning the result stringified. Thin wrapper over
 * `Page.evaluate(...)`.
 *
 * Registered in `web_framework.yaml` — the sibling toolset to `android_framework.yaml`
 * for arbitrary-string-execution utilities. The toolset is `always_enabled: true` so
 * scripted tools can dispatch this via the typed `client.tools.web_evaluate(...)`
 * surface (per-pack codegen reads from resolved toolset membership), and the runtime
 * `/scripting/callback` dispatcher can resolve it by name.
 *
 * Carries `surfaceToLlm = false` and `isRecordable = false` at the class level — same
 * as the other arbitrary-string-execution utilities (`android_adbShell`,
 * `android_sendBroadcast`, `RunCommandTrailblazeTool`, `ExecTrailblazeTool`,
 * `ScriptTrailblazeTool`). The LLM never sees this in its tool catalog and the trail
 * recorder never captures it as a step. It exists for host-side composition when
 * nothing else fits — something the framework exposes as a utility under the tool
 * contract, not a step in an authored flow.
 */
@Serializable
@TrailblazeToolClass(
  name = "web_evaluate",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription(
  """
Executes a JavaScript expression in the current page context and returns the result as a
string. Internal framework utility — not exposed to the LLM and not captured in trail
recordings; available for host-side composition only.
""",
)
data class PlaywrightNativeEvaluateTool(
  @param:LLMDescription(
    "JavaScript expression or IIFE. Use `(() => { ... })()` shape if you need statements.",
  )
  val script: String,
) : PlaywrightExecutableTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    try {
      val result = page.evaluate(script)
      TrailblazeToolResult.Success(message = result?.toString() ?: "")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("web_evaluate failed: ${e.message}")
    }
}
