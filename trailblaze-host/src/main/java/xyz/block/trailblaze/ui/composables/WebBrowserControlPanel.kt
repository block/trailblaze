package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.icons.BrowserChrome

/**
 * A control panel for launching and managing the web browser for testing.
 *
 * This panel displays:
 * - A "Launch Browser" button when no browser is running
 * - Browser status and "Close Browser" button when a browser is running
 * - Error messages if browser launch fails
 */
@Composable
fun WebBrowserControlPanel(
  deviceManager: TrailblazeDeviceManager,
  modifier: Modifier = Modifier,
) {
  val browserState by deviceManager.webBrowserStateFlow.collectAsState()

  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Header
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(
          imageVector = BrowserChrome,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp)
        )
        Text(
          text = "Web Browser",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary
        )
      }

      // Content based on state
      when (val state = browserState) {
        is WebBrowserState.Idle -> {
          IdleBrowserContent(
            onLaunch = { deviceManager.launchWebBrowser() }
          )
        }

        is WebBrowserState.Launching -> {
          LaunchingBrowserContent()
        }

        is WebBrowserState.Running -> {
          RunningBrowserContent(
            onClose = { deviceManager.closeWebBrowser() }
          )
        }

        is WebBrowserState.Error -> {
          ErrorBrowserContent(
            errorMessage = state.message,
            onRetry = { deviceManager.launchWebBrowser() }
          )
        }
      }
    }
  }
}

@Composable
private fun IdleBrowserContent(
  onLaunch: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Text(
      text = "Launch a Chrome browser window for web testing. The browser will appear as a device that you can select for running tests.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
      onClick = onLaunch,
      modifier = Modifier.semantics { contentDescription = "Launch Web Browser Button" }
    ) {
      Icon(
        imageVector = Icons.Default.PlayArrow,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text("Launch Web Browser")
    }
  }
}

@Composable
private fun LaunchingBrowserContent() {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically
  ) {
    CircularProgressIndicator(
      modifier = Modifier.size(24.dp),
      strokeWidth = 2.dp
    )
    Spacer(modifier = Modifier.width(12.dp))
    Text(
      text = "Launching browser...",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun RunningBrowserContent(
  onClose: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Green status indicator
        Card(
          modifier = Modifier.size(12.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
          ),
          shape = MaterialTheme.shapes.extraSmall
        ) {}

        Text(
          text = "Chrome Browser Running",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface
        )
      }

      OutlinedButton(
        onClick = onClose,
        modifier = Modifier.semantics { contentDescription = "Close Web Browser Button" },
        colors = ButtonDefaults.outlinedButtonColors(
          contentColor = MaterialTheme.colorScheme.error
        )
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = null,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Close")
      }
    }

    Text(
      text = "The browser is ready for testing. Select it from the device list below to run web tests.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

@Composable
private fun ErrorBrowserContent(
  errorMessage: String,
  onRetry: () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(20.dp)
      )
      Text(
        text = "Failed to launch browser",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.error
      )
    }

    Text(
      text = errorMessage,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
      onClick = onRetry,
    ) {
      Text("Retry")
    }
  }
}
