package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.playwright.PlaywrightAriaSnapshot
import xyz.block.trailblaze.playwright.PlaywrightScreenState
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Interface for tools that execute directly against a Playwright [Page].
 *
 * This is the Playwright equivalent of [MapsToMaestroCommands]. Tools implementing
 * this interface are executed by [PlaywrightTrailblazeAgent] which provides the
 * current Playwright page.
 *
 * The default [execute] implementation throws an error directing callers to use
 * [PlaywrightTrailblazeAgent], which calls [executeWithPlaywright] directly.
 */
interface PlaywrightExecutableTool : ExecutableTrailblazeTool {

  /**
   * ARIA descriptor of the target element (e.g., "textbox Password", "button Submit").
   *
   * Used during recording playback to wait for the element to be present before executing
   * the tool. This is critical because recorded steps fire without LLM latency — after a
   * page transition (e.g., clicking "Continue" on a login form), the next tool's target
   * element may not exist yet. Returning a non-blank descriptor triggers an element-readiness
   * wait with a fresh screen state capture, ensuring the element is interactable and element
   * ID mappings are current.
   *
   * Returns null for tools that don't target a specific element (e.g., navigate, wait).
   */
  val elementDescriptor: String? get() = null

  /**
   * The element ref used by this tool (e.g., "e5", `button "Submit"`, `css=#my-id`).
   * Non-null for tools that target a specific element. Used by the agent to generate
   * [TrailblazeNodeSelector] for recording.
   */
  val targetRef: String? get() = null

  /**
   * Returns a copy of this tool with the given [TrailblazeNodeSelector] attached.
   *
   * Called by [PlaywrightTrailblazeAgent] after successful execution to enrich the
   * tool with a rich selector before logging. The enriched tool instance is what gets
   * serialized into the trail YAML recording.
   *
   * Default implementation returns `this` unchanged — only tools that target elements
   * need to override this.
   */
  fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool = this

  /**
   * Executes this tool against the given Playwright page.
   *
   * @param page The current Playwright page to execute actions against.
   * @param context The tool execution context with session, logging, and memory.
   * @return The result of tool execution.
   */
  suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    error("PlaywrightExecutableTool must be executed via PlaywrightTrailblazeAgent")
  }

  companion object {
    /**
     * Resolves a ref string to a Playwright [Locator].
     *
     * Supports three resolution strategies, tried in order:
     * 1. **CSS selectors** — refs prefixed with `css=` (e.g., `css=#my-id`,
     *    `css=[data-testid="card"]`) are passed directly to Playwright's locator engine.
     *    This is the fallback for elements without good ARIA semantics.
     * 2. **Element IDs** — refs matching the compact ARIA list format (e.g., "e5", "[e5]")
     *    are resolved via [PlaywrightScreenState.elementIdMapping], using `.nth()` to
     *    disambiguate duplicate elements.
     * 3. **ARIA descriptors** — refs like `button "Submit"` are resolved via Playwright's
     *    `getByRole` API.
     */
    fun resolveRef(
      page: Page,
      ref: String,
      context: TrailblazeToolExecutionContext,
    ): Locator {
      // CSS selector prefix — pass directly to Playwright's locator engine
      if (ref.startsWith("css=")) {
        return page.locator(ref)
      }
      val screenState = context.screenState
      if (screenState is PlaywrightScreenState) {
        val elementRef = screenState.resolveElementId(ref)
        if (elementRef != null) {
          return PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
        }
      }
      // Fall back to direct ARIA descriptor resolution
      return PlaywrightAriaSnapshot.resolveRef(page, ref)
    }

    /**
     * Validates a ref string and resolves it to a Playwright [Locator], returning
     * an error result if the ref is blank or no matching element is found.
     *
     * When a [nodeSelector] is provided (from a recorded trail), it is tried first
     * via [TrailblazeNodeSelectorResolver] against the current page's [TrailblazeNode]
     * tree. This provides stable element resolution across page layout changes where
     * element IDs (e.g., "e5") may have shifted. Falls back to ref-based resolution
     * if the selector doesn't match.
     *
     * @return The resolved [Locator], or null if validation/resolution failed
     *   (in which case [onError] is set to the appropriate error result).
     */
    fun validateAndResolveRef(
      page: Page,
      ref: String,
      description: String,
      context: TrailblazeToolExecutionContext,
      nodeSelector: TrailblazeNodeSelector? = null,
    ): Pair<Locator?, TrailblazeToolResult?> {
      if (ref.isBlank() && nodeSelector == null) {
        return null to TrailblazeToolResult.Error.ExceptionThrown(
          "Element ref is required but was blank.",
        )
      }

      // Try nodeSelector first — provides stable resolution across layout changes
      if (nodeSelector != null) {
        resolveViaNodeSelector(page, nodeSelector, context)?.let { locator ->
          return locator to null
        }
      }

      if (ref.isBlank()) {
        return null to TrailblazeToolResult.Error.ExceptionThrown(
          "Element ref is required but was blank, and nodeSelector resolution failed.",
        )
      }
      val locator = resolveRef(page, ref, context)
      if (locator.count() == 0) {
        return null to TrailblazeToolResult.Error.ExceptionThrown(
          "No element found matching '$ref' ($description). " +
            "Use playwright_snapshot to refresh the page state.",
        )
      }
      return locator to null
    }

    /**
     * Resolves a [TrailblazeNodeSelector] against the current page's [TrailblazeNode] tree
     * and maps the matched node back to a Playwright [Locator].
     *
     * Resolution flow:
     * 1. Get the [trailblazeNodeTree] from the current [PlaywrightScreenState]
     * 2. Resolve the selector via [TrailblazeNodeSelectorResolver]
     * 3. Extract [DriverNodeDetail.Web.ariaDescriptor] + [nthIndex] from the matched node
     * 4. Build a Playwright locator via [PlaywrightAriaSnapshot.resolveElementRef]
     *
     * @return A [Locator] if resolution succeeds, or null to fall back to ref-based resolution.
     */
    private fun resolveViaNodeSelector(
      page: Page,
      nodeSelector: TrailblazeNodeSelector,
      context: TrailblazeToolExecutionContext,
    ): Locator? {
      val screenState = context.screenState as? PlaywrightScreenState ?: return null
      val tree = screenState.trailblazeNodeTree ?: return null

      return try {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector)
        val matchedNode = when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            Console.log("  [nodeSelector] Multiple matches (${result.nodes.size}), using first")
            result.nodes.first()
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> {
            Console.log("  [nodeSelector] No match, falling back to ref")
            return null
          }
        }

        val detail = matchedNode.driverDetail as? DriverNodeDetail.Web ?: return null
        val descriptor = detail.ariaDescriptor ?: return null
        val elementRef = PlaywrightAriaSnapshot.ElementRef(
          descriptor = descriptor,
          nthIndex = detail.nthIndex,
        )
        val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
        if (locator.count() > 0) {
          Console.log("  [nodeSelector] Resolved: $descriptor (nth=${detail.nthIndex})")
          locator
        } else {
          Console.log("  [nodeSelector] Locator found no elements, falling back to ref")
          null
        }
      } catch (e: Exception) {
        Console.log("  [nodeSelector] Resolution failed: ${e.message}, falling back to ref")
        null
      }
    }
  }
}
