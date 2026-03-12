package xyz.block.trailblaze.playwright.eval

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.TrailYamlTemplateResolver

/**
 * Eval tests for the Playwright Native driver.
 *
 * Each test runs a trail YAML file from `opensource/trails/playwright-native/` against the sample
 * web app in `opensource/examples/playwright-native/sample-app/` using the Playwright Native driver
 * (no Maestro).
 *
 * Trail files use `{{PROJECT_ROOT}}` template variables for file:// URLs so they work on any
 * machine and in CI.
 *
 * Tests run in parallel via JUnit 5 — each test gets its own browser instance.
 */
class PlaywrightNativeEvalTests {

  private val playwrightTest =
    BasePlaywrightNativeTest(
      trailblazeDeviceId =
        TrailblazeDeviceId(
          instanceId = "playwright-native-eval",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        )
    )

  @JvmField
  @RegisterExtension
  val loggingExtension = TestRuleExtension(playwrightTest.loggingRule)

  @AfterEach
  fun tearDown() {
    playwrightTest.close()
  }

  @Test fun test_counter() = runTrailFile("test-counter")

  @Test fun test_form_interaction() = runTrailFile("test-form-interaction")

  @Test fun test_navigation() = runTrailFile("test-navigation")

  @Test fun test_scroll_containers() = runTrailFile("test-scroll-containers")

  @Test fun test_duplicate_list() = runTrailFile("test-duplicate-list")

  @Test fun test_search_duplicates() = runTrailFile("test-search-duplicates")

  private fun runTrailFile(trailName: String) {
    val projectRoot = System.getProperty("user.dir")
    val trailDir = "$projectRoot/trails/playwright-native/$trailName"

    // Use device classifiers (e.g., ["web"]) to find the best trail file,
    // matching the same convention as BaseHostTrailblazeTest.runFromResource:
    //   1. web.trail.yaml    (device-specific recording)
    //   2. trailblaze.yaml   (fallback, AI mode)
    val deviceClassifiers = playwrightTest.trailblazeDeviceInfo.classifiers
    val trailFilePath = TrailRecordings.findBestTrailResourcePath(
      path = trailDir,
      deviceClassifiers = deviceClassifiers,
      doesResourceExist = { File(it).exists() },
    ) ?: error("No trail file found in $trailDir for classifiers $deviceClassifiers")

    val trailFile = File(trailFilePath)
    val yaml = TrailYamlTemplateResolver.resolve(
      yaml = trailFile.readText(),
      trailFile = trailFile,
    )

    runBlocking {
      playwrightTest.runTrailblazeYamlSuspend(
        yaml = yaml,
        trailblazeDeviceId = playwrightTest.trailblazeDeviceInfo.trailblazeDeviceId,
        trailFilePath = trailFile.absolutePath,
        sendSessionStartLog = true,
      )
    }
  }
}
