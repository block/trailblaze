package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.InspectViewHierarchySelectorHelper
import xyz.block.trailblaze.ui.SelectorAnalysisResult
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo

/**
 * JVM-specific wrapper for LiveSessionDetailComposable that provides selector computation.
 */
@Composable
fun LiveSessionDetailComposableWithSelectorSupport(
  sessionDataProvider: LiveSessionDataProvider,
  session: SessionInfo,
  toMaestroYaml: (JsonObject) -> String,
  toTrailblazeYaml: (toolName: String, trailblazeTool: TrailblazeTool) -> String,
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit,
  imageLoader: ImageLoader,
  onDeleteSession: () -> Unit = {},
  onOpenLogsFolder: () -> Unit = {},
  onExportSession: () -> Unit = {},
  initialZoomOffset: Int = 0,
  initialFontScale: Float = 1f,
  initialViewMode: SessionViewMode = SessionViewMode.List,
  onZoomOffsetChanged: (Int) -> Unit = {},
  onFontScaleChanged: (Float) -> Unit = {},
  onViewModeChanged: (SessionViewMode) -> Unit = {},
  inspectorScreenshotWidth: Int = TrailblazeServerState.DEFAULT_UI_INSPECTOR_SCREENSHOT_WIDTH,
  inspectorDetailsWeight: Float = 1f,
  inspectorHierarchyWeight: Float = 1f,
  initialInspectorFontScale: Float = TrailblazeServerState.DEFAULT_UI_INSPECTOR_FONT_SCALE,
  onInspectorDetailsWeightChanged: (Float) -> Unit = {},
  onInspectorHierarchyWeightChanged: (Float) -> Unit = {},
  onInspectorFontScaleChanged: (Float) -> Unit = {},
  onOpenInFinder: ((TrailblazeLog) -> Unit)? = null,
  onRevealRecordingInFinder: ((String) -> Unit)? = null,
  recordedTrailsRepo: RecordedTrailsRepo? = null,
  // Retry callback - called when user clicks retry FAB on a failed session
  onRetryTest: (() -> Unit)? = null,
) {
  // Create a factory function that creates selector computers per log
  // This avoids caching issues where the wrong hierarchy is used for different logs
  val selectorFactory: ((TrailblazeLog) -> ((ViewHierarchyTreeNode) -> SelectorAnalysisResult)?)? = { log ->
    when (log) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> {
        InspectViewHierarchySelectorHelper.createSelectorComputeFunction(
          root = log.viewHierarchy,
          deviceWidth = log.deviceWidth,
          deviceHeight = log.deviceHeight,
          platform = TrailblazeDevicePlatform.ANDROID
        )
      }
      is TrailblazeLog.MaestroDriverLog -> {
        log.viewHierarchy?.let { hierarchy ->
          InspectViewHierarchySelectorHelper.createSelectorComputeFunction(
            root = hierarchy,
            deviceWidth = log.deviceWidth,
            deviceHeight = log.deviceHeight,
            platform = TrailblazeDevicePlatform.ANDROID
          )
        }
      }
      else -> null
    }
  }

  LiveSessionDetailComposable(
    sessionDataProvider = sessionDataProvider,
    session = session,
    toMaestroYaml = toMaestroYaml,
    toTrailblazeYaml = toTrailblazeYaml,
    generateRecordingYaml = generateRecordingYaml,
    onBackClick = onBackClick,
    imageLoader = imageLoader,
    onDeleteSession = onDeleteSession,
    onOpenLogsFolder = onOpenLogsFolder,
    onExportSession = onExportSession,
    initialZoomOffset = initialZoomOffset,
    initialFontScale = initialFontScale,
    initialViewMode = initialViewMode,
    onZoomOffsetChanged = onZoomOffsetChanged,
    onFontScaleChanged = onFontScaleChanged,
    onViewModeChanged = onViewModeChanged,
    inspectorScreenshotWidth = inspectorScreenshotWidth,
    inspectorDetailsWeight = inspectorDetailsWeight,
    inspectorHierarchyWeight = inspectorHierarchyWeight,
    initialInspectorFontScale = initialInspectorFontScale,
    onInspectorDetailsWeightChanged = onInspectorDetailsWeightChanged,
    onInspectorHierarchyWeightChanged = onInspectorHierarchyWeightChanged,
    onInspectorFontScaleChanged = onInspectorFontScaleChanged,
    onOpenInFinder = onOpenInFinder,
    onRevealRecordingInFinder = onRevealRecordingInFinder,
    computeSelectorOptions = null, // No longer using cached function
    createSelectorFunctionForLog = selectorFactory, // Pass factory instead
    recordedTrailsRepo = recordedTrailsRepo,
    onRetryTest = onRetryTest,
  )
}
