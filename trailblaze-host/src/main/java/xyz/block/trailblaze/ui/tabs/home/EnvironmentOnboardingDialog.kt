package xyz.block.trailblaze.ui.tabs.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.models.TrailblazeServerState.TestingEnvironment

/**
 * A first-run onboarding dialog that asks the user to choose their testing environment.
 * Shown when [TestingEnvironment] has not been set yet (null).
 */
@Composable
fun EnvironmentOnboardingDialog(
  onEnvironmentSelected: (TestingEnvironment) -> Unit,
) {
  var selectedEnvironment by remember { mutableStateOf<TestingEnvironment?>(null) }

  AlertDialog(
    onDismissRequest = { /* Non-dismissable — user must pick an environment */ },
    title = {
      Text("Choose Your Testing Environment")
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
          text = "Select the type of testing you want to do. This configures your device targets and environment. You can change this later from the Home screen.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        EnvironmentPicker(
          selectedEnvironment = selectedEnvironment,
          onEnvironmentSelected = { selectedEnvironment = it },
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { selectedEnvironment?.let { onEnvironmentSelected(it) } },
        enabled = selectedEnvironment != null,
      ) {
        Text("Get Started")
      }
    },
    modifier = Modifier.width(600.dp),
  )
}
