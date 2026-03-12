package xyz.block.trailblaze.examples.sampleapp.ui.screens.taps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TapsScreen() {
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var tapCount by remember { mutableIntStateOf(0) }
  var showDialog by remember { mutableStateOf(false) }
  var lastAction by remember { mutableStateOf("") }
  var toggleASelected by remember { mutableStateOf(false) }
  var toggleBSelected by remember { mutableStateOf(false) }

  Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Tap Me button
      Button(
        onClick = {
          tapCount++
          lastAction = "Tapped!"
          scope.launch { snackbarHostState.showSnackbar("Tapped!") }
        }
      ) {
        Text("Tap Me")
      }

      // Accessibility tap button
      Button(
        onClick = {
          tapCount++
          lastAction = "Tapped!"
          scope.launch { snackbarHostState.showSnackbar("Tapped!") }
        },
        modifier = Modifier.semantics { contentDescription = "accessibility_tap_button" },
      ) {
        Text("A11y Tap")
      }

      // Long press button
      Surface(
        modifier =
          Modifier.combinedClickable(
            onClick = {},
            onLongClick = { showDialog = true },
          ),
        shape = ButtonDefaults.shape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ) {
        Text(
          "Long Press Me",
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
      }

      // Last action display
      if (lastAction.isNotEmpty()) {
        Text(text = "Last action: $lastAction", fontSize = 16.sp)
      }

      // Tap count display
      Text(
        text = "Tap Count: $tapCount",
        fontSize = 24.sp,
        modifier = Modifier.testTag("tv_tap_count"),
      )

      // Button that enables after 3 taps
      Button(onClick = {}, enabled = tapCount >= 3) {
        Text(if (tapCount >= 3) "Now Enabled!" else "Disabled Until 3 Taps")
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Toggle buttons for selected selector testing
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
      ) {
        FilterChip(
          selected = toggleASelected,
          onClick = { toggleASelected = !toggleASelected },
          label = { Text("Toggle A") },
        )
        FilterChip(
          selected = toggleBSelected,
          onClick = { toggleBSelected = !toggleBSelected },
          label = { Text("Toggle B") },
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Nested elements for containsChild selector testing
      Column(modifier = Modifier.testTag("parent_container").fillMaxWidth().padding(8.dp)) {
        Text("Parent Container", modifier = Modifier.testTag("child_label"))
        Button(onClick = { tapCount++ }, modifier = Modifier.testTag("child_button")) {
          Text("Nested Button")
        }
      }
    }
  }

  if (showDialog) {
    AlertDialog(
      onDismissRequest = { showDialog = false },
      title = { Text("Action") },
      text = { Text("Choose an action") },
      confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Copy") } },
      dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Delete") } },
    )
  }
}
