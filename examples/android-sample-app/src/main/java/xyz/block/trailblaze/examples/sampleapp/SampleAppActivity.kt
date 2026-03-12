package xyz.block.trailblaze.examples.sampleapp

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import xyz.block.trailblaze.examples.sampleapp.ui.navigation.SampleAppNavigation
import xyz.block.trailblaze.examples.sampleapp.ui.theme.SampleAppTheme

class SampleAppActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { SampleAppTheme { SampleAppNavigation() } }
  }
}
