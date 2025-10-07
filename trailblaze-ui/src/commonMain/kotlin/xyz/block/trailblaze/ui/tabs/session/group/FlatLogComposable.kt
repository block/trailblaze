package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.tabs.session.DetailSection
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration


// Simplified flat detail composables that don't use nested Column layouts

@Composable
fun LlmRequestDetailsFlat(log: TrailblazeLog.TrailblazeLlmRequestLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("LLM Response & Actions") {
      log.llmResponse.forEach { response ->
        CodeBlock(response.toString())
        if (log.llmResponse.last() != response) {
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }

    DetailSection("Request Duration") {
      Text(formatDuration(log.durationMs))
    }

    DetailSection("Available Tools") {
      log.toolOptions.forEach { toolOption ->
        CodeBlock(toolOption.name)
      }
    }

    DetailSection("Chat History") {
      Text("Use the 'Chat History' button", style = MaterialTheme.typography.bodyMedium)

    }

    DetailSection("View Hierarchy") {
      Text("Use the 'Inspect UI' button", style = MaterialTheme.typography.bodyMedium)
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
