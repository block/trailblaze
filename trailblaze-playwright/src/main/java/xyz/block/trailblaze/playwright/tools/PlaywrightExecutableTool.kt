package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.WaitForSelectorState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
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
   * The element ref used by this tool (e.g., "e5", `button "Submit"`, `css=#my-id`).
   * Non-null for tools that target a specific element. Used by the agent to generate
   * [TrailblazeNodeSelector] for recording.
   */
  val targetRef: String? get() = null

  /**
   * The durable [TrailblazeNodeSelector] attached to this tool, if any.
   *
   * Populated by [withNodeSelector] after a successful execution and persisted into the
   * recorded trail YAML. Drives action-time element resolution via [validateAndResolveRef].
   * Returns null for tools that don't target a specific element (e.g., navigate, wait).
   */
  val targetNodeSelector: TrailblazeNodeSelector? get() = null

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
     * Builds a human-readable description of a tool's target element for log lines.
     * Prefers the durable [TrailblazeNodeSelector.description] (intent-aligned with the
     * recorded selector), then the LLM-supplied [ref], with `<unidentified>` as a final
     * fallback so log lines never blank out.
     */
    fun describeTarget(nodeSelector: TrailblazeNodeSelector?, ref: String?): String =
      nodeSelector?.description()?.takeIf { it.isNotBlank() }
        ?: ref?.takeIf { it.isNotBlank() }
        ?: "<unidentified>"

    /**
     * Best-effort: resolve a [TrailblazeNodeSelector] to a Playwright [Locator] for the
     * recording-playback readiness wait. Uses driver-direct primitives (CSS selector,
     * data-testid, ARIA role+name) so it does not require a pre-captured screen-state tree.
     *
     * Returns null when the selector has no usable web matcher (e.g., a non-web selector,
     * or one whose only signal is spatial/hierarchical and needs the resolver). Callers
     * should fall back to ref-based resolution in that case.
     */
    fun nodeSelectorToReadinessLocator(
      page: Page,
      nodeSelector: TrailblazeNodeSelector,
    ): Locator? {
      val web = nodeSelector.web ?: return null
      web.cssSelector?.let { return page.locator("css=$it") }
      web.dataTestId?.let { return page.locator("[data-testid=\"$it\"]") }
      val role = web.ariaRole ?: return null
      val descriptor = web.ariaNameRegex?.let { name ->
        "$role \"${unescapeLiteralRegex(name)}\""
      } ?: role
      val locator = PlaywrightAriaSnapshot.resolveRef(page, descriptor)
      return web.nthIndex?.let { locator.nth(it) } ?: locator
    }

    private fun unescapeLiteralRegex(text: String): String {
      // Regex.escape() wraps literals containing metacharacters in \Q...\E.
      if (text.startsWith("\\Q") && text.endsWith("\\E")) {
        return text.substring(2, text.length - 2)
      }
      // Selector generators sometimes anchor names with ^ ... $.
      return text.removePrefix("^").removeSuffix("$")
    }

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
     * Resolution strategy uses Playwright's built-in [Locator.or] primitive rather
     * than a Trailblaze-side tiered fallback chain. When both a [nodeSelector] and a
     * [ref] are provided, we build a live Playwright locator for each and OR them
     * together — whichever attaches to the DOM first wins. A single auto-wait covers
     * the union ([elementResolutionTimeoutMs], default 10s). No per-tier timeout, no
     * Trailblaze-specific race semantics — Playwright handles the matching the same
     * way it does for any other `Locator.or()` user.
     *
     * **Blocking:** this call may block for up to [elementResolutionTimeoutMs] while
     * the unioned locator polls for either selector to attach. This mirrors
     * Playwright's own actionability checks on `locator.click` / `locator.fill` and
     * is what lets post-navigation SPA renders settle without `web_wait` filler.
     *
     * @return The resolved [Locator], or null if validation/resolution failed
     *   (in which case the second tuple element is set to the appropriate error result).
     */
    fun validateAndResolveRef(
      page: Page,
      ref: String?,
      description: String,
      context: TrailblazeToolExecutionContext,
      nodeSelector: TrailblazeNodeSelector? = null,
    ): Pair<Locator?, TrailblazeToolResult?> {
      val effectiveRef = ref?.takeIf { it.isNotBlank() }
      if (effectiveRef == null && nodeSelector == null) {
        return null to TrailblazeToolResult.Error.ExceptionThrown(
          "Element ref is required but was blank.",
        )
      }

      // Build every locator we have a source for, then let Playwright race them via
      // `Locator.or()`. Whichever attaches first wins. We deliberately do NOT chain
      // try-this-then-try-that ourselves — that's exactly the kind of Trailblaze-side
      // logic that diverges from where Playwright is going. Playwright owns "try
      // multiple selectors and use whichever matches."
      val nodeSelectorLocator = nodeSelector?.let { nodeSelectorToReadinessLocator(page, it) }
      val refLocator = effectiveRef?.let { resolveRef(page, it, context) }

      val candidate: Locator? = when {
        nodeSelectorLocator != null && refLocator != null -> nodeSelectorLocator.or(refLocator)
        nodeSelectorLocator != null -> nodeSelectorLocator
        refLocator != null -> refLocator
        else -> null
      }

      if (candidate == null) {
        return null to TrailblazeToolResult.Error.ExceptionThrown(
          "Element ref is required but was blank, and nodeSelector resolution failed.",
        )
      }

      // Auto-wait for one of the OR-combined locators to attach to the DOM. Same
      // semantics Playwright applies inside `locator.click` / `locator.fill` / etc.
      // — poll instead of fail-fast on a not-yet-rendered element. This is what
      // lets post-navigation SPA renders settle without `web_wait` filler steps.
      val timeoutMs = elementResolutionTimeoutMs
      return try {
        candidate.first().waitFor(
          Locator.WaitForOptions()
            .setState(WaitForSelectorState.ATTACHED)
            .setTimeout(timeoutMs),
        )
        candidate to null
      } catch (_: TimeoutError) {
        val identifier = effectiveRef ?: nodeSelector?.description() ?: "<unidentified>"
        null to TrailblazeToolResult.Error.ExceptionThrown(
          "No element found matching '$identifier' ($description) within ${timeoutMs.toLong()}ms. " +
            "Use web_snapshot to refresh the page state.",
        )
      } catch (e: PlaywrightException) {
        // Page closed, frame detached, navigation interrupted mid-wait — surface as a tool
        // result error rather than letting the exception escape and crash the tool path.
        val identifier = effectiveRef ?: nodeSelector?.description() ?: "<unidentified>"
        null to TrailblazeToolResult.Error.ExceptionThrown(
          "Failed to resolve '$identifier' ($description): ${e::class.simpleName}: ${e.message}",
        )
      }
    }

    /**
     * Upper bound on the element-attached auto-wait inside [validateAndResolveRef]. Defaults
     * to 10s, which comfortably absorbs post-login SPA renders (typical settle ~1–3s) without
     * making "this element really doesn't exist" cases drag. Mutable only so unit tests can
     * shrink it to fail fast on negative-path resolution; not intended to be tuned by callers
     * in production.
     */
    internal var elementResolutionTimeoutMs: Double = 10_000.0

  }
}
