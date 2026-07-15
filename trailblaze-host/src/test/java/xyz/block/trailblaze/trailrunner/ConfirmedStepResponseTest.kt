package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * [confirmedStepResponse] is the Create screen's confirm-and-run dispatch: a gesture carrying the
 * author-confirmed step runs THAT step's tools (selector-driven) instead of replaying the raw
 * coordinate tap, and the response echoes the confirmed step so the HUMAN_ACTION event records
 * what the author actually approved.
 */
class ConfirmedStepResponseTest {

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
    val root = createTempDirectory("tb-confirmed-step").toFile()
    try {
      runBlocking { block(deps(root, executor)) }
    } finally {
      root.deleteRecursively()
    }
  }

  private val runYaml = """
    - config:
        title: "Run: Tap Pay"
    - prompts:
      - step: "Run: Tap Pay"
        recording:
          tools:
          - tapOnPoint:
              x: 10
              y: 20
  """.trimIndent()

  private fun request(runYaml: String? = null, resolveOnly: Boolean = false) = RecordGestureRequest(
    trailblazeDeviceId = deviceId,
    type = "tap",
    x = 10,
    y = 20,
    resolveOnly = resolveOnly,
    runId = "run-1",
    runYaml = runYaml,
    stepYaml = "- tools:\n  - tapOnPoint:\n      x: 10\n      y: 20",
    prompt = "Tap the Pay button",
    chosenOption = RecordToolOption(label = "By resource id", toolName = "tapOnElementBySelector", yaml = "- tools:\n  - tapOnElementBySelector: {}", isSelector = true),
    element = RecordElement(label = "Pay", type = "Button"),
  )

  @Test
  fun fallsThroughForResolveOnlyAndForPlainGestures() = withDeps(executor = { _, _ -> "ok" }) { deps ->
    assertNull(confirmedStepResponse(deps, request(runYaml = null)), "no runYaml -> raw gesture path")
    assertNull(confirmedStepResponse(deps, request(runYaml = runYaml, resolveOnly = true)), "resolveOnly never dispatches")
  }

  @Test
  fun runsTheConfirmedToolsAndEchoesTheConfirmedStep() {
    val executed = mutableListOf<TrailblazeTool>()
    withDeps(executor = { tool, _ -> executed.add(tool); "ok" }) { deps ->
      val resp = confirmedStepResponse(deps, request(runYaml = runYaml))!!
      assertTrue(resp.ok)
      assertEquals(1, executed.size, "the confirmed step's tool ran on the device")
      // The response is the confirmed step, not a re-resolution: the author's words become the
      // label, and the chosen selector/element ride along for the HUMAN_ACTION event.
      assertEquals("Tap the Pay button", resp.label)
      assertEquals("- tools:\n  - tapOnPoint:\n      x: 10\n      y: 20", resp.yaml)
      assertEquals("tapOnElementBySelector", resp.toolName)
      assertEquals(listOf("By resource id"), resp.options.map { it.label })
      assertEquals("Pay", resp.element?.label)
    }
  }

  @Test
  fun foldsAFailedRunIntoAStructuredError() = withDeps(executor = { _, _ -> throw IllegalStateException("element not found") }) { deps ->
    val resp = confirmedStepResponse(deps, request(runYaml = runYaml))!!
    assertEquals(false, resp.ok)
    assertTrue(resp.error!!.contains("element not found"))
  }
}
