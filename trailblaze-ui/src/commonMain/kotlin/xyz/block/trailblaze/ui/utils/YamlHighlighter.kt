package xyz.block.trailblaze.ui.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

data class YamlHighlightColors(
  val key: Color,
  val string: Color,
  val number: Color,
  val keyword: Color,
  val comment: Color,
  val listMarker: Color,
) {
  companion object {
    val Dark = YamlHighlightColors(
      key = Color(0xFF4DD0E1),
      string = Color(0xFF81C784),
      number = Color(0xFF64B5F6),
      keyword = Color(0xFFFFB74D),
      comment = Color(0xFF757575),
      listMarker = Color(0xFFFFB74D),
    )

    val Light = YamlHighlightColors(
      key = Color(0xFF00838F),
      string = Color(0xFF2E7D32),
      number = Color(0xFF1565C0),
      keyword = Color(0xFFE65100),
      comment = Color(0xFF9E9E9E),
      listMarker = Color(0xFFE65100),
    )
  }
}

private fun Color.luminance(): Float {
  val r = red
  val g = green
  val b = blue
  return 0.2126f * r + 0.7152f * g + 0.0722f * b
}
