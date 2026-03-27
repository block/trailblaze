package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.ui.InspectTrailblazeNodeSelectorHelper
import xyz.block.trailblaze.ui.InspectViewHierarchySelectorHelper
import xyz.block.trailblaze.ui.SelectorAnalysisResult
import xyz.block.trailblaze.ui.TrailblazeNodeSelectorAnalysisResult
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * JVM-specific wrapper for LiveSessionDetailComposable that provides selector computation.
 */
@Composable
fun LiveSessionDetailComposableWithSelectorSupport(
  sessionDataProvider: LiveSessionDataProvider,
  session: SessionInfo,
  toMaestroYaml: (JsonObject) -> String,
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit,
  imageLoader: ImageLoader,
  onDeleteSession: () -> Unit = {},
  onOpenLogsFolder: () -> Unit = {},
  onExportSession: () -> Unit = {},
  initialZoomOffset: Int = 0,
  initialFontScale: Float = 1f,
  initialViewMode: SessionViewMode = SessionViewMode.Timeline,
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
  // Create a factory function that creates selector computers per log.
  // Skip the legacy Maestro-based path when TrailblazeNode data is available — the
  // TrailblazeNode selector path is Maestro-free and works in all environments.
  val selectorFactory: ((TrailblazeLog) -> ((ViewHierarchyTreeNode) -> SelectorAnalysisResult)?)? = { log ->
    // Check if this log has TrailblazeNode data — if so, skip legacy selector computation
    val hasTrailblazeNodeTree = when (log) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> log.trailblazeNodeTree != null
      is TrailblazeLog.AgentDriverLog -> log.trailblazeNodeTree != null
      is TrailblazeLog.TrailblazeSnapshotLog -> log.trailblazeNodeTree != null
      else -> false
    }
    if (hasTrailblazeNodeTree) {
      // TrailblazeNode selectors handle this log — no need for legacy Maestro path
      null
    } else {
      when (log) {
        is TrailblazeLog.TrailblazeLlmRequestLog -> {
          InspectViewHierarchySelectorHelper.createSelectorComputeFunction(
            root = log.viewHierarchy,
            deviceWidth = log.deviceWidth,
            deviceHeight = log.deviceHeight,
            platform = TrailblazeDevicePlatform.ANDROID
          )
        }
        is TrailblazeLog.AgentDriverLog -> {
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
  }

  // Create a factory for TrailblazeNode selector computation per log
  val trailblazeNodeSelectorFactory: ((TrailblazeLog) -> ((TrailblazeNode) -> TrailblazeNodeSelectorAnalysisResult)?)? = { log ->
    val tree = when (log) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> log.trailblazeNodeTree
      is TrailblazeLog.AgentDriverLog -> log.trailblazeNodeTree
      is TrailblazeLog.TrailblazeSnapshotLog -> log.trailblazeNodeTree
      else -> null
    }
    tree?.let {
      val yamlSerializer: (TrailblazeNodeSelector) -> String = { selector ->
        TrailblazeYaml.defaultYamlInstance.encodeToString(
          TrailblazeNodeSelector.serializer(),
          selector,
        )
      }
      InspectTrailblazeNodeSelectorHelper.createSelectorComputeFunction(
        root = it,
        selectorSerializer = yamlSerializer,
      )
    }
  }

  LiveSessionDetailComposable(
    sessionDataProvider = sessionDataProvider,
    session = session,
    toMaestroYaml = toMaestroYaml,
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
    createTrailblazeNodeSelectorFunctionForLog = trailblazeNodeSelectorFactory,
    recordedTrailsRepo = recordedTrailsRepo,
    onRetryTest = onRetryTest,
  )
}
