package xyz.block.trailblaze.playwright.eval

import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.ElectronAppConfig

/**
 * Eval tests for the Playwright Electron driver.
 *
 * Each test runs a trail YAML file from `trails/playwright-electron/` against the sample
 * Electron app in `examples/playwright-electron/sample-app/` which loads the same
 * `index.html` fixture used by the playwright-native tests.
 *
 * Unlike the playwright-native trails, these trails do NOT include an initial navigate step —
 * the Electron app pre-loads `index.html` via `main.js`.
 *
 * Tests run in parallel via JUnit 5 — each test gets its own Electron instance on a unique port.
 */
class PlaywrightElectronEvalTests {

  private val electronBinary: String =
    System.getProperty("trailblaze.test.electron.binary")
      ?: error("System property 'trailblaze.test.electron.binary' is not set")

  private val electronAppDir: String =
    System.getProperty("trailblaze.test.electron.app.dir")
      ?: error("System property 'trailblaze.test.electron.app.dir' is not set")

  private val headless: Boolean =
    System.getProperty("trailblaze.test.electron.headless", "true").toBoolean()

  @Test fun test_counter() = runTrailFile("test-counter")

  @Test fun test_form_interaction() = runTrailFile("test-form-interaction")

  @Test fun test_navigation() = runTrailFile("test-navigation")

  /** Finds a free local port by briefly binding to port 0. */
  private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

  private fun runTrailFile(trailName: String) {
    val cdpPort = findFreePort()
    val electronTest =
      BasePlaywrightElectronTest(
        electronAppConfig =
          ElectronAppConfig(
            command = electronBinary,
            args = listOf("--disable-gpu-shader-disk-cache", electronAppDir),
            cdpPort = cdpPort,
            headless = headless,
          ),
        trailblazeDeviceId =
          TrailblazeDeviceId(
            instanceId = "playwright-electron-eval-$cdpPort",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
          ),
      )

    try {
      val description =
        Description.createTestDescription(PlaywrightElectronEvalTests::class.java, trailName)
      val statement =
        object : Statement() {
          override fun evaluate() {
            val projectRoot = System.getProperty("user.dir")
            val trailDir = "$projectRoot/trails/playwright-electron/$trailName"

            val deviceClassifiers = electronTest.trailblazeDeviceInfo.classifiers
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
              electronTest.runTrailblazeYamlSuspend(
                yaml = yaml,
                trailblazeDeviceId = electronTest.trailblazeDeviceInfo.trailblazeDeviceId,
                trailFilePath = trailFile.absolutePath,
                sendSessionStartLog = true,
              )
            }
          }
        }
      electronTest.loggingRule.apply(statement, description).evaluate()
    } finally {
      electronTest.close()
    }
  }
}
