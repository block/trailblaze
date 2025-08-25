package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.theme.isDarkTheme

@Composable
fun CodeBlock(text: String) {
  val backgroundColor = if (isDarkTheme()) Color(0xFF2D2D2D) else Color(0xFFF6F8FA)
  val textColor = if (isDarkTheme()) Color(0xFFE0E0E0) else Color.Black
  val borderColor = if (isDarkTheme()) Color(0xFF444444) else Color(0xFFE1E4E8)

  Surface(
    color = backgroundColor,
    shape = RoundedCornerShape(6.dp),
    border = BorderStroke(1.dp, borderColor),
    modifier = Modifier.fillMaxWidth()
  ) {
    SelectionContainer {
      BasicTextField(
        value = text,
        onValueChange = {},
        readOnly = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
          fontFamily = FontFamily.Monospace,
          color = textColor
        ),
        modifier = Modifier
          .padding(16.dp)
          .fillMaxWidth()
      )
    }
  }
}