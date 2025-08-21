package xyz.block.trailblaze.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.LlmMessage
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.theme.isDarkTheme
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.items as gridItems

data class LogCardData(
  val title: String,
  val duration: Long?,
  val screenshotFile: String? = null,
  val deviceWidth: Int? = null,
  val deviceHeight: Int? = null,
  val preformattedText: String? = null,
)

@Composable
fun SelectableText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
  color: Color = Color.Unspecified,
  fontWeight: FontWeight? = null,
) {
  SelectionContainer {
    Text(
      text = text,
      modifier = modifier,
      style = style,
      color = color,
      fontWeight = fontWeight
    )
  }
}

private const val BASE_URL = "http://localhost:52525"

interface ImageLoader {
  fun getImageModel(sessionId: String, screenshotFile: String?): Any?
}

class NetworkImageLoader : ImageLoader {
  override fun getImageModel(sessionId: String, screenshotFile: String?): String? {
    return screenshotFile?.let { filename ->
      if (filename.startsWith("http")) {
        // Already a full URL
        filename
      } else {
        // Construct local server URL
        "$BASE_URL/static/$sessionId/$filename"
      }
    }
  }
}

class FileSystemImageLoader(private val basePath: String) : ImageLoader {
  override fun getImageModel(sessionId: String, screenshotFile: String?): String? {
    return screenshotFile?.let { filename ->
      if (filename.startsWith("/") || filename.contains("://")) {
        // Already an absolute path or URL
        filename
      } else {
        // Construct file system path
        "$basePath/$sessionId/$filename"
      }
    }
  }
}

// Platform-specific function to create FileSystemImageLoader for the logs directory
// Implementation is provided in platform-specific source sets (jvmMain, etc.)
expect fun createLogsFileSystemImageLoader(): ImageLoader

@Composable
fun ScreenshotImage(
  sessionId: String,
  screenshotFile: String?,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  modifier: Modifier = Modifier,
  imageLoader: ImageLoader = NetworkImageLoader(),
) {
  var showImageDialog by remember { mutableStateOf(false) }
  val imageModel = imageLoader.getImageModel(sessionId, screenshotFile)

  if (imageModel != null) {
    BoxWithConstraints(
      modifier = modifier
        .aspectRatio(deviceWidth.toFloat() / deviceHeight.toFloat())
        .clip(RoundedCornerShape(8.dp))
        .clickable { showImageDialog = true }
    ) {
      // Base image
      AsyncImage(
        model = imageModel,
        contentDescription = "Screenshot",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Fit
      )

      // Overlay click point if provided
      if (clickX != null && clickY != null && deviceWidth > 0 && deviceHeight > 0) {
        val xRatio = clickX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
        val yRatio = clickY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
        val dotSize = 15.dp
        // Compute center point offsets in Dp using available space
        val centerX = maxWidth * xRatio
        val centerY = maxHeight * yRatio
        val offsetX = centerX - (dotSize / 2)
        val offsetY = centerY - (dotSize / 2)

        // Red dot (behind) with black outline
        Box(
          modifier = Modifier
            .size(dotSize)
            .offset(
              x = offsetX.coerceIn(0.dp, maxWidth - dotSize),
              y = offsetY.coerceIn(0.dp, maxHeight - dotSize)
            )
            .background(Color.Red, shape = CircleShape)
            .border(width = 3.dp, color = Color.White, shape = CircleShape)
        )
      }
    }

    // Image preview modal
    if (showImageDialog) {
      Dialog(onDismissRequest = { showImageDialog = false }) {
        ImagePreviewDialog(
          imageModel = imageModel,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
          clickX = clickX,
          clickY = clickY,
          onDismiss = { showImageDialog = false }
        )
      }
    }
  }
}

@Composable
fun ImagePreviewDialog(
  imageModel: Any,
  deviceWidth: Int,
  deviceHeight: Int,
  clickX: Int? = null,
  clickY: Int? = null,
  onDismiss: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.8f),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    )
  ) {
    Box(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      contentAlignment = Alignment.Center
    ) {
      BoxWithConstraints {
        // Base image
        AsyncImage(
          model = imageModel,
          contentDescription = "Screenshot Preview",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit
        )

        // Overlay click point if provided
        if (clickX != null && clickY != null && deviceWidth > 0 && deviceHeight > 0) {
          val xRatio = clickX.coerceAtLeast(0).toFloat() / deviceWidth.toFloat()
          val yRatio = clickY.coerceAtLeast(0).toFloat() / deviceHeight.toFloat()
          val dotSize = 18.dp

          val imageAspectRatio = deviceWidth.toFloat() / deviceHeight.toFloat()
          val boxAspectRatio = maxWidth / maxHeight

          val imageWidth: Dp
          val imageHeight: Dp
          if (imageAspectRatio > boxAspectRatio) {
            imageWidth = maxWidth
            imageHeight = maxWidth / imageAspectRatio
          } else {
            imageHeight = maxHeight
            imageWidth = maxHeight * imageAspectRatio
          }

          val offsetX = ((maxWidth - imageWidth) / 2) + (imageWidth * xRatio)
          val offsetY = ((maxHeight - imageHeight) / 2) + (imageHeight * yRatio)

          // Red dot with black outline
          Box(
            modifier = Modifier
              .size(dotSize)
              .offset(
                x = (offsetX - (dotSize / 2)).coerceIn(0.dp, maxWidth - dotSize),
                y = (offsetY - (dotSize / 2)).coerceIn(0.dp, maxHeight - dotSize)
              )
              .background(Color.Red, shape = CircleShape)
              .border(width = 3.dp, color = Color.White, shape = CircleShape)
          )
        }
      }
    }
  }
}

fun AgentTaskStatus.isSuccess(): Boolean = when (this) {
  is AgentTaskStatus.Success -> true
  is AgentTaskStatus.Failure -> false
  is AgentTaskStatus.InProgress -> false
}

fun SessionStatus.isSuccess(): Boolean = when (this) {
  is SessionStatus.Started -> false
  is SessionStatus.Ended.Succeeded -> true
  is SessionStatus.Ended.Failed -> false
  SessionStatus.Unknown -> false
}

data class SessionDetail(
  val session: SessionInfo,
  val logs: List<TrailblazeLog>,
  val llmUsageSummary: String? = null,
  val overallStatus: SessionStatus? = null,
  val deviceName: String? = null,
  val deviceType: String? = null,
)

@Composable
fun SessionDetailComposable(
  details: SessionDetail,
  toMaestroYaml: (JsonObject) -> String = { it.toString() },
  generateRecordingYaml: () -> String,
  onKotlinRecordingClick: () -> Unit = {},
  onMaestroRecordingClick: () -> Unit = {},
  onTrailblazeRecordingClick: () -> Unit = {},
  onSimpleRecordingClick: () -> Unit = {},
  onLLMMessagesClick: () -> Unit = {},
  onBackClick: () -> Unit = {},
  imageLoader: ImageLoader = NetworkImageLoader(),
) {
  if (details.logs.isEmpty()) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
      // Header with title and action buttons
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = onBackClick) {
            Icon(
              imageVector = Icons.Default.ArrowBack,
              contentDescription = "Back to sessions",
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          SelectableText(
            text = "Trailblaze Logs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      SelectableText(
        text = "No logs available for Session \"${details.session.sessionId}\"",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(16.dp)
      )
    }
  } else {
    val gridState = rememberLazyGridState()
    val sizeOptions = listOf(140.dp, 200.dp, 260.dp, 320.dp, 400.dp, 480.dp)
    var sizeIndex by remember { mutableStateOf(0) }
    var viewMode by remember { mutableStateOf(SessionViewMode.Grid) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      // Header item
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(onClick = onBackClick) {
            Icon(
              imageVector = Icons.Default.ArrowBack,
              contentDescription = "Back to sessions",
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          SelectableText(
            text = "Trailblaze Logs",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          // View mode toggle
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewMode = SessionViewMode.Grid }) {
              Text(
                text = "Grid",
                color = if (viewMode == SessionViewMode.Grid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
            TextButton(onClick = { viewMode = SessionViewMode.List }) {
              Text(
                text = "List",
                color = if (viewMode == SessionViewMode.List) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
            TextButton(onClick = { viewMode = SessionViewMode.Recording }) {
              Text(
                text = "Recording",
                color = if (viewMode == SessionViewMode.Recording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
          }
          if (viewMode == SessionViewMode.Grid) {
            Spacer(modifier = Modifier.width(16.dp))
            SelectableText(
              text = "Size",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
              value = sizeIndex.toFloat(),
              onValueChange = { v -> sizeIndex = v.roundToInt().coerceIn(0, sizeOptions.lastIndex) },
              valueRange = 0f..sizeOptions.lastIndex.toFloat(),
              steps = sizeOptions.size - 2,
              modifier = Modifier.width(200.dp)
            )
          }
        }
      }

      // Session summary item
      SessionSummaryRow(
        status = details.overallStatus,
        deviceName = details.deviceName,
        deviceType = details.deviceType,
      )

      // Spacer item
      Spacer(modifier = Modifier.height(16.dp))

      Column(
        modifier = Modifier.fillMaxSize()
      ) {
        when (viewMode) {
          SessionViewMode.Grid -> {
            LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = sizeOptions[sizeIndex]),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              state = gridState,
              modifier = Modifier.fillMaxSize()
            ) {
              // Log items
              gridItems(details.logs) { log ->
                LogCard(
                  log = log,
                  sessionId = details.session.sessionId,
                  toMaestroYaml = toMaestroYaml,
                  imageLoader = imageLoader
                )
              }
            }
          }

          SessionViewMode.List -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(1),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              state = gridState,
              modifier = Modifier.fillMaxSize()
            ) {
              gridItems(details.logs) { log ->
                LogListRow(log = log, sessionId = details.session.sessionId, imageLoader = imageLoader)
              }
            }
          }

          SessionViewMode.Recording -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(1),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
              state = gridState,
              modifier = Modifier.fillMaxSize()
            ) {
              item {
                Column(modifier = Modifier.padding(12.dp)) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Text(
                      text = "Session Recording",
                      style = MaterialTheme.typography.headlineSmall,
                      fontWeight = FontWeight.Bold,
                    )
                    val clipboardManager = LocalClipboardManager.current
                    Button(
                      onClick = {
                        clipboardManager.setText(AnnotatedString(generateRecordingYaml()))
                      }
                    ) {
                      Text("Copy Yaml")
                    }
                  }
                  CodeBlock(text = generateRecordingYaml())
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun LogCard(
  log: TrailblazeLog,
  sessionId: String,
  toMaestroYaml: (JsonObject) -> String,
  imageLoader: ImageLoader = NetworkImageLoader(),
) {
  var showDetailsDialog by remember { mutableStateOf(false) }
  val logData = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> LogCardData(
      title = "Tool: ${log.toolName}",
      duration = log.durationMs,
      preformattedText = buildString {
        appendLine(log.command.toString())
      }
    )

    is TrailblazeLog.MaestroCommandLog -> LogCardData(
      title = "Maestro Command",
      duration = log.durationMs,
      preformattedText = toMaestroYaml(log.maestroCommandJsonObj)
    )

    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> LogCardData(
      title = "Agent Task Status",
      duration = log.durationMs,
      preformattedText = log.agentTaskStatus.toString()
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> LogCardData(
      title = "LLM Request",
      duration = log.durationMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.MaestroDriverLog -> LogCardData(
      title = "Maestro Driver",
      duration = null,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
      preformattedText = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log.action)
    )

    is TrailblazeLog.DelegatingTrailblazeToolLog -> LogCardData(
      title = "Delegating Tool",
      duration = null,
      preformattedText = buildString {
        appendLine(log.command.toString())
        appendLine(log.executableTools.map { it.toString() }.joinToString("\n"))
      }
    )

    is TrailblazeLog.ObjectiveStartLog -> LogCardData(
      title = "Objective Start",
      duration = null,
      preformattedText = log.promptStep.step
    )

    is TrailblazeLog.ObjectiveCompleteLog -> LogCardData(
      title = "Objective Complete",
      duration = null,
      preformattedText = log.promptStep.step
    )

    is TrailblazeLog.TopLevelMaestroCommandLog -> LogCardData(
      title = "Top Level Maestro Command",
      duration = null,
      preformattedText = log.command
    )

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> LogCardData(
      title = "Session Status",
      duration = null,
      preformattedText = log.sessionStatus.toString()
    )
  }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant
    )
  ) {
    Column(
      modifier = Modifier.padding(12.dp)
    ) {
      SelectableText(
        text = logData.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
      )

      Spacer(modifier = Modifier.height(4.dp))

      logData.duration?.let {
        SelectableText(
          text = "Duration: ${it}ms",
          style = MaterialTheme.typography.bodySmall
        )
      }

      logData.preformattedText?.let { preformattedText ->
        CodeBlock(
          text = preformattedText
        )
      }

      // Add screenshot if available
      if (logData.screenshotFile != null && logData.deviceWidth != null && logData.deviceHeight != null) {
        Spacer(modifier = Modifier.height(8.dp))
        // Determine click coordinates for MaestroDriverLog TapPoint/LongPressPoint
        val (clickX, clickY) =
          if (log is TrailblazeLog.MaestroDriverLog) {
            val action = log.action
            if (action is HasClickCoordinates) action.x to action.y else null to null
          } else null to null
        ScreenshotImage(
          sessionId = sessionId,
          screenshotFile = logData.screenshotFile,
          deviceWidth = logData.deviceWidth,
          deviceHeight = logData.deviceHeight,
          clickX = clickX,
          clickY = clickY,
          modifier = Modifier.fillMaxWidth(),
          imageLoader = imageLoader
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Button(
        onClick = { showDetailsDialog = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.secondary
        )
      ) {
        Text("View Details")
      }
    }
  }

  // Modal Dialog for detailed view
  if (showDetailsDialog) {
    Dialog(onDismissRequest = { showDetailsDialog = false }) {
      LogDetailsDialog(
        log = log,
        onDismiss = { showDetailsDialog = false }
      )
    }
  }
}

@Composable
fun LogDetailsDialog(
  log: TrailblazeLog,
  onDismiss: () -> Unit,
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.8f),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    )
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth()
    ) {
      // Header item
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = getLogTypeDisplayName(log),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Close")
          }
        }
      }

      // Flatten all the content directly into LazyColumn items
      when (log) {
        is TrailblazeLog.TrailblazeLlmRequestLog -> {
          item {
            LlmRequestDetailsFlat(log)
          }
        }

        is TrailblazeLog.MaestroDriverLog -> {
          item {
            MaestroDriverDetailsFlat(log)
          }
        }

        is TrailblazeLog.TrailblazeToolLog -> {
          item {
            TrailblazeCommandDetailsFlat(log)
          }
        }

        is TrailblazeLog.DelegatingTrailblazeToolLog -> {
          item {
            DelegatingTrailblazeToolDetailsFlat(log)
          }
        }

        is TrailblazeLog.MaestroCommandLog -> {
          item {
            MaestroCommandDetailsFlat(log)
          }
        }

        is TrailblazeLog.TopLevelMaestroCommandLog -> {
          item {
            TopLevelMaestroCommandDetailsFlat(log)
          }
        }

        is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> {
          item {
            AgentTaskStatusDetailsFlat(log)
          }
        }

        is TrailblazeLog.TrailblazeSessionStatusChangeLog -> {
          item {
            SessionStatusDetailsFlat(log)
          }
        }

        is TrailblazeLog.ObjectiveStartLog -> {
          item {
            ObjectiveStartDetailsFlat(log)
          }
        }

        is TrailblazeLog.ObjectiveCompleteLog -> {
          item {
            ObjectiveCompleteDetailsFlat(log)
          }
        }
      }

      // Bottom padding
      item {
        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }
}

// Simplified flat detail composables that don't use nested Column layouts

@Composable
fun LlmRequestDetailsFlat(log: TrailblazeLog.TrailblazeLlmRequestLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("LLM Response") {
      log.llmResponse.forEach { response ->
        CodeBlock(response.toString())
        if (log.llmResponse.last() != response) {
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }

    DetailSection("Request Duration") {
      Text("${log.durationMs}ms")
    }

    if (log.llmMessages.isNotEmpty()) {
      DetailSection("LLM Messages") {
        log.llmMessages.forEach { message ->
          CodeBlock(message.message ?: "")
          if (log.llmMessages.last() != message) {
            Spacer(modifier = Modifier.height(8.dp))
          }
        }
      }
    }

    DetailSection("Actions Returned") {
      log.actions.forEach { action ->
        CodeBlock(action.toString())
        if (log.actions.last() != action) {
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }

    DetailSection("Chat History") {
      if (log.llmMessages.isNotEmpty()) {
        log.llmMessages.forEach { message ->
          ChatMessage(message)
        }
      } else {
        Text("No chat history available.", style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
fun MaestroDriverDetailsFlat(log: TrailblazeLog.MaestroDriverLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Class Name") {
      CodeBlock(log.action::class.simpleName ?: "Unknown")
    }

    DetailSection("Raw Log") {
      CodeBlock(log.toString())
    }
  }
}

@Composable
fun TrailblazeCommandDetailsFlat(log: TrailblazeLog.TrailblazeToolLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Trailblaze Command (JSON)") {
      CodeBlock(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log))
    }
  }
}

@Composable
fun DelegatingTrailblazeToolDetailsFlat(log: TrailblazeLog.DelegatingTrailblazeToolLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Delegating Trailblaze Tool (JSON)") {
      CodeBlock(log.toString())
    }
  }
}

@Composable
fun MaestroCommandDetailsFlat(log: TrailblazeLog.MaestroCommandLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Maestro Command (YAML)") {
      CodeBlock(log.toString())
    }
  }
}

@Composable
fun TopLevelMaestroCommandDetailsFlat(log: TrailblazeLog.TopLevelMaestroCommandLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Top-Level Maestro Command") {
      CodeBlock(log.command)
    }
  }
}

@Composable
fun AgentTaskStatusDetailsFlat(log: TrailblazeLog.TrailblazeAgentTaskStatusChangeLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Agent Task Status") {
      Text("Status Type: ${log.agentTaskStatus::class.simpleName}")
      Spacer(modifier = Modifier.height(8.dp))
      Text("Prompt:", fontWeight = FontWeight.Bold)
      CodeBlock(log.agentTaskStatus.statusData.prompt)
    }
  }
}

@Composable
fun SessionStatusDetailsFlat(log: TrailblazeLog.TrailblazeSessionStatusChangeLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Session Status") {
      CodeBlock(log.sessionStatus.toString())
    }
  }
}

@Composable
fun ObjectiveStartDetailsFlat(log: TrailblazeLog.ObjectiveStartLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Objective Start") {
      CodeBlock(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log))
    }
  }
}

@Composable
fun ObjectiveCompleteDetailsFlat(log: TrailblazeLog.ObjectiveCompleteLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Objective Complete") {
      CodeBlock(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log))
    }
    DetailSection("Result") {
      CodeBlock(log.objectiveResult.toString())
    }
    DetailSection("Prompt") {
      CodeBlock(log.objectiveResult.statusData.prompt)
    }
  }
}

@Composable
fun DetailSection(title: String, content: @Composable () -> Unit) {
  Column {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 8.dp)
    )
    content()
    Spacer(modifier = Modifier.height(4.dp))
  }
}

@Composable
fun CodeBlock(text: String) {
  val backgroundColor = if (isDarkTheme()) Color(0xFF2D2D2D) else Color(0xFFF6F8FA)
  val textColor = if (isDarkTheme()) Color(0xFFE0E0E0) else Color.Black
  val borderColor = if (isDarkTheme()) Color(0xFF444444) else Color(0xFFE1E4E8)

  Surface(
    color = backgroundColor,
    shape = RoundedCornerShape(6.dp),
    border = BorderStroke(1.dp, borderColor),
    modifier = Modifier.fillMaxWidth()
  ) {
    LazyRow(modifier = Modifier.padding(16.dp)) {
      item {
        SelectableText(
          text = text,
          color = textColor,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
      }
    }
  }
}

@Composable
fun ChatMessage(message: LlmMessage) {
  val backgroundColor = when (message.role) {
    "user" -> MaterialTheme.colorScheme.primaryContainer
    "assistant" -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.tertiaryContainer
  }

  Column(modifier = Modifier.padding(bottom = 8.dp)) {
    Text(
      text = message.role.replaceFirstChar { it.uppercaseChar() },
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(bottom = 4.dp)
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
      SelectionContainer {
        Text(
          text = message.message?.replace("data:image/png;base64,[A-Za-z0-9+/=]+".toRegex(), "[screenshot removed]")
            ?: "",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(12.dp)
        )
      }
    }
  }
}

fun getLogTypeDisplayName(log: TrailblazeLog): String {
  return when (log) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> "LLM Request"
    is TrailblazeLog.MaestroDriverLog -> "Maestro Driver"
    is TrailblazeLog.TrailblazeToolLog -> "Trailblaze Command"
    is TrailblazeLog.DelegatingTrailblazeToolLog -> "Delegating Trailblaze Tool"
    is TrailblazeLog.MaestroCommandLog -> "Maestro Command"
    is TrailblazeLog.TopLevelMaestroCommandLog -> "Top-Level Maestro Command"
    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> "Agent Task Status"
    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> "Session Status"
    is TrailblazeLog.ObjectiveStartLog -> "Objective Start"
    is TrailblazeLog.ObjectiveCompleteLog -> "Objective Complete"
  }
}

enum class SessionViewMode { Grid, List, Recording }

@Composable
fun SessionSummaryRow(
  status: SessionStatus?,
  deviceName: String?,
  deviceType: String?,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.CenterVertically
    ) {
      status?.let {
        StatusBadge(status = status)
      }
      Spacer(modifier = Modifier.width(16.dp))
      SelectableText(
        text = "Device: ${deviceName ?: "Unknown"}${deviceType?.let { " ($it)" } ?: ""}",
        style = MaterialTheme.typography.bodyMedium
      )
    }
  }
}

@Composable
fun StatusBadge(
  status: SessionStatus,
  modifier: Modifier = Modifier,
) {
  val (label, bg, fg) = when {
    (status is SessionStatus.Started) -> Triple(
      "In Progress",
      Color(0xFFFFF3CD), // Light amber background - standard for in-progress
      Color(0xFF856404)  // Dark amber text
    )

    status.isSuccess() -> Triple(
      "Succeeded",
      Color(0xFFD4F6D4), // Light green background - standard for success
      Color(0xFF0F5132)  // Dark green text
    )

    else -> Triple(
      "Failed",
      Color(0xFFF8D7DA),
      Color(0xFF721C24)
    )  // Light red background - standard for failure, dark red text
  }
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = bg)
  ) {
    Text(
      text = label,
      color = fg,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
  }
}

@Composable
fun LogListRow(log: TrailblazeLog, sessionId: String, imageLoader: ImageLoader = NetworkImageLoader()) {
  var showDetailsDialog by remember { mutableStateOf(false) }
  val data = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> LogCardData(
      title = "Tool: ${log.toolName}",
      duration = log.durationMs
    )

    is TrailblazeLog.MaestroCommandLog -> LogCardData(
      title = "Maestro Command",
      duration = log.durationMs
    )

    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> LogCardData(
      title = "Agent Task Status",
      duration = log.durationMs,
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> LogCardData(
      "LLM Request",
      duration = log.durationMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.MaestroDriverLog -> LogCardData(
      "Maestro Driver",
      duration = null,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.DelegatingTrailblazeToolLog -> LogCardData(
      title = "Delegating Tool",
      duration = null
    )

    is TrailblazeLog.ObjectiveStartLog -> LogCardData(
      title = "Objective Start",
      duration = null
    )

    is TrailblazeLog.ObjectiveCompleteLog -> LogCardData(
      title = "Objective Complete",
      duration = null
    )

    is TrailblazeLog.TopLevelMaestroCommandLog -> LogCardData(
      title = "Top Level Maestro Command",
      duration = null
    )

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> LogCardData(
      title = "Session Status",
      duration = null,
    )
  }
  val indent = logIndentLevel(log)

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .padding(start = (indent * 16).dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50))
          .background(
            Color(0xFF0F5132) // Dark green for success, dark red for failure
          )
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column {
        SelectableText(text = data.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
          data.duration?.let { SelectableText(text = "${it}ms", style = MaterialTheme.typography.bodySmall) }
          Spacer(modifier = Modifier.width(8.dp))
        }
      }
    }
    TextButton(onClick = { showDetailsDialog = true }) { Text("Details") }
  }

  if (showDetailsDialog) {
    Dialog(onDismissRequest = { showDetailsDialog = false }) {
      LogDetailsDialog(log = log, onDismiss = { showDetailsDialog = false })
    }
  }
}

fun logIndentLevel(log: TrailblazeLog): Int = when (log) {
  is TrailblazeLog.TopLevelMaestroCommandLog -> 0
  is TrailblazeLog.MaestroCommandLog -> 1
  is TrailblazeLog.MaestroDriverLog -> 2
  is TrailblazeLog.TrailblazeLlmRequestLog -> 1
  is TrailblazeLog.TrailblazeToolLog -> 1
  is TrailblazeLog.DelegatingTrailblazeToolLog -> 2
  else -> 0
}

@Composable
fun SessionListComposable(
  sessions: List<SessionInfo>,
  sessionClicked: (SessionInfo) -> Unit = {},
  deleteSession: ((SessionInfo) -> Unit)?,
) {
  Column {
    Row {
      SelectableText(
        "List of Trailblaze Sessions",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(8.dp)
      )
    }

    val groupedSessions: Map<LocalDate, List<SessionInfo>> = sessions.groupBy {
      it.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date
    }

    LazyColumn(
      modifier = Modifier.padding(start = 8.dp, end = 8.dp),
    ) {
      groupedSessions.forEach { (date, sessionsForDay) ->
        item {
          Text(
            text = date.toString(), // Consider a more friendly format
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp)
          )
        }

        items(sessionsForDay) { session: SessionInfo ->
          Card(
            modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(),
            onClick = {
              sessionClicked(session)
            },
          ) {
            Column {
              Row(
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                val time = session.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).time
                SelectableText(
                  text = "${time.hour}:${time.minute.toString().padStart(2, '0')} - ${session.displayName}",
                  modifier = Modifier.padding(8.dp)
                )
                StatusBadge(modifier = Modifier.weight(1f, false), status = session.latestStatus)
                deleteSession?.let {
                  Box(
                    modifier = Modifier.weight(1f, false)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Delete,
                      contentDescription = "Delete Session",
                      modifier = Modifier.align(Alignment.CenterEnd).clickable {
                        deleteSession(session)
                      }
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
}

interface LiveSessionDataProvider {
  fun getSessionIds(): List<String>
  fun getSessions(): List<SessionInfo>
  fun getSessionInfo(sessionId: String): SessionInfo
  fun getLogsForSession(sessionId: String): List<TrailblazeLog>
  fun addSessionListListener(listener: SessionListListener)
  fun removeSessionListListener(listener: SessionListListener)
  fun startWatchingTrailblazeSession(listener: TrailblazeSessionListener)
  fun stopWatching(sessionId: String)
}

interface SessionListListener {
  fun onSessionAdded(sessionId: String)
  fun onSessionRemoved(sessionId: String)
}

interface TrailblazeSessionListener {
  val trailblazeSessionId: String
  fun onSessionStarted()
  fun onUpdate(message: String)
  fun onSessionEnded()
}

@Composable
fun LiveSessionListComposable(
  sessionDataProvider: LiveSessionDataProvider,
  sessionClicked: (SessionInfo) -> Unit = {},
  deleteSession: (SessionInfo) -> Unit,
) {
  var sessions by remember {
    mutableStateOf(
      sessionDataProvider.getSessionIds().mapNotNull { sessionId ->
        val firstLog = sessionDataProvider.getLogsForSession(sessionId).firstOrNull()
        if (firstLog != null) {
          sessionDataProvider.getSessionInfo(sessionId)
        } else {
          null
        }
      }
    )
  }

  DisposableEffect(sessionDataProvider) {
    val listener = object : SessionListListener {
      override fun onSessionAdded(sessionId: String) {
        val updatedSessions = sessionDataProvider.getSessionIds().mapNotNull { id ->
          val firstLog = sessionDataProvider.getLogsForSession(id).firstOrNull()
          if (firstLog != null) {
            sessionDataProvider.getSessionInfo(id)
          } else {
            null
          }
        }
        sessions = updatedSessions
      }

      override fun onSessionRemoved(sessionId: String) {
        sessions = sessions.filterNot { it.sessionId == sessionId }
      }
    }

    sessionDataProvider.addSessionListListener(listener)

    onDispose {
      sessionDataProvider.removeSessionListListener(listener)
    }
  }

  SessionListComposable(
    sessions = sessions,
    sessionClicked = sessionClicked,
    deleteSession = deleteSession,
  )
}

@Composable
fun LiveSessionDetailComposable(
  sessionDataProvider: LiveSessionDataProvider,
  session: SessionInfo,
  toMaestroYaml: (JsonObject) -> String,
  generateRecordingYaml: () -> String,
  onBackClick: () -> Unit,
  onKotlinRecordingClick: () -> Unit = {},
  onMaestroRecordingClick: () -> Unit = {},
  onTrailblazeRecordingClick: () -> Unit = {},
  onSimpleRecordingClick: () -> Unit = {},
  onLLMMessagesClick: () -> Unit = {},
  imageLoader: ImageLoader = NetworkImageLoader(),
) {
  var logs by remember(session.sessionId) {
    mutableStateOf(sessionDataProvider.getLogsForSession(session.sessionId))
  }

  DisposableEffect(sessionDataProvider, session.sessionId) {
    val listener = object : TrailblazeSessionListener {
      override val trailblazeSessionId: String = session.sessionId

      override fun onSessionStarted() {
        logs = sessionDataProvider.getLogsForSession(session.sessionId)
      }

      override fun onUpdate(message: String) {
        logs = sessionDataProvider.getLogsForSession(session.sessionId)
      }

      override fun onSessionEnded() {
        logs = sessionDataProvider.getLogsForSession(session.sessionId)
      }
    }

    sessionDataProvider.startWatchingTrailblazeSession(listener)

    onDispose {
      sessionDataProvider.stopWatching(session.sessionId)
    }
  }

  val sessionDetail = remember(logs) {
    val overallStatus = logs
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .lastOrNull()?.sessionStatus

    val inProgress = overallStatus == null || overallStatus is SessionStatus.Started

    val firstLogWithDeviceInfo = logs.firstOrNull { log ->
      when (log) {
        is TrailblazeLog.TrailblazeLlmRequestLog -> true
        is TrailblazeLog.MaestroDriverLog -> true
        else -> false
      }
    }

    val (deviceName, deviceType) = when (firstLogWithDeviceInfo) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> "Device ${firstLogWithDeviceInfo.deviceWidth}x${firstLogWithDeviceInfo.deviceHeight}" to "Mobile"
      is TrailblazeLog.MaestroDriverLog -> "Device ${firstLogWithDeviceInfo.deviceWidth}x${firstLogWithDeviceInfo.deviceHeight}" to "Mobile"
      else -> null to null
    }

    SessionDetail(
      session = session,
      logs = logs,
      overallStatus = overallStatus,
      deviceName = deviceName,
      deviceType = deviceType
    )
  }

  SessionDetailComposable(
    details = sessionDetail,
    toMaestroYaml = toMaestroYaml,
    generateRecordingYaml = generateRecordingYaml,
    onBackClick = onBackClick,
    onKotlinRecordingClick = onKotlinRecordingClick,
    onMaestroRecordingClick = onMaestroRecordingClick,
    onTrailblazeRecordingClick = onTrailblazeRecordingClick,
    onSimpleRecordingClick = onSimpleRecordingClick,
    onLLMMessagesClick = onLLMMessagesClick,
    imageLoader = imageLoader
  )
}

fun LocalDate.toUSDateFormat(): String {
  val monthName = month.name.lowercase().replaceFirstChar { it.uppercaseChar() }
  return "$monthName $dayOfMonth, $year"
}