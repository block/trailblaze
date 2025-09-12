package xyz.block.trailblaze.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * TestRail icon showing "TR" text
 * Simple and clear representation for TestRail
 */
@Composable
fun TextIcon(
  text: String,
  backgroundColor: Color = MaterialTheme.colorScheme.inverseOnSurface,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
  Box(
    modifier = Modifier
      .size(24.dp)
      .background(
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
      ),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = text,
      color = textColor,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      lineHeight = 10.sp // Ensures consistent line height
    )
  }
}
