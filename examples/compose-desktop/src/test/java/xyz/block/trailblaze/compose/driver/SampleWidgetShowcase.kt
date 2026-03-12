package xyz.block.trailblaze.compose.driver

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Switch
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * A showcase composable exercising every widget type and semantic property the compose-driver maps.
 *
 * Used by [ComposeEvalSuiteTest] to validate semantic tree mapping, tool execution, and
 * screen state capture across all supported widget roles and field combinations.
 */
@Composable
fun SampleWidgetShowcase() {
  var isDarkMode by remember { mutableStateOf(false) }
  var selectedRadio by remember { mutableStateOf("A") }
  var selectedTab by remember { mutableStateOf(0) }
  var clickCount by remember { mutableStateOf(0) }
  var passwordText by remember { mutableStateOf("") }
  var focusedText by remember { mutableStateOf("") }
  var appendText by remember { mutableStateOf("Hello") }
  val focusRequester = remember { FocusRequester() }

  MaterialTheme {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
      // Section header
      Text("Widget Showcase", modifier = Modifier.testTag("section_header"))

      // Switch
      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
          checked = isDarkMode,
          onCheckedChange = { isDarkMode = it },
          modifier = Modifier.testTag("dark_mode_switch"),
        )
        Text("Dark Mode")
      }

      // Radio buttons
      Column(modifier = Modifier.selectableGroup()) {
        val radioOptions = listOf("A", "B", "C")
        radioOptions.forEach { option ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
              selected = selectedRadio == option,
              onClick = { selectedRadio = option },
              modifier = Modifier.testTag("radio_option_${option.lowercase()}"),
            )
            Text("Option $option")
          }
        }
      }

      // Tabs
      TabRow(
        selectedTabIndex = selectedTab,
        modifier = Modifier.testTag("tab_row"),
      ) {
        Tab(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          modifier = Modifier.testTag("tab_info"),
        ) {
          Text("Info", modifier = Modifier.padding(8.dp))
        }
        Tab(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          modifier = Modifier.testTag("tab_settings"),
        ) {
          Text("Settings", modifier = Modifier.padding(8.dp))
        }
      }

      // Image with contentDescription
      Image(
        painter = ColorPainter(Color.Yellow),
        contentDescription = "Gold star rating",
        modifier = Modifier.size(48.dp).testTag("star_image"),
      )

      // Disabled button
      Button(
        onClick = {},
        enabled = false,
        modifier = Modifier.testTag("disabled_button"),
      ) {
        Text("Cannot Click")
      }

      // Counter button
      Button(
        onClick = { clickCount++ },
        modifier = Modifier.testTag("counter_button"),
      ) {
        Text("Clicked: $clickCount")
      }

      // Password field
      TextField(
        value = passwordText,
        onValueChange = { passwordText = it },
        placeholder = { Text("Enter password") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.testTag("password_field"),
      )

      // Auto-focused field
      TextField(
        value = focusedText,
        onValueChange = { focusedText = it },
        placeholder = { Text("Auto-focused") },
        modifier = Modifier.testTag("focused_field").focusRequester(focusRequester),
      )

      // Pre-filled append field
      TextField(
        value = appendText,
        onValueChange = { appendText = it },
        modifier = Modifier.testTag("append_field"),
      )

      // Box with contentDescription, no text
      Box(
        modifier =
          Modifier.size(48.dp)
            .testTag("described_box")
            .semantics { contentDescription = "Decorative separator" }
      )

      // Scrollable list (LazyColumn inside a fixed-height container)
      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("scrollable_list")
      ) {
        items((0 until 30).toList()) { index ->
          Text("Item $index", modifier = Modifier.testTag("list_item_$index").padding(8.dp))
        }
      }
    }
  }

  LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
