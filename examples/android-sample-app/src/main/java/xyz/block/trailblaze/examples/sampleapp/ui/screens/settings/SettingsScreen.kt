package xyz.block.trailblaze.examples.sampleapp.ui.screens.settings

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService

@Composable
fun SettingsScreen() {
  val context = LocalContext.current
  var notificationsEnabled by remember { mutableStateOf(false) }
  var darkModeEnabled by remember { mutableStateOf(false) }
  var acceptTerms by remember { mutableStateOf(false) }

  // Network status
  val connectivityManager = context.getSystemService<ConnectivityManager>()
  val network = connectivityManager?.activeNetwork
  val capabilities = connectivityManager?.getNetworkCapabilities(network)
  val isOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
  val networkStatus = if (isOnline) "Online" else "Offline"

  // App version
  val appVersion =
    "Version ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}"

  Column(
    modifier =
      Modifier.fillMaxSize()
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .toggleable(
            value = notificationsEnabled,
            role = Role.Switch,
            onValueChange = { notificationsEnabled = it },
          ),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Enable Notifications")
      Switch(checked = notificationsEnabled, onCheckedChange = null)
    }

    Row(
      modifier =
        Modifier.fillMaxWidth()
          .toggleable(
            value = darkModeEnabled,
            role = Role.Switch,
            onValueChange = { darkModeEnabled = it },
          ),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Dark Mode")
      Switch(checked = darkModeEnabled, onCheckedChange = null)
    }

    Button(
      onClick = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/block/trailblaze"))
        context.startActivity(intent)
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Open Documentation")
    }

    Text("Network Status", fontSize = 14.sp, fontWeight = FontWeight.Bold)
    Text(networkStatus, fontSize = 16.sp)

    Row(
      modifier =
        Modifier.toggleable(
          value = acceptTerms,
          role = Role.Checkbox,
          onValueChange = { acceptTerms = it },
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(checked = acceptTerms, onCheckedChange = null)
      Text("Accept Terms")
    }

    Text(appVersion, fontSize = 14.sp)
  }
}
