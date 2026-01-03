@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.testresults

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.ui.composables.InteractivePieChart
import xyz.block.trailblaze.ui.composables.PieChartCenterContent
import xyz.block.trailblaze.ui.composables.PieChartSegment

/**
 * Common test results UI that accepts session data directly.
 * Can be used in any platform (JVM, WASM, etc.)
 */
@Composable
fun TestResultsComposable(
  sessionLogsMap: Map<String, List<TrailblazeLog>>,
) {
  // Compute session infos from the provided logs map
  val sessionInfos = remember(sessionLogsMap) {
    sessionLogsMap.entries
      .mapNotNull { (_, logs) ->
        if (logs.isNotEmpty()) {
          logs.getSessionInfo()
        } else {
          null
        }
      }
  }

  // Calculate aggregate statistics
  val totalTests = sessionInfos.size
  val succeededTests = sessionInfos.count {
    it.latestStatus is SessionStatus.Ended.Succeeded ||
        it.latestStatus is SessionStatus.Ended.SucceededWithFallback
  }
  val failedTests = sessionInfos.count {
    it.latestStatus is SessionStatus.Ended.Failed ||
        it.latestStatus is SessionStatus.Ended.FailedWithFallback ||
        it.latestStatus is SessionStatus.Ended.MaxCallsLimitReached
  }
  val cancelledTests = sessionInfos.count { it.latestStatus is SessionStatus.Ended.Cancelled }
  val timeoutTests = sessionInfos.count { it.latestStatus is SessionStatus.Ended.TimeoutReached }
  val inProgressTests = sessionInfos.count { it.latestStatus is SessionStatus.Started }
  val unknownTests = sessionInfos.count { it.latestStatus is SessionStatus.Unknown }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(24.dp)
  ) {
    // Header
    Text(
      text = "Test Results",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 24.dp)
    )

    // Test Status Distribution Pie Chart - Now at the top and prominent
    if (totalTests > 0) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
      ) {
        Column(
          modifier = Modifier.padding(24.dp)
        ) {
          val segments = buildList {
            if (succeededTests > 0) {
              add(
                PieChartSegment(
                  label = "Passed",
                  value = succeededTests.toLong(),
                  color = Color(0xFF4CAF50),
                  description = "$succeededTests tests"
                )
              )
            }
            if (failedTests > 0) {
              add(
                PieChartSegment(
                  label = "Failed",
                  value = failedTests.toLong(),
                  color = Color(0xFFF44336),
                  description = "$failedTests tests"
                )
              )
            }
            if (cancelledTests > 0) {
              add(
                PieChartSegment(
                  label = "Cancelled",
                  value = cancelledTests.toLong(),
                  color = Color(0xFFFF9800),
                  description = "$cancelledTests tests"
                )
              )
            }
            if (timeoutTests > 0) {
              add(
                PieChartSegment(
                  label = "Timeout",
                  value = timeoutTests.toLong(),
                  color = Color(0xFFFF5722),
                  description = "$timeoutTests tests"
                )
              )
            }
            if (inProgressTests > 0) {
              add(
                PieChartSegment(
                  label = "In Progress",
                  value = inProgressTests.toLong(),
                  color = Color(0xFF2196F3),
                  description = "$inProgressTests tests"
                )
              )
            }
            if (unknownTests > 0) {
              add(
                PieChartSegment(
                  label = "Unknown",
                  value = unknownTests.toLong(),
                  color = Color(0xFF9E9E9E),
                  description = "$unknownTests tests"
                )
              )
            }
          }

          InteractivePieChart(
            segments = segments,
            chartSize = 280.dp,
            strokeWidth = 50f,
            centerContent = { content ->
              when (content) {
                is PieChartCenterContent.Default -> {
                  Text(
                    text = content.totalValue,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "Total Tests",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                is PieChartCenterContent.Hovered -> {
                  Text(
                    text = content.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = content.color
                  )
                  Spacer(modifier = Modifier.height(8.dp))
                  Text(
                    text = content.value,
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "${content.percentage} of tests",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                  )
                }
              }
            }
          )
        }
      }
    }
  }
}
