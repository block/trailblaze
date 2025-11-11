package xyz.block.trailblaze.ui.tabs.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.CodeBlock

/**
 * Reusable row component for target app selection.
 * Used by DevicesTab for target app configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetAppConfigRow(
  deviceManager: TrailblazeDeviceManager,
  settingsRepo: TrailblazeSettingsRepo,
  selectedTargetApp: TrailblazeHostAppTarget?,
  showDebugInfo: Boolean = false,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Column(modifier = modifier) {
    // Target App Dropdown
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = {
        expanded = !expanded
      },
      modifier = Modifier.fillMaxWidth()
    ) {
      TextField(
        readOnly = true,
        value = selectedTargetApp?.name ?: "Select target app",
        onValueChange = { },
        label = { Text("Target App") },
        leadingIcon = {
          deviceManager.appIconProvider.getIcon(
            appTarget = selectedTargetApp
          )
        },
        trailingIcon = {
          ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        },
        modifier = Modifier.fillMaxWidth().menuAnchor()
      )
      ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = {
          expanded = false
        }
      ) {
        deviceManager.appTargets.forEach { selectedTargetApp ->
          DropdownMenuItem(
            leadingIcon = {
              deviceManager.appIconProvider.getIcon(selectedTargetApp)
            },
            text = { Text(selectedTargetApp.name) },
            onClick = {
              settingsRepo.targetAppSelected(selectedTargetApp)
              expanded = false
            }
          )
        }
      }
    }

    // Show app info if requested and an app is selected
    if (selectedTargetApp != null && showDebugInfo) {
      Spacer(modifier = Modifier.height(16.dp))
      CodeBlock(
        text = selectedTargetApp.getAppInfoText(
          supportedDrivers = deviceManager.supportedDrivers
        )
      )
    }
  }
}
