package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight


@Composable
fun SelectableText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
  color: Color = Color.Unspecified,
  fontWeight: FontWeight? = null,
  maxLines: Int? = null,
) {
  SelectionContainer {
    Text(
      text = text,
      modifier = modifier,
      style = style,
      color = color,
      fontWeight = fontWeight,
      maxLines = maxLines ?: Int.MAX_VALUE,
    )
  }
}