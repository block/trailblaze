package xyz.block.trailblaze.ui.tabs.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.model.LlmMessage


@Composable
fun LlmMessageComposable(message: LlmMessage) {
  val backgroundColor = when (message.role) {
    "user" -> MaterialTheme.colorScheme.primaryContainer
    "assistant" -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.tertiaryContainer
  }

  Column(modifier = Modifier.padding(bottom = 8.dp)) {
    Text(
      text = message.role.replaceFirstChar { it.uppercaseChar() },
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(bottom = 4.dp)
    )

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
      SelectionContainer {
        Text(
          text = message.message?.replace("data:image/png;base64,[A-Za-z0-9+/=]+".toRegex(), "[screenshot removed]")
            ?: "",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(12.dp)
        )
      }
    }
  }
}