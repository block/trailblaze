package xyz.block.trailblaze.toolcalls.commands.memory

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.MatchDescriptorBuilder
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.toolcalls.SnapshotCache
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Outcome of [captureSelectorText]: either the matched node's text, or the error the calling tool
 * should return verbatim.
 */
internal sealed interface SelectorTextCapture {
  data class Captured(val text: String) : SelectorTextCapture

  data class Failed(val error: TrailblazeToolResult.Error) : SelectorTextCapture
}

/**
 * Reads the driver-native text of the ONE node matching [nodeSelector] on the current screen.
 *
 * Shared by [RememberTextBySelectorTrailblazeTool] and [RememberNumberBySelectorTrailblazeTool] —
 * the deterministic, zero-LLM counterparts of the prompt-based `rememberText` / `rememberNumber`,
 * whose locator step always spends a model call and therefore cannot replay on a recording-only
 * leg. Resolution goes through the same [TrailblazeNodeSelectorResolver] as `findMatches` /
 * `assertVisibleBySelector`. The captured text comes from the field the selector matched on (see
 * [pinnedTextField]), falling back to the cross-driver [MatchDescriptorBuilder.extractIdentity]
 * rule `findMatches` reports as `matchedText` when the selector doesn't name one.
 *
 * A selector that matches more than once is an ERROR rather than a first-match pick: which node
 * won would depend on resolver traversal order, reintroducing the non-determinism this path exists
 * to remove.
 *
 * Point-in-time, like `findMatches` with no `timeoutMs`: it reads the screen as captured, and does
 * not wait for an element to render. Precede it with an assertion that waits (e.g.
 * `assertVisibleBySelector`) when the value appears after a navigation.
 */
internal fun captureSelectorText(
  toolName: String,
  toolExecutionContext: TrailblazeToolExecutionContext,
  nodeSelector: TrailblazeNodeSelector?,
): SelectorTextCapture {
  val selector = nodeSelector
    ?: return failed("$toolName requires `nodeSelector` to be non-null.")

  // Read-only, so routing through the batch's SnapshotCache frame reuses a tree an earlier query
  // already paid for; outside a frame this falls back to a direct capture.
  val provider = toolExecutionContext.screenStateProvider
  val screenState = if (provider != null) {
    SnapshotCache.snapshot(provider, toolExecutionContext.traceId?.traceId)
  } else {
    toolExecutionContext.screenState
  }
  val tree: TrailblazeNode = screenState?.trailblazeNodeTree
    ?: return failed(
      "$toolName: current driver does not produce a TrailblazeNode tree " +
        "(platform=${screenState?.trailblazeDevicePlatform?.name ?: "unknown"}). " +
        "The selector cannot be resolved.",
    )

  val target = toolExecutionContext.resolvedTarget?.let { resolved ->
    TargetTemplateContext(appId = toolExecutionContext.appId, appIds = resolved.appIds)
  }
  val selectorDesc = selector.description()
  val matches = when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector, target)) {
    is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
    is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
  }
  if (matches.isEmpty()) {
    return failed("$toolName: no element matched '$selectorDesc'.")
  }
  if (matches.size > 1) {
    return failed(
      "$toolName: '$selectorDesc' matched ${matches.size} elements; the captured value would " +
        "depend on resolution order. Narrow the selector so it matches exactly one element.",
    )
  }

  val detail = matches.single().driverDetail
  val text = pinnedTextField(detail, selector.driverMatch)
    ?: MatchDescriptorBuilder.extractIdentity(detail).matchedText
  if (text.isNullOrEmpty()) {
    return failed("$toolName: the element matching '$selectorDesc' carries no text to capture.")
  }
  return SelectorTextCapture.Captured(text)
}

private fun failed(message: String): SelectorTextCapture.Failed =
  SelectorTextCapture.Failed(TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message))

/**
 * The value of the one text-bearing field [match] constrained, or null when it named none or
 * several.
 *
 * Every driver collapses its several text fields into one by a fixed priority — AXe reads AXLabel
 * before AXValue — which answers the wrong field whenever the selector matched on a lower-priority
 * one. A Contacts row labels the field type ("home") and carries the number in AXValue, so
 * selecting it by `valueRegex` and reading the priority remembers "home". Only the fields a
 * driver's priority chooses between are considered, so this can only ever re-point the capture
 * within that set; a selector naming several leaves the priority to break the tie.
 *
 * Same-shape selectors only: the `iosMaestro` → AXe bridge matches one pattern against a cluster
 * of fields, so which one matched isn't recoverable there.
 */
private fun pinnedTextField(detail: DriverNodeDetail, match: DriverNodeMatch?): String? = when {
  match is DriverNodeMatch.IosAxe && detail is DriverNodeDetail.IosAxe -> listOfNotNull(
    match.labelRegex?.let { detail.label },
    match.valueRegex?.let { detail.value },
    match.titleRegex?.let { detail.title },
  )

  match is DriverNodeMatch.AndroidAccessibility &&
    detail is DriverNodeDetail.AndroidAccessibility -> listOfNotNull(
    match.textRegex?.let { detail.resolveText() },
    match.hintTextRegex?.let { detail.hintText },
    match.contentDescriptionRegex?.let { detail.contentDescription },
  )

  match is DriverNodeMatch.Compose && detail is DriverNodeDetail.Compose -> listOfNotNull(
    match.textRegex?.let { detail.resolveText() },
    match.editableTextRegex?.let { detail.editableText },
    match.contentDescriptionRegex?.let { detail.contentDescription },
  )

  match is DriverNodeMatch.AndroidMaestro && detail is DriverNodeDetail.AndroidMaestro ->
    listOfNotNull(
      match.textRegex?.let { detail.resolveText() },
      match.hintTextRegex?.let { detail.hintText },
      match.accessibilityTextRegex?.let { detail.accessibilityText },
    )

  match is DriverNodeMatch.IosMaestro && detail is DriverNodeDetail.IosMaestro -> listOfNotNull(
    match.textRegex?.let { detail.resolveText() },
    match.hintTextRegex?.let { detail.hintText },
    match.accessibilityTextRegex?.let { detail.accessibilityText },
  )

  else -> emptyList()
}.singleOrNull()

/**
 * The captured value as it may appear in a result message. A `--secret` / `rememberSensitive` key
 * is a session-lifetime redaction promise, and this message rides into logs and the LLM-facing
 * result surface — so a sensitive variable renders redacted rather than echoing the value back out.
 */
internal fun renderCaptured(
  toolExecutionContext: TrailblazeToolExecutionContext,
  variable: String,
  value: String,
): String = if (variable in toolExecutionContext.memory.sensitiveKeys) "[REDACTED]" else "'$value'"
