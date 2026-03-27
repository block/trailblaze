package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.util.encodeBase64
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable

/**
 * Raw screen state tool for getting screenshot and/or view hierarchy.
 *
 * Unlike [StepToolSet.ask] which uses an LLM to interpret the screen and keeps raw data
 * out of the caller's context, snapshot returns raw data directly to the caller.
 * Does not require an LLM.
 */
@Suppress("unused")
class SnapshotToolSet(
  private val screenStateProvider: () -> ScreenState?,
  private val sessionContext: TrailblazeMcpSessionContext? = null,
  private val driverStatusProvider: (() -> String?)? = null,
  private val logsRepo: LogsRepo? = null,
  private val sessionIdProvider: (() -> SessionId?)? = null,
  private val mcpBridge: TrailblazeMcpBridge? = null,
) : ToolSet {

  enum class SnapshotDetail {
    /** Screenshot + view hierarchy (default) */
    ALL,
    /** Screenshot only */
    SCREENSHOT,
    /** View hierarchy only */
    HIERARCHY,
  }

  @LLMDescription(
    """
    Get a raw screenshot and/or view hierarchy from the connected device.

    snapshot() → screenshot + hierarchy (default)
    snapshot(detail=SCREENSHOT) → screenshot only
    snapshot(detail=HIERARCHY) → hierarchy only
    snapshot(verbosity=FULL) → unfiltered hierarchy

    Unlike ask(), this returns raw data directly — no LLM interpretation.
    Use this when you need to see exactly what's on screen.
    """
  )
  @Tool(McpToolProfile.TOOL_SNAPSHOT)
  suspend fun snapshot(
    @LLMDescription("What to include: ALL (default), SCREENSHOT, or HIERARCHY")
    detail: SnapshotDetail = SnapshotDetail.ALL,
    @LLMDescription("Hierarchy verbosity: MINIMAL (interactable only), STANDARD, or FULL (all elements)")
    verbosity: ViewHierarchyVerbosity? = null,
  ): String {
    val screenState = screenStateProvider()
    if (screenState == null) {
      val driverStatus = driverStatusProvider?.invoke()
        ?: "No device connected. Use device(action=ANDROID), device(action=IOS), or device(action=WEB) first."
      Console.error("[snapshot] Screen state is null — driverStatus=$driverStatus")
      return SnapshotResult(error = driverStatus).toJson()
    }

    val effectiveVerbosity = verbosity
      ?: sessionContext?.viewHierarchyVerbosity
      ?: ViewHierarchyVerbosity.STANDARD

    val includeScreenshot = detail == SnapshotDetail.ALL || detail == SnapshotDetail.SCREENSHOT
    val includeHierarchy = detail == SnapshotDetail.ALL || detail == SnapshotDetail.HIERARCHY

    val screenshotBase64 = if (includeScreenshot) {
      screenState.screenshotBytes?.encodeBase64()
    } else null

    val viewHierarchyText = if (includeHierarchy) {
      formatViewHierarchy(screenState, effectiveVerbosity)
    } else null

    // Save screenshot to session directory if available
    val savedScreenshotPath = if (includeScreenshot && screenState.screenshotBytes != null) {
      // Ensure a Trailblaze session exists (device connect alone doesn't create one)
      val sessionId = sessionIdProvider?.invoke()
        ?: mcpBridge?.ensureSessionAndGetId(null)
      if (logsRepo != null && sessionId != null) {
        try {
          val filename = logsRepo.saveScreenshotBytes(sessionId, screenState.screenshotBytes!!)
          val sessionDir = logsRepo.getSessionDir(sessionId)
          java.io.File(sessionDir, filename).absolutePath
        } catch (e: Exception) {
          Console.error("[snapshot] Failed to save screenshot: ${e.message}")
          null
        }
      } else null
    } else null

    Console.log("")
    Console.log("┌──────────────────────────────────────────────────────────────────────────────")
    Console.log("│ [snapshot] detail=$detail, verbosity=$effectiveVerbosity")
    Console.log("│ Screenshot: ${if (screenshotBase64 != null) "${screenshotBase64.length} chars (base64)" else "not included"}")
    if (savedScreenshotPath != null) Console.log("│ Saved: $savedScreenshotPath")
    Console.log("│ Hierarchy: ${if (viewHierarchyText != null) "${viewHierarchyText.lines().size} lines" else "not included"}")
    Console.log("└──────────────────────────────────────────────────────────────────────────────")

    return SnapshotResult(
      screenshot = screenshotBase64,
      viewHierarchy = viewHierarchyText,
      pageContextSummary = screenState.pageContextSummary,
      deviceWidth = screenState.deviceWidth,
      deviceHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform?.name,
      screenshotPath = savedScreenshotPath,
    ).toJson()
  }

  private fun formatViewHierarchy(
    screenState: ScreenState,
    verbosity: ViewHierarchyVerbosity,
  ): String {
    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchy)

    return when (verbosity) {
      ViewHierarchyVerbosity.MINIMAL -> buildMinimalViewHierarchy(filtered)
      ViewHierarchyVerbosity.STANDARD -> buildViewHierarchyDescription(filtered)
      ViewHierarchyVerbosity.FULL -> buildFullViewHierarchy(screenState.viewHierarchy)
    }
  }

  private fun buildMinimalViewHierarchy(node: ViewHierarchyTreeNode): String {
    val elements = mutableListOf<String>()
    collectInteractableElements(node, elements)
    return if (elements.isEmpty()) {
      "No interactable elements found on screen."
    } else {
      elements.joinToString("\n")
    }
  }

  private fun collectInteractableElements(
    node: ViewHierarchyTreeNode,
    elements: MutableList<String>,
  ) {
    if (node.isInteractable()) {
      val selector = node.asTrailblazeElementSelector()
      val description = selector?.description() ?: node.className
      val position = node.centerPoint?.let { "@($it)" } ?: ""
      elements.add("- $description $position")
    }
    node.children.forEach { child -> collectInteractableElements(child, elements) }
  }

  private fun buildFullViewHierarchy(
    node: ViewHierarchyTreeNode,
    depth: Int = 0,
  ): String {
    val indent = "  ".repeat(depth)
    val className = node.className ?: "Unknown"
    val text = node.text?.let { " '$it'" } ?: ""
    val resourceId = node.resourceId?.let { " [$it]" } ?: ""
    val position = node.centerPoint?.let { " @($it)" } ?: ""
    val interactable = if (node.isInteractable()) " *" else ""

    val thisLine = "$indent$className$text$resourceId$position$interactable"

    val childDescriptions = node.children
      .map { child -> buildFullViewHierarchy(child, depth + 1) }
      .filter { it.isNotBlank() }

    return listOf(thisLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }

  private fun buildViewHierarchyDescription(
    node: ViewHierarchyTreeNode,
    depth: Int = 0,
  ): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    val thisNodeLine = if (selectorDescription != null) {
      val positionSuffix = centerPoint?.let { " @$it" } ?: ""
      "$indent$selectorDescription$positionSuffix"
    } else {
      null
    }

    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions = node.children
      .map { child -> buildViewHierarchyDescription(child, childDepth) }
      .filter { it.isNotBlank() }

    return listOfNotNull(thisNodeLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }
}

@Serializable
data class SnapshotResult(
  val screenshot: String? = null,
  val viewHierarchy: String? = null,
  val pageContextSummary: String? = null,
  val deviceWidth: Int? = null,
  val deviceHeight: Int? = null,
  val platform: String? = null,
  val error: String? = null,
  val screenshotPath: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
