package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.models.LogCardData
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

@Composable
fun LogCard(
  log: TrailblazeLog,
  sessionId: String,
  toMaestroYaml: (JsonObject) -> String,
  imageLoader: ImageLoader = NetworkImageLoader(),
  showDetails: (() -> Unit)? = null,
  showInspectUI: (() -> Unit)? = null,
) {
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
      title = "Delegating Tool: ${log.toolName}",
      duration = null,
      preformattedText = buildString {
        appendLine(log.command.toString())
        appendLine(log.executableTools.joinToString("\n") { it.toString() })
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
        fontWeight = FontWeight.Bold,
      )

      Spacer(modifier = Modifier.height(4.dp))

      logData.duration?.let {
        SelectableText(
          text = "Duration: ${formatDuration(it)}",
          style = MaterialTheme.typography.bodySmall,
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
        onClick = { showDetails?.invoke() },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.secondary
        )
      ) {
        Text("View Details")
      }

      // Add Inspect UI button for LLM Request logs
      if (log is TrailblazeLog.TrailblazeLlmRequestLog && showInspectUI != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Button(
          onClick = {
            showInspectUI.invoke()
          },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary
          )
        ) {
          Text("Inspect UI")
        }
      }
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