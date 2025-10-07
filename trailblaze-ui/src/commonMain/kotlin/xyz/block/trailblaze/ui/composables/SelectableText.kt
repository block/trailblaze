package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import xyz.block.trailblaze.ui.theme.LocalFontScale

@Composable
fun SelectableText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = MaterialTheme.typography.bodyMedium,
  color: Color = Color.Unspecified,
  fontWeight: FontWeight? = null,
  maxLines: Int? = null,
) {
  val fontScale = LocalFontScale.current
  SelectionContainer {
    Text(
      text = text,
      modifier = modifier,
      style = style.copy(fontSize = style.fontSize * fontScale),
      color = color,
      fontWeight = fontWeight,
      maxLines = maxLines ?: Int.MAX_VALUE,
    )
  }
}