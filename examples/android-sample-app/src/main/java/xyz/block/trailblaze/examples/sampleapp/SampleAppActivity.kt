package xyz.block.trailblaze.examples.sampleapp

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import xyz.block.trailblaze.examples.sampleapp.ui.navigation.SampleAppNavigation
import xyz.block.trailblaze.examples.sampleapp.ui.screens.repro.AccessibilityTruncationReproScreen
import xyz.block.trailblaze.examples.sampleapp.startup.SampleAppStartupState
import xyz.block.trailblaze.examples.sampleapp.ui.theme.SampleAppTheme

class SampleAppActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Startup-produced state, read where a real app reads its DI graph: in onCreate, with no
    // fallback. The theme is NoActionBar so the title is never rendered — what this line is for is
    // the directed failure when the app's androidx.startup initializers did not run, which is the
    // in-process lane's tripwire on startup discovery. See SampleAppInitializers.
    title = SampleAppStartupState.requireGreeting()
    // Off-the-nav repro screen for the accessibility "truncated/partial hierarchy" fix
    // (see AccessibilityTruncationReproScreen). Opt in via the intent extra; normal launches
    // go straight to the app.
    val showTruncationRepro =
      intent?.getBooleanExtra(EXTRA_ACCESSIBILITY_TRUNCATION_REPRO, false) == true
    val reproFilled = intent?.getBooleanExtra(EXTRA_ACCESSIBILITY_TRUNCATION_REPRO_FILLED, false) == true
    setContent {
      SampleAppTheme {
        if (showTruncationRepro) {
          AccessibilityTruncationReproScreen(filled = reproFilled)
        } else {
          SampleAppNavigation()
        }
      }
    }
  }

  companion object {
    const val EXTRA_ACCESSIBILITY_TRUNCATION_REPRO = "tb_accessibility_truncation_repro"
    const val EXTRA_ACCESSIBILITY_TRUNCATION_REPRO_FILLED = "tb_accessibility_truncation_repro_filled"
  }
}
