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
  fun SessionStatus.isSuccess(): Boolean = when (this) {
    is SessionStatus.Started -> false
    is SessionStatus.Ended.Succeeded -> true
    is SessionStatus.Ended.Failed -> false
    SessionStatus.Unknown -> false
  }

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