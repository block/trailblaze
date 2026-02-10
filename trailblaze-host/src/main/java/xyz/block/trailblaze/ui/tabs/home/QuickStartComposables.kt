package xyz.block.trailblaze.ui.tabs.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Sample YAML: sets an alarm in the Clock app. */
val SET_ALARM_YAML = """
- prompts:
    - step: Open the Clock app
    - step: Navigate to the Alarms tab
    - step: Create a new alarm set for 7:30 AM
    - step: Save the alarm
    - step: Verify the alarm for 7:30 AM is visible in the list
""".trimIndent()

/** Sample YAML: adds a contact in the Contacts app. */
val ADD_CONTACT_YAML = """
- prompts:
    - step: Open the Contacts app
    - step: Tap the button to add a new contact
    - step: Enter "Jane" as the first name
    - step: Enter "Doe" as the last name
    - step: Enter "555-0123" as the phone number
    - step: Save the contact
    - step: Verify the contact "Jane Doe" appears in the contacts list
""".trimIndent()

/** A clickable card used to launch a sample trail. */
@Composable
fun QuickStartCard(
  title: String,
  description: String,
  icon: ImageVector,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  ElevatedCard(
    onClick = onClick,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
