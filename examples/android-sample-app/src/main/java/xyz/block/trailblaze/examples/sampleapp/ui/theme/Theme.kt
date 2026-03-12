package xyz.block.trailblaze.examples.sampleapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme()

@Composable
fun SampleAppTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = LightColorScheme, content = content)
}
