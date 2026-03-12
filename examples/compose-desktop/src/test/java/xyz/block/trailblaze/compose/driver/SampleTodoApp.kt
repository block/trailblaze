package xyz.block.trailblaze.compose.driver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * A Todo List composable used as a proving ground for compose-driver tools.
 *
 * When [includeAmbiguousSection] is true, extra elements are added at the bottom to exercise
 * element ID disambiguation: duplicate "Save" buttons, untagged "Learn more" clickable texts,
 * and duplicate "Enter value" text fields.
 */
object SampleTodoApp {
  const val TAG_TODO_INPUT = "todo_input"
  const val TAG_ADD_BUTTON = "add_button"
  const val TAG_ITEM_COUNT = "item_count"
  const val TAG_TODO_LIST = "todo_list"
}

@Composable
fun SampleTodoApp(includeAmbiguousSection: Boolean = false) {
  var inputText by remember { mutableStateOf("") }
  val todos = remember { mutableStateListOf<String>() }
  val completedStates = remember { mutableStateListOf<Boolean>() }

  // Ambiguous section state
  var profileSaved by remember { mutableStateOf(false) }
  var settingsSaved by remember { mutableStateOf(false) }
  var learnMore1Clicked by remember { mutableStateOf(false) }
  var learnMore2Clicked by remember { mutableStateOf(false) }
  var profileFieldText by remember { mutableStateOf("") }
  var settingsFieldText by remember { mutableStateOf("") }

  MaterialTheme {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextField(
          value = inputText,
          onValueChange = { inputText = it },
          placeholder = { Text("Enter a todo") },
          modifier = Modifier.weight(1f).testTag("todo_input"),
        )
        Button(
          onClick = {
            if (inputText.isNotBlank()) {
              todos.add(inputText.trim())
              completedStates.add(false)
              inputText = ""
            }
          },
          modifier = Modifier.testTag("add_button"),
        ) {
          Text("Add")
        }
      }

      Text(
        text = "${todos.size} items",
        modifier = Modifier.padding(vertical = 8.dp).testTag("item_count"),
      )

      LazyColumn(modifier = Modifier.fillMaxWidth().testTag("todo_list")) {
        itemsIndexed(todos) { index, todo ->
          Row(
            modifier =
              Modifier.fillMaxWidth().testTag("todo_item_$index").padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = completedStates[index],
              onCheckedChange = { completedStates[index] = it },
              modifier = Modifier.testTag("todo_checkbox_$index"),
            )
            Text(text = todo, modifier = Modifier.weight(1f))
            TextButton(
              onClick = {
                todos.removeAt(index)
                completedStates.removeAt(index)
              },
              modifier = Modifier.testTag("delete_button_$index"),
            ) {
              Text("X")
            }
          }
        }
      }

      // -- Ambiguous section: duplicate buttons, untagged clickable text, duplicate fields --
      if (includeAmbiguousSection) {
        // Profile section — "Save" button with testTag
        Text("Profile Section")
        TextField(
          value = profileFieldText,
          onValueChange = { profileFieldText = it },
          placeholder = { Text("Enter value") },
          modifier = Modifier.fillMaxWidth().testTag("profile_field"),
        )
        Button(
          onClick = { profileSaved = true },
          modifier = Modifier.testTag("profile_save_button"),
        ) {
          Text("Save")
        }
        if (profileSaved) {
          Text("Profile saved!", modifier = Modifier.testTag("profile_saved_indicator"))
        }
        // Untagged "Learn more" clickable text #1
        Text(
          text = "Learn more",
          modifier = Modifier.clickable { learnMore1Clicked = true },
        )
        if (learnMore1Clicked) {
          Text("Learn more 1 clicked", modifier = Modifier.testTag("learn_more_1_indicator"))
        }

        // Settings section — another "Save" button with testTag
        Text("Settings Section")
        TextField(
          value = settingsFieldText,
          onValueChange = { settingsFieldText = it },
          placeholder = { Text("Enter value") },
          modifier = Modifier.fillMaxWidth().testTag("settings_field"),
        )
        Button(
          onClick = { settingsSaved = true },
          modifier = Modifier.testTag("settings_save_button"),
        ) {
          Text("Save")
        }
        if (settingsSaved) {
          Text("Settings saved!", modifier = Modifier.testTag("settings_saved_indicator"))
        }
        // Untagged "Learn more" clickable text #2
        Text(
          text = "Learn more",
          modifier = Modifier.clickable { learnMore2Clicked = true },
        )
        if (learnMore2Clicked) {
          Text("Learn more 2 clicked", modifier = Modifier.testTag("learn_more_2_indicator"))
        }
      }
    }
  }
}
