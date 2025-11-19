package xyz.block.trailblaze.ui.tabs.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage


@Composable
fun LlmMessageComposable(message: TrailblazeLlmMessage) {
  val isUser = message.role == "user"
  val isAssistant = message.role == "assistant"
  val isTool = message.role == "tool" || message.role == "function"
  val isSystem = message.role == "system"

  // Determine icon and colors based on role
  val (icon, iconColor, iconBgColor) = when {
    isUser -> Triple(
      Icons.Default.AccountCircle,
      MaterialTheme.colorScheme.onPrimaryContainer,
      MaterialTheme.colorScheme.primaryContainer
    )

    isAssistant -> Triple(
      Icons.Default.Settings,
      MaterialTheme.colorScheme.onSecondaryContainer,
      MaterialTheme.colorScheme.secondaryContainer
    )

    isTool -> Triple(
      Icons.Default.Build,
      MaterialTheme.colorScheme.onTertiaryContainer,
      MaterialTheme.colorScheme.tertiaryContainer
    )

    else -> Triple(
      Icons.Default.Settings,
      MaterialTheme.colorScheme.onSurfaceVariant,
      MaterialTheme.colorScheme.surfaceVariant
    )
  }

  // Clean up message content
  val cleanedMessage = message.message
      ?.replace("data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+".toRegex(), "[🖼️ Screenshot attached]")
    ?: ""

  // ChatGPT-style layout: User messages on the right, others on the left
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp, horizontal = 16.dp),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
  ) {
    // Avatar/Icon (only show for non-user messages on the left)
    if (!isUser) {
      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(CircleShape)
          .background(iconBgColor),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = message.role,
          tint = iconColor,
          modifier = Modifier.size(18.dp)
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
    }

    // Message content
    Box(
      modifier = Modifier
        .fillMaxWidth(0.75f)
    ) {
      Column(
        modifier = Modifier
          .wrapContentWidth()
      ) {
        // Role label (smaller and less prominent)
        Text(
          text = when {
            isUser -> "You"
            isAssistant -> "Assistant"
            isTool -> "Tool"
            isSystem -> "System"
            else -> message.role.replaceFirstChar { it.uppercaseChar() }
          },
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          modifier = Modifier.padding(bottom = 4.dp, start = if (isUser) 0.dp else 2.dp)
        )

        // Message bubble
        Surface(
          shape = RoundedCornerShape(
            topStart = if (isUser) 12.dp else 4.dp,
            topEnd = if (isUser) 4.dp else 12.dp,
            bottomStart = 12.dp,
            bottomEnd = 12.dp
          ),
          color = when {
            isUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            isAssistant -> MaterialTheme.colorScheme.surface
            isTool -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
          },
          tonalElevation = if (isAssistant) 1.dp else 0.dp,
          shadowElevation = if (isAssistant || isUser) 1.dp else 0.dp
        ) {
          SelectionContainer {
            Markdown(
              modifier = Modifier
                .padding(16.dp),
              content = cleanedMessage
            )
          }
        }
      }
    }

    // Avatar/Icon for user messages on the right
    if (isUser) {
      Spacer(modifier = Modifier.width(8.dp))
      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(CircleShape)
          .background(iconBgColor),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = message.role,
          tint = iconColor,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}
