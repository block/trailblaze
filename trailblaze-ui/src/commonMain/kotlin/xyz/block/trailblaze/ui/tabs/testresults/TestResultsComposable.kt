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

/**
 * Format a double as a percentage string with one decimal place
 */
private fun formatPercentage(value: Double): String {
  val rounded = (value * 10).toInt() / 10.0
  return "$rounded%"
}

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

  val passPercentage = if (totalTests > 0) (succeededTests * 100.0 / totalTests) else 0.0
  val failPercentage = if (totalTests > 0) (failedTests * 100.0 / totalTests) else 0.0

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(24.dp)
  ) {
    // Header
    Text(
      text = "Test Results Summary",
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 16.dp)
    )

    // Summary Cards
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Total Tests Card
      SummaryCard(
        title = "Total Tests",
        value = totalTests.toString(),
        subtitle = " ", // Empty subtitle to match height of other cards
        color = Color(0xFF6200EE),
        modifier = Modifier.weight(1f)
      )

      // Passed Tests Card
      SummaryCard(
        title = "Passed",
        value = succeededTests.toString(),
        subtitle = formatPercentage(passPercentage),
        color = Color(0xFF4CAF50),
        modifier = Modifier.weight(1f)
      )

      // Failed Tests Card
      SummaryCard(
        title = "Failed",
        value = failedTests.toString(),
        subtitle = formatPercentage(failPercentage),
        color = Color(0xFFF44336),
        modifier = Modifier.weight(1f)
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Detailed Breakdown
    Card(
      modifier = Modifier.fillMaxWidth(),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
      Column(
        modifier = Modifier.padding(16.dp)
      ) {
        Text(
          text = "Detailed Breakdown",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(bottom = 12.dp)
        )

        Divider(modifier = Modifier.padding(bottom = 12.dp))

        StatusRow("Succeeded", succeededTests, totalTests, Color(0xFF4CAF50))
        StatusRow("Failed", failedTests, totalTests, Color(0xFFF44336))
        if (cancelledTests > 0) {
          StatusRow("Cancelled", cancelledTests, totalTests, Color(0xFFFF9800))
        }
        if (timeoutTests > 0) {
          StatusRow("Timeout", timeoutTests, totalTests, Color(0xFFFF5722))
        }
        if (inProgressTests > 0) {
          StatusRow("In Progress", inProgressTests, totalTests, Color(0xFF2196F3))
        }
        if (unknownTests > 0) {
          StatusRow("Unknown", unknownTests, totalTests, Color(0xFF9E9E9E))
        }
      }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Visual Progress Bar
    if (totalTests > 0) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(
          modifier = Modifier.padding(16.dp)
        ) {
          Text(
            text = "Pass/Fail Distribution",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 12.dp)
          )

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(40.dp),
            horizontalArrangement = Arrangement.Start
          ) {
            // Passed segment
            if (succeededTests > 0) {
              Box(
                modifier = Modifier
                  .fillMaxHeight()
                  .weight(succeededTests.toFloat())
                  .background(Color(0xFF4CAF50))
              )
            }
            // Failed segment
            if (failedTests > 0) {
              Box(
                modifier = Modifier
                  .fillMaxHeight()
                  .weight(failedTests.toFloat())
                  .background(Color(0xFFF44336))
              )
            }
            // Other statuses segment
            val otherTests = cancelledTests + timeoutTests + inProgressTests + unknownTests
            if (otherTests > 0) {
              Box(
                modifier = Modifier
                  .fillMaxHeight()
                  .weight(otherTests.toFloat())
                  .background(Color(0xFF9E9E9E))
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SummaryCard(
  title: String,
  value: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  color: Color = MaterialTheme.colorScheme.primary,
) {
  Card(
    modifier = modifier,
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    colors = CardDefaults.cardColors(containerColor = color)
  ) {
    Column(
      modifier = Modifier
        .padding(16.dp)
        .fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Medium
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.displaySmall,
        color = Color.White,
        fontWeight = FontWeight.Bold
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.titleMedium,
          color = Color.White.copy(alpha = 0.9f),
          fontWeight = FontWeight.Medium
        )
      }
    }
  }
}

@Composable
private fun StatusRow(
  label: String,
  count: Int,
  total: Int,
  color: Color,
) {
  val percentage = if (total > 0) (count * 100.0 / total) else 0.0

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.weight(1f)
    ) {
      Box(
        modifier = Modifier
          .size(12.dp)
          .background(color, shape = RoundedCornerShape(2.dp))
      )
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge
      )
    }

    Row(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = count.toString(),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold
      )
      Text(
        text = formatPercentage(percentage),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
