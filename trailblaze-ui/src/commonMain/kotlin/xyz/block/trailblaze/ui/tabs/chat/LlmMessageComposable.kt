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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage

private val prettyJson = Json { prettyPrint = true }

/** Try to parse a string as a JSON element. Returns null if it's not valid JSON. */
private fun tryParseJson(text: String): JsonElement? {
  return try {
    val trimmed = text.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      Json.parseToJsonElement(trimmed)
    } else {
      null
    }
  } catch (_: Exception) {
    null
  }
}

@Composable
fun LlmMessageComposable(message: TrailblazeLlmMessage) {
  val isUser = message.role == "user"
  val isAssistant = message.role == "assistant"
  val isToolCall = message.role == "tool_call"
  val isTool = message.role == "tool" || message.role == "function" || isToolCall
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
      ?.replace("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+".toRegex(), "[🖼️ Screenshot attached]")
    ?: ""

  // For tool messages, try to pretty-print JSON content
  val displayMessage = if (isTool) {
    remember(cleanedMessage) {
      val jsonElement = tryParseJson(cleanedMessage)
      if (jsonElement != null) {
        prettyJson.encodeToString(JsonElement.serializer(), jsonElement)
      } else {
        cleanedMessage
      }
    }
  } else {
    cleanedMessage
  }

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
            isToolCall -> "Tool: ${message.toolName ?: "unknown"}"
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
            if (isTool && tryParseJson(cleanedMessage) != null) {
              // Render pretty-printed JSON with monospace font for tool messages
              Text(
                text = displayMessage,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
                  fontSize = 12.sp,
                  lineHeight = 18.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
              )
            } else {
              Markdown(
                modifier = Modifier.padding(16.dp),
                content = displayMessage,
              )
            }
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
