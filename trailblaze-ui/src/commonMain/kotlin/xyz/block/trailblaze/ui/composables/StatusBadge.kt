package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.model.SessionStatus

@Composable
fun StatusBadge(
  status: SessionStatus,
  modifier: Modifier = Modifier,
) {
  fun SessionStatus.isCancelled(): Boolean = when (this) {
    is SessionStatus.Ended.Cancelled -> true
    else -> false
  }

  fun SessionStatus.isTimeout(): Boolean = when (this) {
    is SessionStatus.Ended.TimeoutReached -> true
    else -> false
  }

  fun SessionStatus.isSuccess(): Boolean = when (this) {
    is SessionStatus.Started -> false
    is SessionStatus.Ended.Succeeded -> true
    is SessionStatus.Ended.SucceededWithFallback -> true
    is SessionStatus.Ended.Failed -> false
    is SessionStatus.Ended.FailedWithFallback -> false
    is SessionStatus.Ended.Cancelled -> false
    is SessionStatus.Ended.TimeoutReached -> false
    SessionStatus.Unknown -> false
  }

  val (label, bg, fg) = when {
    (status is SessionStatus.Started) -> Triple(
      "In Progress",
      Color(0xFFFFF3CD), // Light amber background - standard for in-progress
      Color(0xFF856404)  // Dark amber text
    )

    status.isTimeout() -> Triple(
      "Timeout",
      Color(0xFFFFE5CC), // Light orange background
      Color(0xFFCC5500)  // Dark orange text
    )

    status.isCancelled() -> Triple(
      "Cancelled",
      Color(0xFFFFE5CC), // Light orange background - more orange than amber
      Color(0xFFCC5500)  // Dark orange text - more orange than amber
    )

    status is SessionStatus.Ended.SucceededWithFallback -> Triple(
      "Succeeded (Fallback)",
      Color(0xFFE8F4F8), // Light blue-green background - different from regular success
      Color(0xFF0C5460)  // Dark blue-green text - indicates fallback was used
    )

    status is SessionStatus.Ended.FailedWithFallback -> Triple(
      "Failed (Fallback)",
      Color(0xFFF4E8F8), // Light purple background - different from regular failure  
      Color(0xFF5C0C60)  // Dark purple text - indicates fallback was attempted
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