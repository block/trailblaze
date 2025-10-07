package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun LoadingButton(
  onClick: () -> Unit,
  text: String,
  loadingText: String = "Loading...",
  isLoading: Boolean = false,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !isLoading,
    modifier = modifier
  ) {
    if (isLoading) {
      CircularProgressIndicator(modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(8.dp))
    } else if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
    }
    Text(if (isLoading) loadingText else text)
  }
}
