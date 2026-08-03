package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * [buildToolRunResponse] backs the Trailmaps / Trail Detail "Run on device" buttons: it decodes the
 * one-step unified trail yaml the web UI builds and runs its recorded tools on the connected device.
 * Because it replays onto a SINGLE device, the yaml must carry exactly one device classifier.
 */
class ToolRunResponseTest {

  private val deviceId = TrailblazeDeviceId(instanceId = "emulator-5554", trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID)

  private fun deps(root: File, executor: (suspend (TrailblazeTool, TrailblazeDeviceId?) -> String)?) =
    TrailRunnerDeps(
      trailsRootProvider = { File(root, "trails").apply { mkdirs() } },
      logsRepo = LogsRepo(logsDir = File(root, "logs").apply { mkdirs() }, watchFileSystem = false),
      settingsRepo = null,
      deviceManager = null,
      integrationsProvider = null,
      integrationActionHandler = null,
      analyticsProvider = null,
      analyticsCaptureStarter = null,
      eventCaptureController = null,
      toolExecutor = executor,
    )

  private fun withDeps(executor: (suspend (TrailblazeTool, TrailblazeDeviceId?) -> String)?, block: suspend (TrailRunnerDeps) -> Unit) {
    val root = createTempDirectory("tb-tool-run").toFile()
    try {
      runBlocking { block(deps(root, executor)) }
    } finally {
      root.deleteRecursively()
    }
  }

  private fun request(yaml: String) = ToolRunRequest(trailblazeDeviceId = deviceId, yaml = yaml)

  private val singleClassifierYaml = """
    config:
      title: "Run: Tap Pay"
    trail:
      - step: "Run: Tap Pay"
        recording:
          android:
          - tapOnPoint:
              x: 10
              y: 20
  """.trimIndent()

  private val multiClassifierYaml = """
    config:
      title: "Run: Tap Pay"
    trail:
      - step: "Run: Tap Pay"
        recording:
          android:
          - tapOnPoint:
              x: 10
              y: 20
          ios:
          - tapOnPoint:
              x: 30
              y: 40
  """.trimIndent()

  @Test
  fun runsTheRecordedToolsForASingleClassifier() {
    val executed = mutableListOf<TrailblazeTool>()
    withDeps(executor = { tool, _ -> executed.add(tool); "ok" }) { deps ->
      val resp = buildToolRunResponse(deps, request(singleClassifierYaml))
      assertTrue(resp.success, "single-classifier yaml runs: ${resp.error}")
      assertEquals(1, executed.size, "the one recorded android tool ran on the device")
    }
  }

  @Test
  fun refusesAMultiClassifierYamlWithoutRunningAnything() {
    val executed = mutableListOf<TrailblazeTool>()
    withDeps(executor = { tool, _ -> executed.add(tool); "ok" }) { deps ->
      val resp = buildToolRunResponse(deps, request(multiClassifierYaml))
      assertFalse(resp.success, "a multi-classifier tool-run yaml must be refused")
      val error = resp.error ?: error("expected an error message")
      assertTrue(error.contains("single device classifier"), "error explains the single-classifier rule: $error")
      assertTrue(error.contains("android") && error.contains("ios"), "error names the offending classifiers: $error")
      assertTrue(executed.isEmpty(), "no tool from either classifier ran on the connected device")
    }
  }
}
