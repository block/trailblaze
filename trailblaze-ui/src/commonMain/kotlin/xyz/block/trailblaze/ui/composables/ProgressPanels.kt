package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.block.trailblaze.model.DeviceConnectionStatus

/** Panel displaying progress messages during test execution. */
@Composable
fun ProgressMessagesPanel(progressMessages: List<String>) {
  OutlinedCard(modifier = Modifier.fillMaxWidth().height(200.dp)) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      Text(
        text = "Progress Messages",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
      )

      Spacer(modifier = Modifier.height(8.dp))

      val scrollState = rememberScrollState()

      // Auto-scroll to bottom when messages change
      LaunchedEffect(progressMessages.size) { scrollState.animateScrollTo(scrollState.maxValue) }

      Box(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Column {
          progressMessages.forEach { message ->
            SelectableText(
              text = "• $message",
              style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
              modifier = Modifier.padding(vertical = 2.dp),
            )
          }
        }
      }
    }
  }
}

/** Panel displaying the current device connection status. */
@Composable
fun ConnectionStatusPanel(status: DeviceConnectionStatus) {
  val (statusText, statusColor) =
    when (status) {
      is DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning ->
        "✓ Trailblaze running on device: ${status.trailblazeDeviceId}" to Color(0xFF4CAF50)
      is DeviceConnectionStatus.WithTargetDevice.StartingConnection ->
        "\uD83D\uDD04 Starting connection to device: ${status.trailblazeDeviceId}" to
          Color(0xFF2196F3)
      is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure ->
        "✗ Connection failed: ${status.errorMessage}" to Color(0xFFF44336)
      is DeviceConnectionStatus.DeviceConnectionError.NoConnection ->
        "⚪ No active connections" to Color(0xFF9E9E9E)
      is DeviceConnectionStatus.DeviceConnectionError.ThereIsAlreadyAnActiveConnection ->
        "⚠\uFE0F Already connected to device: ${status.deviceId}" to Color(0xFFFF9800)
    }

  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text(
        text = "Connection Status",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
      )

      Spacer(modifier = Modifier.height(8.dp))

      SelectableText(text = statusText, color = statusColor)
    }
  }
}
