package xyz.block.trailblaze.compose.driver

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rules.BaseComposeTest
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.TrailYamlTemplateResolver

/**
 * Eval tests for the Compose Desktop driver.
 *
 * Each test runs a trail YAML file from `opensource/trails/compose-desktop/` against sample Compose
 * apps using the full Trailblaze pipeline: trail YAML -> TrailblazeRunner -> recorded steps -> tools.
 *
 * Mirrors the pattern of [PlaywrightNativeEvalTests] but for Compose Desktop.
 *
 * Trail files use the "desktop" classifier — the resolution order is:
 *   1. desktop.trail.yaml  (device-specific recording)
 *   2. trail.yaml           (fallback, AI mode)
 */
@OptIn(ExperimentalTestApi::class)
class ComposeDesktopEvalTests {

  private val composeTest =
    BaseComposeTest(
      trailblazeDeviceId =
        TrailblazeDeviceId(
          instanceId = "compose-desktop-eval",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        ),
    )

  @get:Rule val loggingRule = composeTest.loggingRule

  @Test fun test_add_todo() = runTrailFile("test-add-todo") { SampleTodoApp() }

  @Test fun test_manage_todos() = runTrailFile("test-manage-todos") { SampleTodoApp() }

  @Test
  fun test_widget_interactions() = runTrailFile("test-widget-interactions") { SampleWidgetShowcase() }

  @Test
  fun test_ambiguous_elements() =
    runTrailFile("test-ambiguous-elements") { SampleTodoApp(includeAmbiguousSection = true) }

  private fun runTrailFile(trailName: String, content: @Composable () -> Unit) =
    runComposeUiTest {
      setContent { content() }
      waitForIdle()

      val projectRoot = System.getProperty("user.dir")
      val trailDir = "$projectRoot/trails/compose-desktop/$trailName"
      val deviceClassifiers = composeTest.trailblazeDeviceInfo.classifiers

      val trailFilePath =
        TrailRecordings.findBestTrailResourcePath(
          path = trailDir,
          deviceClassifiers = deviceClassifiers,
          doesResourceExist = { File(it).exists() },
        ) ?: error("No trail file found in $trailDir for classifiers $deviceClassifiers")

      val trailFile = File(trailFilePath)
      val yaml =
        TrailYamlTemplateResolver.resolve(
          yaml = trailFile.readText(),
          trailFile = trailFile,
        )

      runBlocking {
        composeTest.runTestWithCompose(
          composeUiTest = this@runComposeUiTest,
          yaml = yaml,
          trailFilePath = trailFile.absolutePath,
          useRecordedSteps = true,
          sendSessionStartLog = true,
        )
      }
    }
}
