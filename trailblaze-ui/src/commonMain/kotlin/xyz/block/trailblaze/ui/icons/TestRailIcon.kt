package xyz.block.trailblaze.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun TestRailIcon(
  modifier: Modifier = Modifier,
  backgroundColor: Color = Color(0xFF7CB342), // TestRail green
  textColor: Color = Color.White,
  useSimpleVersion: Boolean = false, // Kept for compatibility but not used
) {
  Box(
    modifier = modifier
      .size(24.dp)
      .background(
        color = backgroundColor,
        shape = RoundedCornerShape(4.dp)
      ),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = "TR",
      color = textColor,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      textAlign = TextAlign.Center,
      lineHeight = 10.sp // Ensures consistent line height
    )
  }
}
