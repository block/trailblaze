package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp

@Composable
fun FullScreenModalOverlay(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.7f))
      .focusRequester(focusRequester)
      .focusable()
      .onKeyEvent { keyEvent ->
        if (keyEvent.key == Key.Escape) {
          onDismiss()
          true
        } else {
          false
        }
      }
    // Removed background click dismissal - only Escape key and Close button can dismiss
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
        .background(
          MaterialTheme.colorScheme.background,
          shape = RoundedCornerShape(16.dp)
        )
      // No click handling at all - let all events pass through to content
    ) {
      content()
    }
  }
}