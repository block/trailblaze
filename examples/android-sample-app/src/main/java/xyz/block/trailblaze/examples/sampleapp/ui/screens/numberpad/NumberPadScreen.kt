package xyz.block.trailblaze.examples.sampleapp.ui.screens.numberpad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// 3x4 grid, keys packed tightly (4.dp gaps, 88x56.dp keys) so a tap that lands even a few pixels
// off-center risks hitting a neighboring key instead of the intended one — the dense-keypad shape
// that originally exposed a mis-tap bug in a real numeric-entry screen (#4764). Deterministic
// replay only proves this screen's tap dispatch, not agent reasoning: every step is a recorded
// tap on a specific key, and the accumulating display is asserted against the exact expected
// digits.
private val KEY_ROWS =
  listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("Clear", "0", "Backspace"),
  )

@Composable
fun NumberPadScreen() {
  var entered by remember { mutableStateOf("") }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "Entered: $entered",
      style = MaterialTheme.typography.headlineSmall,
      modifier = Modifier.testTag("tv_number_pad_display"),
    )

    Spacer(modifier = Modifier.height(8.dp))

    KEY_ROWS.forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEach { key ->
          Button(
            onClick = {
              entered =
                when (key) {
                  "Clear" -> ""
                  "Backspace" -> entered.dropLast(1)
                  else -> entered + key
                }
            },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(width = 88.dp, height = 56.dp),
          ) {
            Text(key)
          }
        }
      }
    }
  }
}
