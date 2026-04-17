package xyz.block.trailblaze.mcp.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import xyz.block.trailblaze.agent.ExecutionResult
import xyz.block.trailblaze.agent.UiActionExecutor
import xyz.block.trailblaze.api.AndroidCompactElementList
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.IosCompactElementList
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.SnapshotDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.utils.ScreenStateCaptureUtil
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.ConfigTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Implementation of [UiActionExecutor] that uses [TrailblazeMcpBridge] to execute UI actions.
 *
 * This executor bridges between the two-tier agent architecture and the existing
 * Trailblaze infrastructure. It converts tool name + JSON args into TrailblazeTool
 * instances and executes them via the MCP bridge.
 *
 * ## Usage
 *
 * ```kotlin
 * val executor = BridgeUiActionExecutor(mcpBridge)
 *
 * val result = executor.execute(
 *   toolName = "tapOnElementByNodeId",
 *   args = buildJsonObject { put("nodeId", "login_button") },
 *   traceId = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL),
 * )
 * ```
 *
 * @param mcpBridge The MCP bridge for device communication
 *
 * @see UiActionExecutor
 * @see ExecutionResult
 */
class BridgeUiActionExecutor(
  private val mcpBridge: TrailblazeMcpBridge,
  private val trailblazeToolRepo: TrailblazeToolRepo? = null,
  private val screenshotScalingConfig: ScreenshotScalingConfig = ScreenshotScalingConfig.DEFAULT,
) : UiActionExecutor {

  /**
   * Executes a UI action on the connected device.
   *
   * Converts the tool name and arguments to a [TrailblazeTool] and executes
   * it via the MCP bridge. Success/failure is determined by whether the
   * bridge throws an exception.
   *
   * @param toolName The name of the tool to execute (e.g., "tapOnElementByNodeId")
   * @param args The tool arguments as a JSON object
   * @param traceId Optional trace ID for logging correlation
   * @return Execution result indicating success or failure
   */
  override suspend fun execute(
    toolName: String,
    args: JsonObject,
    traceId: TraceId?,
  ): ExecutionResult {
    val startTime = System.currentTimeMillis()

    return try {
      // Validate launchApp before executing
      if (toolName == "launchApp") {
        val validationError = validateLaunchApp(args)
        if (validationError != null) {
          return ExecutionResult.Failure(
            error = validationError,
            recoverable = false,
          )
        }
      }

      // Convert tool name + args to TrailblazeTool
      val tool = try {
        mapToTrailblazeTool(toolName, args)
      } catch (e: Exception) {
        return ExecutionResult.Failure(
          error = e.message ?: "Unknown tool: $toolName",
          recoverable = true,
        )
      }

      // Intercept config tools (e.g. setActiveToolSets) that modify the agent's
      // available tool set. These operate on the TrailblazeToolRepo, not the device.
      if (tool is ConfigTrailblazeTool) {
        val configResult = trailblazeToolRepo?.let { tool.execute(it) }
          ?: TrailblazeToolResult.Success(message = "Dynamic toolsets not configured.")
        Console.log("[BridgeUiActionExecutor] ConfigTool $toolName: $configResult")
        val durationMs = System.currentTimeMillis() - startTime
        return when (configResult) {
          is TrailblazeToolResult.Success -> ExecutionResult.Success(
            screenSummaryAfter = "Config tool executed",
            durationMs = durationMs,
          )
          is TrailblazeToolResult.Error -> ExecutionResult.Failure(
            error = configResult.errorMessage,
            recoverable = true,
          )
        }
      }

      // Execute via MCP bridge - blocking so we can capture screen state after completion
      val bridgeResult = mcpBridge.executeTrailblazeTool(tool, blocking = true)
      Console.log("[BridgeUiActionExecutor] Executed $toolName: $bridgeResult")

      val durationMs = System.currentTimeMillis() - startTime

      // Capture screen state after action. The blocking executeTrailblazeTool() call above
      // already waits for the UI to settle (Maestro's Orchestra calls waitForAppToSettle(),
      // the accessibility driver calls waitForSettled()), so no additional delay is needed.
      val screenSummary = getScreenSummary() ?: "Screen state not available"

      ExecutionResult.Success(
        screenSummaryAfter = screenSummary,
        durationMs = durationMs,
      )
    } catch (e: Exception) {
      Console.log("[BridgeUiActionExecutor] Exception executing $toolName: ${e.message}")
      ExecutionResult.Failure(
        error = "Execution failed: ${e.message}",
        recoverable = true, // Most errors are recoverable
      )
    }
  }

  /**
   * Captures the current screen state from the connected device.
   *
   * @return Current screen state, or null if capture failed
   */
  override suspend fun captureScreenState(): ScreenState? {
    return try {
      ScreenStateCaptureUtil.captureScreenState(
        mcpBridge = mcpBridge,
        screenshotScalingConfig = screenshotScalingConfig
      )
    } catch (e: Exception) {
      Console.log("[BridgeUiActionExecutor] Failed to capture screen state: ${e.message}")
      null
    }
  }

  /**
   * Maps a tool name and JSON args to a TrailblazeTool using the existing JSON
   * deserialization infrastructure.
   *
   * Uses "toolName" field which is recognized by OtherTrailblazeToolSerializer.
   *
   * @throws IllegalStateException if deserialization fails or produces an untyped fallback
   */
  private fun mapToTrailblazeTool(toolName: String, args: JsonObject): TrailblazeTool {
    val toolJson = buildJsonObject {
      put("toolName", toolName)
      args.entries.forEach { (key, value) ->
        put(key, value)
      }
    }

    val tool = try {
      TrailblazeJsonInstance.decodeFromString<TrailblazeTool>(toolJson.toString())
    } catch (e: Exception) {
      error("Tool '$toolName' args do not match the expected schema: ${e.message}")
    }

    check(tool !is OtherTrailblazeTool) {
      "Tool '$toolName' could not be parsed. Verify argument names and types match the tool schema exactly. Provided args: $args"
    }

    return tool
  }

  /**
   * Checks if an app ID is an iOS system app.
   * System apps cannot be uninstalled/reinstalled.
   */
  private fun isIosSystemApp(appId: String): Boolean {
    return appId.startsWith("com.apple.")
  }

  /**
   * Validates a launchApp request before execution.
   *
   * Checks:
   * 1. App ID is provided
   * 2. App is installed on the device (for non-system apps)
   *
   * If a display name is provided (e.g. "Contacts") instead of a bundle ID, attempts
   * to resolve it via [IOS_SYSTEM_APP_DISPLAY_NAMES] before validation.
   *
   * @return Error message if validation fails, null if valid
   */
  private suspend fun validateLaunchApp(args: JsonObject): String? {
    val appId = (args["appId"] as? JsonPrimitive)?.contentOrNull
      ?: return "launchApp requires an 'appId' argument"

    // Resolve display name → bundle ID for well-known iOS system apps
    // (e.g. "Contacts" → "com.apple.MobileAddressBook")
    val resolvedAppId = IOS_SYSTEM_APP_DISPLAY_NAMES[appId.lowercase()] ?: appId

    // Skip validation for system apps (they're always "installed")
    if (isIosSystemApp(resolvedAppId)) {
      return null
    }

    // Check if app is installed
    return try {
      val installedApps = mcpBridge.getInstalledAppIds()
      if (!installedApps.contains(resolvedAppId)) {
        // Include full app list in error so LLM can retry with correct app
        val sortedApps = installedApps.sorted()

        buildString {
          appendLine("App '$appId' is not installed on the device.")
          appendLine()
          appendLine("Installed apps (${sortedApps.size} total):")
          sortedApps.forEach { app ->
            appendLine("  - $app")
          }
          appendLine()
          append("Please retry with one of the installed app IDs listed above.")
        }
      } else {
        null // Valid
      }
    } catch (e: Exception) {
      // If we can't check, let it through (validation is best-effort)
      Console.log("[BridgeUiActionExecutor] Warning: Could not validate app installation: ${e.message}")
      null
    }
  }

  /**
   * Creates a compact screen summary of interactive elements only.
   *
   * When the accessibility driver's [TrailblazeNode] tree is available, uses it for
   * richer filtering: `isImportantForAccessibility`, `packageName` (skip system UI),
   * and precise `isClickable`/`isEditable`/`isScrollable` flags.
   *
   * Falls back to [ViewHierarchyTreeNode] for Maestro-based drivers.
   */
  /** Captures the current screen state and returns a text summary, or null on failure. */
  internal suspend fun getScreenSummary(
    details: Set<SnapshotDetail> = emptySet(),
  ): String? {
    return try {
      val screenState = captureScreenState() ?: return null
      describeScreen(screenState, details)
    } catch (e: Exception) {
      null
    }
  }

  private fun describeScreen(
    screenState: ScreenState,
    details: Set<SnapshotDetail> = emptySet(),
  ): String {
    // When detail enrichment is requested, rebuild the compact list with details
    if (details.isNotEmpty()) {
      val tree = screenState.trailblazeNodeTree
      if (tree != null) {
        // Detect platform from tree — check root and first child (root may be a wrapper)
        val platform = detectPlatform(tree)
        val elements = when (platform) {
          "android" -> AndroidCompactElementList.build(tree, details, screenState.deviceHeight).text
          "ios" -> IosCompactElementList.build(tree, details, screenState.deviceHeight).text
          else -> null
        }
        if (elements != null) {
          val pageContext = screenState.pageContextSummary
          return if (pageContext != null) "$pageContext\n\n$elements" else elements
        }
      }
    }

    // Default: use the pre-computed compact text representation
    val textRepresentation = screenState.viewHierarchyTextRepresentation
    if (textRepresentation != null) {
      return textRepresentation
    }

    // Mobile/other: generate summary from TrailblazeNode tree or ViewHierarchy
    val trailblazeTree = screenState.trailblazeNodeTree
    val actionableItems = if (trailblazeTree != null) {
      describeFromTrailblazeNode(trailblazeTree)
    } else {
      describeFromViewHierarchy(screenState.viewHierarchy)
    }

    // Extract non-actionable labels (headings, titles, static text) for screen context.
    val contextLabels = if (trailblazeTree != null) {
      extractContextLabels(trailblazeTree)
    } else {
      extractContextLabelsFromVh(screenState.viewHierarchy)
    }

    val elementsSummary = buildString {
      // Context labels first — helps orient the agent without needing ask()
      if (contextLabels.isNotEmpty()) {
        for (label in contextLabels) {
          if (length + label.length + 4 > MAX_CONTEXT_LABELS_LENGTH) {
            append("...")
            break
          }
          if (isNotEmpty()) append(" | ")
          append(label)
        }
      }

      // Then actionable elements
      if (actionableItems.isNotEmpty()) {
        if (isNotEmpty()) append("\n")
        val startLen = length
        for (item in actionableItems) {
          if (length - startLen + item.length + 4 > MAX_SUMMARY_LENGTH) {
            append("...")
            break
          }
          if (length > startLen) append(" | ")
          append(item)
        }
      } else if (contextLabels.isEmpty()) {
        append("No actionable elements visible")
      }
    }

    val pageContext = screenState.pageContextSummary
    return if (pageContext != null) "$pageContext\n$elementsSummary" else elementsSummary
  }

  /**
   * Extracts actionable element labels from the rich [TrailblazeNode] tree.
   * Handles all [DriverNodeDetail] variants (Android, iOS/Maestro, Web, Compose).
   */
  private fun describeFromTrailblazeNode(root: TrailblazeNode): List<String> {
    return root.aggregate().mapNotNull { node ->
      when (val detail = node.driverDetail) {
        is DriverNodeDetail.AndroidAccessibility -> describeAndroidAccessibilityNode(detail)
        is DriverNodeDetail.AndroidMaestro -> describeAndroidMaestroNode(detail)
        is DriverNodeDetail.IosMaestro -> describeIosMaestroNode(detail)
        is DriverNodeDetail.Web -> describeWebNode(detail)
        is DriverNodeDetail.Compose -> describeComposeNode(detail)
      }
    }.distinct()
  }

  /** Extracts a label from an [DriverNodeDetail.AndroidAccessibility] node, or null if not actionable. */
  private fun describeAndroidAccessibilityNode(detail: DriverNodeDetail.AndroidAccessibility): String? {
    if (!detail.isImportantForAccessibility) return null
    if (detail.packageName?.startsWith("com.android.systemui") == true) return null
    val actionable = detail.isClickable || detail.isEditable || detail.isScrollable || detail.isCheckable
    if (!actionable) return null

    val rawLabel = detail.text?.takeIf { it.isNotBlank() }
      ?: detail.contentDescription?.takeIf { it.isNotBlank() }
      ?: detail.hintText?.takeIf { it.isNotBlank() }
      ?: return null
    val label = rawLabel.truncate(MAX_LABEL_LENGTH)
    val type = inferTypeFromAndroidAccessibility(detail)
    return if (type != null) "[$type] $label" else label
  }

  /** Extracts a label from an [DriverNodeDetail.AndroidMaestro] node, or null if not actionable. */
  private fun describeAndroidMaestroNode(detail: DriverNodeDetail.AndroidMaestro): String? {
    val actionable = detail.clickable || detail.scrollable
    if (!actionable) return null

    val rawLabel = detail.text?.takeIf { it.isNotBlank() }
      ?: detail.accessibilityText?.takeIf { it.isNotBlank() }
      ?: detail.hintText?.takeIf { it.isNotBlank() }
      ?: return null
    val label = rawLabel.truncate(MAX_LABEL_LENGTH)
    val type = inferTypeFromAndroidMaestro(detail)
    return if (type != null) "[$type] $label" else label
  }

  /** Extracts a label from an [DriverNodeDetail.IosMaestro] node, or null if not actionable. */
  private fun describeIosMaestroNode(detail: DriverNodeDetail.IosMaestro): String? {
    val actionable = detail.clickable || detail.scrollable
    if (!actionable) return null

    val rawLabel = detail.text?.takeIf { it.isNotBlank() }
      ?: detail.accessibilityText?.takeIf { it.isNotBlank() }
      ?: detail.hintText?.takeIf { it.isNotBlank() }
      ?: return null
    val label = rawLabel.truncate(MAX_LABEL_LENGTH)
    val type = inferTypeFromIosMaestro(detail)
    return if (type != null) "[$type] $label" else label
  }

  /** Infers element type from [DriverNodeDetail.IosMaestro] properties. */
  private fun inferTypeFromIosMaestro(detail: DriverNodeDetail.IosMaestro): String? {
    if (!detail.hintText.isNullOrBlank()) return ELEMENT_TYPE_INPUT
    if (detail.scrollable) return ELEMENT_TYPE_SCROLL
    if (detail.checked) return ELEMENT_TYPE_CHECKBOX
    val cls = detail.className?.substringAfterLast('.', "")?.lowercase() ?: return null
    return when {
      CLS_BUTTON in cls -> ELEMENT_TYPE_BUTTON
      CLS_SWITCH in cls || CLS_TOGGLE in cls -> ELEMENT_TYPE_TOGGLE
      CLS_TAB in cls -> ELEMENT_TYPE_TAB
      CLS_RADIO in cls -> ELEMENT_TYPE_RADIO
      CLS_TEXTFIELD in cls || "textview" in cls || "searchfield" in cls -> ELEMENT_TYPE_INPUT
      CLS_IMAGE in cls && detail.clickable -> ELEMENT_TYPE_ICON
      else -> null
    }
  }

  /** Extracts a label from a [DriverNodeDetail.Web] node, or null if not actionable. */
  private fun describeWebNode(detail: DriverNodeDetail.Web): String? {
    if (!detail.isInteractive) return null

    val rawLabel = detail.ariaName?.takeIf { it.isNotBlank() } ?: return null
    val label = rawLabel.truncate(MAX_LABEL_LENGTH)
    val type = detail.ariaRole
    return if (type != null) "[$type] $label" else label
  }

  /** Extracts a label from a [DriverNodeDetail.Compose] node, or null if not actionable. */
  private fun describeComposeNode(detail: DriverNodeDetail.Compose): String? {
    val actionable = detail.hasClickAction || detail.hasScrollAction ||
      detail.role != null || detail.editableText != null
    if (!actionable) return null

    val rawLabel = detail.editableText?.takeIf { it.isNotBlank() }
      ?: detail.text?.takeIf { it.isNotBlank() }
      ?: detail.contentDescription?.takeIf { it.isNotBlank() }
      ?: return null
    val label = rawLabel.truncate(MAX_LABEL_LENGTH)
    val type = detail.role?.lowercase()
    return if (type != null) "[$type] $label" else label
  }

  /** Infers element type from [DriverNodeDetail.AndroidAccessibility] properties. */
  private fun inferTypeFromAndroidAccessibility(detail: DriverNodeDetail.AndroidAccessibility): String? {
    if (detail.isEditable) return ELEMENT_TYPE_INPUT
    if (detail.isScrollable) return ELEMENT_TYPE_SCROLL
    if (detail.isCheckable) return ELEMENT_TYPE_CHECKBOX
    val cls = detail.className?.substringAfterLast('.', "")?.lowercase() ?: return null
    return when {
      CLS_SWITCH in cls || CLS_TOGGLE in cls -> ELEMENT_TYPE_TOGGLE
      CLS_RADIO in cls -> ELEMENT_TYPE_RADIO
      CLS_TAB in cls -> ELEMENT_TYPE_TAB
      CLS_BUTTON in cls -> ELEMENT_TYPE_BUTTON
      CLS_IMAGE in cls && detail.isClickable -> ELEMENT_TYPE_ICON
      else -> null
    }
  }

  /** Infers element type from [DriverNodeDetail.AndroidMaestro] properties. */
  private fun inferTypeFromAndroidMaestro(detail: DriverNodeDetail.AndroidMaestro): String? {
    if (!detail.hintText.isNullOrBlank()) return ELEMENT_TYPE_INPUT
    if (detail.scrollable) return ELEMENT_TYPE_SCROLL
    if (detail.checked) return ELEMENT_TYPE_CHECKBOX
    val cls = detail.className?.substringAfterLast('.', "")?.lowercase() ?: return null
    return when {
      CLS_BUTTON in cls -> ELEMENT_TYPE_BUTTON
      CLS_SWITCH in cls || CLS_TOGGLE in cls -> ELEMENT_TYPE_TOGGLE
      CLS_TAB in cls -> ELEMENT_TYPE_TAB
      CLS_RADIO in cls -> ELEMENT_TYPE_RADIO
      CLS_EDITTEXT in cls || CLS_TEXTFIELD in cls || CLS_TEXTINPUT in cls -> ELEMENT_TYPE_INPUT
      CLS_IMAGE in cls && detail.clickable -> ELEMENT_TYPE_ICON
      else -> null
    }
  }

  /** Fallback: extracts actionable element labels from the legacy [ViewHierarchyTreeNode] tree. */
  private fun describeFromViewHierarchy(root: ViewHierarchyTreeNode): List<String> {
    return stripSystemUiSubtrees(root).aggregate().mapNotNull { node ->
      val actionable = node.clickable || node.scrollable || !node.hintText.isNullOrBlank()
      if (!actionable) return@mapNotNull null
      val rawLabel = node.text?.takeIf { it.isNotBlank() }
        ?: node.accessibilityText?.takeIf { it.isNotBlank() }
        ?: node.hintText?.takeIf { it.isNotBlank() }
        ?: return@mapNotNull null
      val label = rawLabel.truncate(MAX_LABEL_LENGTH)
      val type = inferElementTypeFromVh(node)
      if (type != null) "[$type] $label" else label
    }.distinct()
  }

  /**
   * Extracts non-actionable text labels from the [TrailblazeNode] tree.
   * These are headings, titles, and static text that help orient the agent.
   * Only includes labels that are important for accessibility and have meaningful text,
   * excluding labels that are already captured as actionable elements.
   */
  private fun extractContextLabels(root: TrailblazeNode): List<String> {
    return root.aggregate().mapNotNull { node ->
      when (val detail = node.driverDetail) {
        is DriverNodeDetail.AndroidAccessibility -> {
          if (!detail.isImportantForAccessibility) return@mapNotNull null
          if (detail.packageName?.startsWith("com.android.systemui") == true) return@mapNotNull null
          // Skip actionable elements — they're already in the main list
          val actionable = detail.isClickable || detail.isEditable || detail.isScrollable || detail.isCheckable
          if (actionable) return@mapNotNull null
          detail.text?.takeIf { it.isNotBlank() }
            ?: detail.contentDescription?.takeIf { it.isNotBlank() }
        }
        is DriverNodeDetail.IosMaestro -> {
          val actionable = detail.clickable || detail.scrollable || detail.checked || !detail.hintText.isNullOrBlank()
          if (actionable) return@mapNotNull null
          detail.text?.takeIf { it.isNotBlank() }
            ?: detail.accessibilityText?.takeIf { it.isNotBlank() }
        }
        is DriverNodeDetail.AndroidMaestro -> {
          val actionable = detail.clickable || detail.scrollable || detail.checked || !detail.hintText.isNullOrBlank()
          if (actionable) return@mapNotNull null
          detail.text?.takeIf { it.isNotBlank() }
            ?: detail.accessibilityText?.takeIf { it.isNotBlank() }
        }
        else -> null
      }
    }.map { it.truncate(MAX_LABEL_LENGTH) }.distinct()
  }

  /**
   * Extracts non-actionable text labels from the legacy [ViewHierarchyTreeNode] tree.
   */
  private fun extractContextLabelsFromVh(root: ViewHierarchyTreeNode): List<String> {
    return stripSystemUiSubtrees(root).aggregate().mapNotNull { node ->
      // Match the TrailblazeNode version: skip clickable, editable (hintText proxy),
      // scrollable, and checkable (checked proxy) elements.
      val actionable = node.clickable || node.scrollable || node.checked || !node.hintText.isNullOrBlank()
      if (actionable) return@mapNotNull null
      node.text?.takeIf { it.isNotBlank() }
        ?: node.accessibilityText?.takeIf { it.isNotBlank() }
    }.map { it.truncate(MAX_LABEL_LENGTH) }.distinct()
  }

  /** Infers element type from legacy [ViewHierarchyTreeNode] properties. */
  internal fun inferElementTypeFromVh(node: ViewHierarchyTreeNode): String? {
    if (!node.hintText.isNullOrBlank()) return ELEMENT_TYPE_INPUT
    if (node.scrollable) return ELEMENT_TYPE_SCROLL
    if (node.checked) return ELEMENT_TYPE_CHECKBOX
    val cls = node.className?.substringAfterLast('.', "")?.lowercase() ?: return null
    return when {
      // More specific class names must be checked before "button", because
      // ToggleButton, RadioButton, etc. all contain "button" as a substring.
      CLS_SWITCH in cls || CLS_TOGGLE in cls -> ELEMENT_TYPE_TOGGLE
      CLS_CHECKBOX in cls || CLS_CHECK in cls -> ELEMENT_TYPE_CHECKBOX
      CLS_RADIO in cls -> ELEMENT_TYPE_RADIO
      CLS_EDITTEXT in cls || CLS_TEXTFIELD in cls || CLS_TEXTINPUT in cls -> ELEMENT_TYPE_INPUT
      CLS_TAB in cls -> ELEMENT_TYPE_TAB
      CLS_BUTTON in cls -> ELEMENT_TYPE_BUTTON
      CLS_IMAGE in cls && node.clickable -> ELEMENT_TYPE_ICON
      else -> null
    }
  }

  /** Strips system UI subtrees from the legacy view hierarchy. */
  internal fun stripSystemUiSubtrees(node: ViewHierarchyTreeNode): ViewHierarchyTreeNode {
    val resId = node.resourceId
    val isSystemUi = resId != null && isSystemUiResourceId(resId)
    return node.copy(
      children = if (isSystemUi) emptyList()
      else node.children.map { stripSystemUiSubtrees(it) }
        .filter { child ->
          val cResId = child.resourceId
          cResId == null || !isSystemUiResourceId(cResId)
        },
    )
  }

  companion object {
    private const val MAX_SUMMARY_LENGTH = 1500
    /** Max chars for context labels (headings, titles, static text). */
    private const val MAX_CONTEXT_LABELS_LENGTH = 300
    /** Max chars per element label — longer text is truncated with ellipsis. */
    private const val MAX_LABEL_LENGTH = 40

    /**
     * Mapping of lowercase iOS system app display names to their bundle IDs.
     *
     * Used in [validateLaunchApp] so that natural-language app names like "Contacts"
     * are resolved to the correct bundle ID (e.g. "com.apple.MobileAddressBook") before
     * checking whether the app is installed. System apps often do not appear under their
     * display name in the `getInstalledAppIds()` result, so without this mapping the
     * validation would incorrectly report them as "not installed".
     */
    internal val IOS_SYSTEM_APP_DISPLAY_NAMES: Map<String, String> = mapOf(
      "contacts" to "com.apple.MobileAddressBook",
      "calendar" to "com.apple.mobilecal",
      "safari" to "com.apple.mobilesafari",
      "maps" to "com.apple.Maps",
      "messages" to "com.apple.MobileSMS",
      "photos" to "com.apple.mobileslideshow",
      "settings" to "com.apple.Preferences",
      "reminders" to "com.apple.reminders",
      "health" to "com.apple.Health",
      "wallet" to "com.apple.Passbook",
      "news" to "com.apple.news",
      "files" to "com.apple.DocumentsApp",
      "shortcuts" to "com.apple.shortcuts",
      "fitness" to "com.apple.Fitness",
      "passwords" to "com.apple.Passwords",
      "podcasts" to "243LU875E5.groups.com.apple.podcasts",
    )

    // Element type constants returned by inferElementTypeFromVh / inferElementTypeFromDetail.
    internal const val ELEMENT_TYPE_BUTTON = "button"
    internal const val ELEMENT_TYPE_TOGGLE = "toggle"
    internal const val ELEMENT_TYPE_TAB = "tab"
    internal const val ELEMENT_TYPE_CHECKBOX = "checkbox"
    internal const val ELEMENT_TYPE_RADIO = "radio"
    internal const val ELEMENT_TYPE_INPUT = "input"
    internal const val ELEMENT_TYPE_SCROLL = "scroll"
    internal const val ELEMENT_TYPE_ICON = "icon"

    // Lowercase className substrings used for element type inference.
    private const val CLS_BUTTON = "button"
    private const val CLS_SWITCH = "switch"
    private const val CLS_TOGGLE = "toggle"
    private const val CLS_TAB = "tab"
    private const val CLS_CHECKBOX = "checkbox"
    private const val CLS_CHECK = "check"
    private const val CLS_RADIO = "radio"
    private const val CLS_EDITTEXT = "edittext"
    private const val CLS_TEXTFIELD = "textfield"
    private const val CLS_TEXTINPUT = "textinput"
    private const val CLS_IMAGE = "image"

    // System UI constants used by stripSystemUiSubtrees and describeFromTrailblazeNode.
    internal const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    internal const val SYSTEM_UI_PREFIX = "$SYSTEM_UI_PACKAGE:"
    internal const val RES_STATUS_BAR_BACKGROUND = "statusBarBackground"
    internal const val RES_NAVIGATION_BAR_BACKGROUND = "navigationBarBackground"

    internal fun isSystemUiResourceId(resId: String): Boolean =
      resId.startsWith(SYSTEM_UI_PREFIX) ||
        resId == RES_STATUS_BAR_BACKGROUND ||
        resId == RES_NAVIGATION_BAR_BACKGROUND
  }
}

private fun String.truncate(max: Int): String =
  if (length <= max) this else take(max - 1) + "…"

/** Detects platform from the TrailblazeNode tree by checking root and first child. */
private fun detectPlatform(tree: TrailblazeNode): String? {
  if (tree.driverDetail is DriverNodeDetail.AndroidAccessibility) return "android"
  if (tree.driverDetail is DriverNodeDetail.IosMaestro) return "ios"
  // Check first child (root may be a wrapper with generic detail)
  val firstChild = tree.children.firstOrNull() ?: return null
  if (firstChild.driverDetail is DriverNodeDetail.AndroidAccessibility) return "android"
  if (firstChild.driverDetail is DriverNodeDetail.IosMaestro) return "ios"
  return null
}
