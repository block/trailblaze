package xyz.block.trailblaze.ui.tabs.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.CodeBlock

/**
 * Reusable row component for target app selection.
 * Used by DevicesTab for target app configuration.
 *
 * Shows registered app targets followed by user-added custom package names.
 * Includes an "Add Package" option at the bottom of the dropdown to let users
 * register new package names which are persisted in app settings.
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
  var showAddPackageDialog by remember { mutableStateOf(false) }

  val currentState by settingsRepo.serverStateFlow.collectAsState()
  val customPackageNames = currentState.appConfig.customAppPackageNames

  // Determine display value: could be a registered target app or a custom package name
  val selectedCustomPackage = if (selectedTargetApp == null) {
    currentState.appConfig.selectedTargetAppId?.takeIf { it in customPackageNames }
  } else null

  val displayValue = selectedTargetApp?.displayName
    ?: selectedCustomPackage
    ?: "Select target app"

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
        value = displayValue,
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
        // Registered app targets
        deviceManager.availableAppTargets.forEach { appTarget ->
          DropdownMenuItem(
            leadingIcon = {
              deviceManager.appIconProvider.getIcon(appTarget)
            },
            text = { Text(appTarget.displayName) },
            onClick = {
              settingsRepo.targetAppSelected(appTarget)
              expanded = false
            }
          )
        }

        // User-added custom package names
        if (customPackageNames.isNotEmpty()) {
          HorizontalDivider()

          customPackageNames.forEach { packageName ->
            DropdownMenuItem(
              text = {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(packageName, modifier = Modifier.weight(1f))
                  IconButton(
                    onClick = {
                      settingsRepo.removeCustomAppPackageName(packageName)
                    },
                    modifier = Modifier.size(24.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Delete,
                      contentDescription = "Remove $packageName",
                      tint = MaterialTheme.colorScheme.error,
                      modifier = Modifier.size(16.dp),
                    )
                  }
                }
              },
              onClick = {
                settingsRepo.updateAppConfig { config ->
                  config.copy(selectedTargetAppId = packageName)
                }
                expanded = false
              }
            )
          }
        }

        // "Add Package" option at the bottom
        HorizontalDivider()
        DropdownMenuItem(
          leadingIcon = {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
          },
          text = {
            Text(
              "Add Package",
              color = MaterialTheme.colorScheme.primary,
            )
          },
          onClick = {
            expanded = false
            showAddPackageDialog = true
          }
        )
      }
    }

    // Show app info if requested and an app is selected
    if (selectedTargetApp != null && showDebugInfo) {
      Spacer(modifier = Modifier.height(16.dp))
      CodeBlock(
        text = selectedTargetApp.getAppInfoText(
          supportedDrivers = deviceManager.getAllSupportedDriverTypes()
        )
      )
    }
  }

  // Add Package Dialog
  if (showAddPackageDialog) {
    AddPackageDialog(
      onDismiss = { showAddPackageDialog = false },
      onAdd = { packageName ->
        settingsRepo.addCustomAppPackageName(packageName)
        // Auto-select the newly added package
        settingsRepo.updateAppConfig { config ->
          config.copy(selectedTargetAppId = packageName.trim())
        }
        showAddPackageDialog = false
      },
      existingPackages = customPackageNames,
    )
  }
}

/**
 * Dialog for adding a new custom application package name.
 */
@Composable
private fun AddPackageDialog(
  onDismiss: () -> Unit,
  onAdd: (String) -> Unit,
  existingPackages: List<String>,
) {
  var packageName by remember { mutableStateOf("") }
  val trimmed = packageName.trim()
  val isDuplicate = trimmed in existingPackages
  val isValid = trimmed.isNotEmpty() && !isDuplicate

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Application Package") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "Enter the package name of the application you want to test.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = packageName,
          onValueChange = { packageName = it },
          label = { Text("Package Name") },
          placeholder = { Text("com.example.myapp") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          isError = isDuplicate,
          supportingText = if (isDuplicate) {
            { Text("This package has already been added") }
          } else null,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onAdd(trimmed) },
        enabled = isValid,
      ) {
        Text("Add")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}
