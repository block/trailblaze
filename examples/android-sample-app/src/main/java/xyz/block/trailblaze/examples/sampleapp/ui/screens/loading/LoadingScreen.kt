package xyz.block.trailblaze.examples.sampleapp.ui.screens.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A screen whose content appears only after a configurable, deliberately variable delay.
 *
 * It exists to demonstrate the "wait until visible" testing pattern: the same trail (and the
 * `sampleapp_waitForText` scripted tool that backs it) must pass whether the content takes one
 * second or six, because it waits for the result to *appear* rather than sleeping a fixed amount
 * of time. Pick a delay, tap "Start Loading", and the screen swaps a spinner ("Loading") for the
 * final "Content Loaded" result after that delay.
 */
private enum class LoadState {
  IDLE,
  LOADING,
  LOADED,
}

private data class DelayOption(val label: String, val millis: Long)

private val DELAY_OPTIONS =
  listOf(
    DelayOption("Fast", 1_000),
    DelayOption("Medium", 3_000),
    DelayOption("Slow", 6_000),
  )

@Composable
fun LoadingScreen() {
  var state by remember { mutableStateOf(LoadState.IDLE) }
  // Default to the middle option so a trail that doesn't touch the chips still exercises a
  // non-trivial delay.
  var selected by remember { mutableStateOf(DELAY_OPTIONS[1]) }
  val scope = rememberCoroutineScope()

  Column(
    modifier =
      Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text("Loading Demo", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Pick how long the content should take, then start. The result appears only after that " +
        "delay — so a test has to wait for it rather than sleep a fixed amount of time.",
      style = MaterialTheme.typography.bodyMedium,
      textAlign = TextAlign.Center,
    )

    when (state) {
      LoadState.IDLE -> {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
          DELAY_OPTIONS.forEach { option ->
            FilterChip(
              selected = selected == option,
              onClick = { selected = option },
              label = { Text("${option.label} (${option.millis / 1000}s)") },
              modifier = Modifier.testTag("delay_${option.label.lowercase()}"),
            )
          }
        }
        Button(
          onClick = {
            state = LoadState.LOADING
            scope.launch {
              delay(selected.millis)
              state = LoadState.LOADED
            }
          },
          modifier = Modifier.testTag("btn_start_loading"),
        ) {
          Text("Start Loading")
        }
      }
      LoadState.LOADING -> {
        Spacer(modifier = Modifier.height(8.dp))
        CircularProgressIndicator(
          modifier = Modifier.semantics { contentDescription = "loading_spinner" }
        )
        Text("Loading", modifier = Modifier.testTag("tv_loading"))
      }
      LoadState.LOADED -> {
        Text(
          "Content Loaded",
          style = MaterialTheme.typography.headlineSmall,
          modifier = Modifier.testTag("tv_content_loaded"),
        )
        Text(
          "Your content is ready after a ${selected.label.lowercase()} delay.",
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
        )
        Button(
          onClick = { state = LoadState.IDLE },
          modifier = Modifier.testTag("btn_reset"),
        ) {
          Text("Reset")
        }
      }
    }
  }
}
