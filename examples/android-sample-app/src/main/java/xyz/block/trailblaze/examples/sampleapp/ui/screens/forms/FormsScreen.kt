package xyz.block.trailblaze.examples.sampleapp.ui.screens.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FormsScreen() {
  var name by remember { mutableStateOf("") }
  var email by remember { mutableStateOf("") }
  var submissionResult by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    OutlinedTextField(
      value = name,
      onValueChange = { name = it },
      label = { Text("Name") },
      modifier = Modifier.fillMaxWidth().testTag("field_name"),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
      singleLine = true,
    )

    OutlinedTextField(
      value = email,
      onValueChange = { email = it },
      label = { Text("Email") },
      modifier = Modifier.fillMaxWidth().testTag("field_email"),
      keyboardOptions =
        KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
      singleLine = true,
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(
        onClick = {
          submissionResult = "Name: $name\nEmail: $email"
          focusManager.clearFocus()
        },
        modifier = Modifier.weight(1f),
      ) {
        Text("Submit")
      }
      OutlinedButton(
        onClick = {
          name = ""
          email = ""
          submissionResult = ""
        },
        modifier = Modifier.weight(1f),
      ) {
        Text("Clear All")
      }
    }

    if (submissionResult.isNotEmpty()) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = "Submission Result:", fontSize = 18.sp)
      Text(text = submissionResult, modifier = Modifier.testTag("tv_submission_result"))
    }
  }
}
