package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.images.ImageLoader

@Composable
internal fun ScreenshotGallery(
  items: List<ScreenshotTimelineItem>,
  sessionStartTime: Instant,
  selectedIndex: Int,
  onSelectedIndexChanged: (Int) -> Unit,
  sessionId: String,
  imageLoader: ImageLoader,
  onFullScreenClick: (Any?, Int, Int, Int?, Int?) -> Unit,
  onShowDetails: ((TrailblazeLog) -> Unit)? = null,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
  onShowChatHistory: ((TrailblazeLog.TrailblazeLlmRequestLog) -> Unit)? = null,
  onColumnsPerRowChanged: ((Int) -> Unit)? = null,
) {
  // Clamp selection if items change
  val safeIndex = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
  val selected = items.getOrNull(safeIndex)

  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val galleryMaxHeight = if (maxHeight < 10000.dp) maxHeight else 600.dp
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Left: wrapping thumbnail grid, scrollable with auto-scroll to selected
      val thumbScrollState = rememberScrollState()
      val thumbOffsets = remember { mutableStateMapOf<Int, Int>() }

      // Auto-scroll to selected thumbnail when selection changes (e.g. from scrubber)
      LaunchedEffect(safeIndex) {
        val targetY = thumbOffsets[safeIndex] ?: return@LaunchedEffect
        thumbScrollState.animateScrollTo(
          (targetY - 40).coerceAtLeast(0), // small offset so it's not flush with the top
        )
      }

      Column(
        modifier =
          Modifier.weight(1f).heightIn(max = galleryMaxHeight).verticalScroll(thumbScrollState),
      ) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        items.forEachIndexed { index, item ->
          val isSelected = index == safeIndex
          // For items without screenshots, inherit dimensions from the nearest screenshot
          // (prefer preceding, fall back to following)
          val effectiveItem =
            if (item.screenshotFile == null) {
              items.subList(0, index).lastOrNull { it.screenshotFile != null }
                ?: items.subList(index + 1, items.size).firstOrNull { it.screenshotFile != null }
                ?: item
            } else {
              item
            }
          val isLandscape = effectiveItem.deviceWidth > effectiveItem.deviceHeight
          val thumbWidth = if (isLandscape) 180.dp else 100.dp
          val selectionMod =
            if (isSelected) {
              Modifier.background(
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                  RoundedCornerShape(10.dp),
                )
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                .padding(2.dp)
            } else {
              Modifier.border(
                  1.dp,
                  MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                  RoundedCornerShape(8.dp),
                )
                .padding(2.dp)
            }

          // Thumbnail + caption (screenshot or action card)
          val hasScreenshot = item.screenshotFile != null
          Column(
            modifier =
              Modifier.width(thumbWidth)
                .clickable { onSelectedIndexChanged(index) }
                .onGloballyPositioned { coords ->
                  thumbOffsets[index] = coords.positionInParent().y.toInt()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            if (hasScreenshot) {
              Box(modifier = Modifier.fillMaxWidth().then(selectionMod)) {
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
                )
                Box(
                  modifier =
                    Modifier.matchParentSize().clickable { onSelectedIndexChanged(index) },
                )
              }
            } else {
              // Action card for events without screenshots — sized to match screenshot thumbnails
              val isToolCall =
                item.sourceLog is TrailblazeLog.TrailblazeToolLog ||
                  item.sourceLog is TrailblazeLog.DelegatingTrailblazeToolLog
              val cardHeight =
                if (effectiveItem !== item && effectiveItem.deviceWidth > 0 && effectiveItem.deviceHeight > 0) {
                  // Match the aspect ratio of the nearest preceding screenshot
                  val aspect = effectiveItem.deviceWidth.toFloat() / effectiveItem.deviceHeight.toFloat()
                  thumbWidth / aspect
                } else {
                  if (isLandscape) 80.dp else 140.dp
                }
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .height(cardHeight)
                    .then(selectionMod)
                    .background(
                      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                      RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
              ) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    imageVector = if (isToolCall) Icons.Filled.Build else Icons.Filled.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint =
                      if (isSelected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                  )
                  if (item.toolCallName != null) {
                    Text(
                      text = item.toolCallName,
                      style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                      color =
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                      textAlign = TextAlign.Center,
                      modifier = Modifier.padding(horizontal = 4.dp),
                    )
                  }
                }
              }
            }
            Text(
              text = screenshotCaption(item),
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
              color =
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              textAlign = if (hasScreenshot) TextAlign.Start else TextAlign.Center,
              modifier = Modifier.padding(top = 2.dp).fillMaxWidth(),
            )
          }
        }
      }

      // Report columns per row based on laid-out Y offsets
      if (onColumnsPerRowChanged != null && thumbOffsets.isNotEmpty()) {
        val firstY = thumbOffsets[0]
        val cols =
          if (firstY != null) {
            thumbOffsets.count { it.value == firstY }
          } else {
            1
          }
        SideEffect { onColumnsPerRowChanged(cols.coerceAtLeast(1)) }
      }
    }

    // Right: caption row + reasoning + preview screenshot
    if (selected != null) {
      BoxWithConstraints(modifier = Modifier.weight(1f)) {
        val availableWidth = maxWidth
        val hasScreenshot = selected.screenshotFile != null
        val isLandscape = selected.deviceWidth > selected.deviceHeight
        val sourceLog = selected.sourceLog
        val iconTint =
          if (isSystemInDarkTheme()) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
          }

        val absoluteMaxW = if (isLandscape) 600.dp else 400.dp
        val previewWidth =
          if (availableWidth > absoluteMaxW) absoluteMaxW else availableWidth

        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.End,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          // Caption + action icons — full width row
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = screenshotCaption(selected),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )

            // Inspect UI icon
            if (
              sourceLog != null &&
                onShowInspectUI != null &&
                (sourceLog is TrailblazeLog.TrailblazeLlmRequestLog ||
                  (sourceLog is TrailblazeLog.AgentDriverLog &&
                    sourceLog.viewHierarchy != null) ||
                  sourceLog is TrailblazeLog.TrailblazeSnapshotLog)
            ) {
              IconButton(
                onClick = { onShowInspectUI(sourceLog) },
                modifier = Modifier.size(24.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Search,
                  contentDescription = "Inspect UI",
                  modifier = Modifier.size(16.dp),
                  tint = iconTint,
                )
              }
            }

            // Chat History icon
            if (
              sourceLog is TrailblazeLog.TrailblazeLlmRequestLog &&
                onShowChatHistory != null
            ) {
              IconButton(
                onClick = { onShowChatHistory(sourceLog) },
                modifier = Modifier.size(24.dp),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.Chat,
                  contentDescription = "Chat History",
                  modifier = Modifier.size(16.dp),
                  tint = iconTint,
                )
              }
            }

            // Info icon — full log details
            if (sourceLog != null && onShowDetails != null) {
              IconButton(
                onClick = { onShowDetails(sourceLog) },
                modifier = Modifier.size(24.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Info,
                  contentDescription = "View Details",
                  modifier = Modifier.size(16.dp),
                  tint = iconTint,
                )
              }
            }
          }

          // Tool YAML + screenshot grouped together at screenshot width, right-aligned
          Column(
            modifier = Modifier.width(previewWidth),
            verticalArrangement = Arrangement.spacedBy(0.dp),
          ) {
            // Tool call rendered as YAML
            if (selected.toolCallName != null && selected.trailblazeTool != null) {
              val yamlText =
                TrailblazeYaml.toolToYaml(selected.toolCallName, selected.trailblazeTool)
              if (yamlText.isNotBlank()) {
                val yamlShape = if (hasScreenshot) {
                  RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                } else {
                  RoundedCornerShape(8.dp)
                }
                SelectionContainer {
                  Text(
                    text = yamlText.trimEnd(),
                    style =
                      MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                      ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                      Modifier.fillMaxWidth()
                        .background(
                          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                          yamlShape,
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                  )
                }
              }
            }

            // Screenshot preview
            if (hasScreenshot) {
              val aspectRatio =
                selected.deviceWidth.toFloat() / selected.deviceHeight.toFloat()
              val maxPreviewHeight = previewWidth / aspectRatio
              val hasYaml =
                selected.toolCallName != null && selected.trailblazeTool != null
              val topCorner = if (hasYaml) 0.dp else 8.dp

              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .border(
                      1.dp,
                      MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                      RoundedCornerShape(
                        topStart = topCorner,
                        topEnd = topCorner,
                        bottomStart = 8.dp,
                        bottomEnd = 8.dp,
                      ),
                    ),
              ) {
                ScreenshotImage(
                  sessionId = sessionId,
                  screenshotFile = selected.screenshotFile,
                  deviceWidth = selected.deviceWidth,
                  deviceHeight = selected.deviceHeight,
                  clickX = selected.clickX,
                  clickY = selected.clickY,
                  action = selected.action,
                  modifier =
                    Modifier.fillMaxWidth()
                      .heightIn(max = maxPreviewHeight)
                      .padding(2.dp),
                  imageLoader = imageLoader,
                  onImageClick = onFullScreenClick,
                )
              }
            }
          }
        }
      }
    }
    }
  }
}
