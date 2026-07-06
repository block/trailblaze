package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasTraceId
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.yaml.TrailblazeYaml

/** Section title for screenshots that fall outside any objective's window. */
private const val DEFAULT_STORYBOARD_SECTION_TITLE = "Session steps"

/** Cap on rendered YAML lines per cell, mirroring the CLI storyboard exporter's clamp. */
private const val STORYBOARD_YAML_MAX_LINES = 20

/** Fixed cell width, mirroring the CLI storyboard exporter's 300px cells. */
private val STORYBOARD_CELL_WIDTH = 260.dp

internal data class StoryboardSection(
  val title: String,
  val items: List<ScreenshotTimelineItem>,
)

/**
 * Buckets every screenshot-bearing timeline item by the objective active at its timestamp,
 * mirroring `StoryboardHtmlBuilder.buildSections` (the CLI's `trailblaze report --storyboard`
 * exporter) so the in-app Storyboard tab reads the same way: one section per objective, in
 * chronological order, with any screenshots outside an objective window (e.g. a trailhead)
 * grouped under [DEFAULT_STORYBOARD_SECTION_TITLE].
 */
internal fun buildStoryboardSections(logs: List<TrailblazeLog>): List<StoryboardSection> {
  val objectives = buildObjectiveProgress(logs).sortedBy { it.startedAt }
  val allItems = buildScreenshotTimelineItems(logs).filter { it.screenshotFile != null }
  if (allItems.isEmpty()) return emptyList()

  val sections = mutableListOf<StoryboardSection>()
  var currentTitle = DEFAULT_STORYBOARD_SECTION_TITLE
  var currentItems = mutableListOf<ScreenshotTimelineItem>()
  var nextObjectiveIndex = 0

  fun flush() {
    if (currentItems.isNotEmpty()) {
      sections += StoryboardSection(currentTitle, currentItems.toList())
      currentItems = mutableListOf()
    }
  }

  for (item in allItems) {
    val itemMs = item.timestamp.toEpochMilliseconds()
    while (
      nextObjectiveIndex < objectives.size &&
      (objectives[nextObjectiveIndex].startedAt?.toEpochMilliseconds() ?: Long.MAX_VALUE) <= itemMs
    ) {
      flush()
      currentTitle = objectives[nextObjectiveIndex].prompt.ifBlank { DEFAULT_STORYBOARD_SECTION_TITLE }
      nextObjectiveIndex++
    }
    currentItems.add(item)
  }
  flush()
  return sections
}

/** Whether an item's source log shares a traceId with an LLM request — "the LLM decided this". */
private fun isAiGenerated(item: ScreenshotTimelineItem, llmTraceIds: Set<String>): Boolean {
  val traceId = (item.sourceLog as? HasTraceId)?.traceId?.traceId ?: return false
  return traceId in llmTraceIds
}

/** Tool-call YAML for a cell, matching the CLI storyboard exporter's per-cell YAML snippet. */
private fun toolYamlForItem(item: ScreenshotTimelineItem): String? {
  val toolName = item.toolCallName ?: return null
  val tool = item.trailblazeTool ?: return null
  val yaml = runCatching { TrailblazeYaml.toolToYaml(toolName, tool).trimEnd() }.getOrNull()
  if (yaml.isNullOrBlank()) return null
  val lines = yaml.lines()
  return if (lines.size > STORYBOARD_YAML_MAX_LINES) {
    lines.take(STORYBOARD_YAML_MAX_LINES).joinToString("\n")
  } else {
    yaml
  }
}

/**
 * Storyboard tab: every screenshot from the session laid out as a scrollable, section-grouped
 * grid, in chronological order, so the whole run can be scanned at a glance instead of scrubbing
 * through the Timeline one step at a time. Mirrors the layout of `trailblaze report`'s exported
 * storyboard HTML/webp (sections per objective, step badges, AI/REC source chips, tool-call
 * YAML per cell), rendered natively here since the report already runs as Compose/WASM.
 */
@Composable
internal fun StoryboardTabComposable(
  logs: List<TrailblazeLog>,
  sessionId: String,
  imageLoader: ImageLoader = NetworkImageLoader(),
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit = { _, _, _, _, _, _ -> },
) {
  var sections by remember { mutableStateOf<List<StoryboardSection>>(emptyList()) }
  var llmTraceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
  var isComputing by remember { mutableStateOf(true) }

  LaunchedEffect(logs) {
    isComputing = true
    withContext(Dispatchers.Default) {
      val computedSections = buildStoryboardSections(logs)
      val computedLlmTraceIds = logs.filterIsInstance<TrailblazeLog.TrailblazeLlmRequestLog>()
        .mapTo(mutableSetOf()) { it.traceId.traceId }
      sections = computedSections
      llmTraceIds = computedLlmTraceIds
    }
    isComputing = false
  }

  if (sections.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = if (isComputing) "Loading storyboard..." else "No screenshots available for this session",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  val scrollState = rememberScrollState()
  var stepNumber = 0
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(scrollState),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    sections.forEach { section ->
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StoryboardSectionHeader(title = section.title, stepCount = section.items.size)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          section.items.forEach { item ->
            stepNumber++
            StoryboardCell(
              stepNumber = stepNumber,
              item = item,
              aiGenerated = isAiGenerated(item, llmTraceIds),
              sessionId = sessionId,
              imageLoader = imageLoader,
              onShowScreenshotModal = onShowScreenshotModal,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun StoryboardSectionHeader(title: String, stepCount: Int) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(IntrinsicSize.Min)
      .clip(RoundedCornerShape(6.dp))
      .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
  ) {
    Box(
      modifier = Modifier
        .width(3.dp)
        .fillMaxHeight()
        .background(MaterialTheme.colorScheme.primary),
    )
    Row(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f, fill = false),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "$stepCount step${if (stepCount != 1) "s" else ""}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun StoryboardCell(
  stepNumber: Int,
  item: ScreenshotTimelineItem,
  aiGenerated: Boolean,
  sessionId: String,
  imageLoader: ImageLoader,
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit,
) {
  Column(
    modifier = Modifier
      .width(STORYBOARD_CELL_WIDTH)
      .border(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        RoundedCornerShape(8.dp),
      )
      .clip(RoundedCornerShape(8.dp)),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      ScreenshotImage(
        sessionId = sessionId,
        screenshotFile = item.screenshotFile,
        deviceWidth = item.deviceWidth,
        deviceHeight = item.deviceHeight,
        clickX = item.clickX,
        clickY = item.clickY,
        action = item.action,
        modifier = Modifier.fillMaxWidth(),
        imageLoader = imageLoader,
        onImageClick = { imageModel, deviceWidth, deviceHeight, clickX, clickY ->
          onShowScreenshotModal(imageModel, deviceWidth, deviceHeight, clickX, clickY, item.action)
        },
      )
      Box(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(4.dp)
          .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
          .padding(horizontal = 6.dp, vertical = 2.dp),
      ) {
        Text(
          text = "$stepNumber",
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          fontWeight = FontWeight.Bold,
        )
      }
      val chipColor = if (aiGenerated) Color(0xFF1F883D) else Color(0xFFCF222E)
      Box(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(4.dp)
          .background(chipColor.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
          .padding(horizontal = 6.dp, vertical = 2.dp),
      ) {
        Text(
          text = if (aiGenerated) "AI" else "REC",
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
          color = Color.White,
          fontWeight = FontWeight.Bold,
        )
      }
    }
    val yaml = toolYamlForItem(item)
    if (yaml != null) {
      Text(
        text = yaml,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(horizontal = 8.dp, vertical = 6.dp),
      )
    } else {
      Text(
        text = screenshotCaption(item),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp),
      )
    }
  }
}
