package xyz.block.trailblaze.ui.tabs.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.block.trailblaze.ui.desktoputil.DesktopUtil
import xyz.block.trailblaze.ui.desktoputil.ExitApp
import xyz.block.trailblaze.ui.desktoputil.ShellProfileRestartRequiredDialog
import xyz.block.trailblaze.ui.desktoputil.ShellProfileWriteResult
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.desktoputil.appendLineToShellProfile
import xyz.block.trailblaze.ui.model.LocalNavController
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.model.navigateToRoute
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.icons.Android
import xyz.block.trailblaze.ui.icons.Apple
import xyz.block.trailblaze.ui.utils.toolavailability.AdbStatus
import xyz.block.trailblaze.ui.utils.toolavailability.IosStatus
import xyz.block.trailblaze.ui.utils.toolavailability.ToolAvailability
import xyz.block.trailblaze.ui.utils.toolavailability.ToolAvailabilityChecker

@Composable
fun HomeTabComposable(
  trailblazeSettingsRepo: TrailblazeSettingsRepo,
) {
  val navController = LocalNavController.current
  var toolAvailability by remember { mutableStateOf<ToolAvailability?>(null) }

  LaunchedEffect(Unit) {
    toolAvailability = ToolAvailabilityChecker.check()
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    // Welcome section
    Column {
      Text(
        text = "Welcome to Trailblaze",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "AI-powered UI testing for mobile apps. Get started by running a sample test on a connected device.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Quick Start section
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = "Quick Start",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = "Try a sample test to see Trailblaze in action.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        QuickStartCard(
          title = "Set an Alarm",
          description = "Open the Clock app and set a new alarm for 7:30 AM",
          icon = Icons.Filled.Alarm,
          modifier = Modifier.weight(1f),
          onClick = {
            trailblazeSettingsRepo.updateAppConfig { it.copy(yamlContent = SET_ALARM_YAML) }
            navController.navigateToRoute(TrailblazeRoute.YamlRoute)
          },
        )
        QuickStartCard(
          title = "Add a Contact",
          description = "Open the Contacts app and create a new contact",
          icon = Icons.Filled.ContactPhone,
          modifier = Modifier.weight(1f),
          onClick = {
            trailblazeSettingsRepo.updateAppConfig { it.copy(yamlContent = ADD_CONTACT_YAML) }
            navController.navigateToRoute(TrailblazeRoute.YamlRoute)
          },
        )
      }
    }

    // Environment Status section
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        text = "Environment Status",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
      )

      val availability = toolAvailability
      if (availability == null) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Text(
            text = "Checking environment...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          AdbStatusCard(
            adbStatus = availability.adbStatus,
            modifier = Modifier.weight(1f),
          )
          IosStatusCard(
            iosStatus = availability.iosStatus,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private object StatusColors {
  val SUCCESS = Color(0xFF4CAF50)
  val WARNING = Color(0xFFFF9800)
}

// ---------------------------------------------------------------------------
// Platform Status Cards
// ---------------------------------------------------------------------------

/** Header row used inside each platform card: platform icon + label + status badge. */
@Composable
private fun PlatformCardHeader(
  label: String,
  platformIcon: ImageVector,
  available: Boolean,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = platformIcon,
      contentDescription = label,
      modifier = Modifier.size(32.dp),
      tint = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    Icon(
      imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning,
      contentDescription = if (available) "Available" else "Needs setup",
      modifier = Modifier.size(20.dp),
      tint = if (available) StatusColors.SUCCESS else StatusColors.WARNING,
    )
  }
}

@Composable
private fun AdbStatusCard(adbStatus: AdbStatus, modifier: Modifier = Modifier) {
  val shellProfile = remember { DesktopUtil.getShellProfileFile() }
  var showRestartDialog by remember { mutableStateOf(false) }
  var writeError by remember { mutableStateOf<String?>(null) }

  if (showRestartDialog) {
    ShellProfileRestartRequiredDialog(
      title = "Restart Required",
      message = "ADB has been added to your PATH in ${shellProfile?.name ?: "your shell profile"}. " +
        "Restart Trailblaze to pick up the change.",
      onDismiss = { showRestartDialog = false },
      onQuit = { ExitApp.quit() },
    )
  }

  ElevatedCard(modifier = modifier) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PlatformCardHeader(
        label = "Android",
        platformIcon = Android,
        available = adbStatus is AdbStatus.Available,
      )

      when (adbStatus) {
        is AdbStatus.Available -> {
          Text(
            text = "ADB is available. Android devices are ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is AdbStatus.SdkFoundNotOnPath -> {
          Text(
            text = "SDK found but ADB is not on your PATH.",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.WARNING,
          )

          val exportLine =
            "export PATH=\"${adbStatus.sdkPath}/platform-tools:\$PATH\""
          val profileName = shellProfile?.name ?: "~/.zshrc"

          CopyableCommand(command = exportLine)

          Button(
            onClick = {
              val result = appendLineToShellProfile(
                line = exportLine,
                shellProfile = shellProfile,
                comment = "Android SDK platform-tools — added by Trailblaze",
                dedupMarker = "${adbStatus.sdkPath}/platform-tools",
              )
              when (result) {
                is ShellProfileWriteResult.Success,
                is ShellProfileWriteResult.AlreadyPresent -> {
                  showRestartDialog = true
                }
                is ShellProfileWriteResult.Error -> {
                  writeError = result.message
                }
              }
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Add to $profileName")
          }

          if (writeError != null) {
            Text(
              text = writeError!!,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        is AdbStatus.NotInstalled -> {
          Text(
            text = "Install Android Studio, which includes the SDK and ADB.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Button(
            onClick = {
              TrailblazeDesktopUtil.openInDefaultBrowser(
                "https://developer.android.com/studio"
              )
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Download Android Studio")
          }

          Text(
            text = "After installing, finish the setup wizard and restart Trailblaze.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Text(
            text = "Or install just ADB via terminal:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          CopyableCommand(command = "brew install --cask android-platform-tools")
        }
      }
    }
  }
}

@Composable
private fun IosStatusCard(iosStatus: IosStatus, modifier: Modifier = Modifier) {
  ElevatedCard(modifier = modifier) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PlatformCardHeader(
        label = "iOS",
        platformIcon = Apple,
        available = iosStatus is IosStatus.Available,
      )

      when (iosStatus) {
        is IosStatus.Available -> {
          Text(
            text = "Xcode tools are available. iOS devices are ready.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is IosStatus.XcodeInstalledNotConfigured -> {
          Text(
            text = "Xcode found but command-line tools need setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = StatusColors.WARNING,
          )

          Text(
            text = "Open Xcode to accept the license, then run in terminal:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          CopyableCommand(
            command = "sudo xcode-select -s ${iosStatus.xcodePath}/Contents/Developer"
          )
          CopyableCommand(command = "xcodebuild -runFirstLaunch")

          Text(
            text = "For simulators: Xcode > Settings > Components. Then restart Trailblaze.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        is IosStatus.NotInstalled -> {
          Text(
            text = "Install Xcode from the App Store to enable iOS testing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Button(
            onClick = {
              TrailblazeDesktopUtil.openInDefaultBrowser(
                "macappstore://apps.apple.com/app/xcode/id497799835"
              )
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Open Xcode in App Store")
          }

          Text(
            text = "After installing, open Xcode to accept the license, then run in terminal:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          CopyableCommand(
            command = "sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
          )
          CopyableCommand(command = "xcodebuild -runFirstLaunch")

          Text(
            text = "For simulators: Xcode > Settings > Components. Then restart Trailblaze.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Shared UI Components
// ---------------------------------------------------------------------------

/** A styled, copy-to-clipboard command block. */
@Composable
private fun CopyableCommand(command: String) {
  val clipboardManager = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  var copied by remember { mutableStateOf(false) }

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = command,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    IconButton(
      onClick = {
        clipboardManager.setText(AnnotatedString(command))
        copied = true
        scope.launch {
          delay(2000)
          copied = false
        }
      },
      modifier = Modifier.size(32.dp),
    ) {
      if (copied) {
        Icon(
          imageVector = Icons.Filled.CheckCircle,
          contentDescription = "Copied",
          modifier = Modifier.size(16.dp),
          tint = StatusColors.SUCCESS,
        )
      } else {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = "Copy to clipboard",
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
