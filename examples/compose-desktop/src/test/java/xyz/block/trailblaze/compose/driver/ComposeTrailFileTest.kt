package xyz.block.trailblaze.compose.driver

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.util.Console

/**
 * Trail file tests for the Compose Desktop driver.
 *
 * Validates that trail YAML (tools, prompts with recordings, config) parses correctly via
 * [TrailblazeYaml] and executes against real Compose UI through [ComposeTrailblazeAgent].
 * No LLM needed — `tools:` items execute directly, `prompts:` with `recording:` sections replay
 * without LLM.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeTrailFileTest {

  private val trailblazeYaml = TrailblazeYaml.Default

  /** Stub [ElementComparator] — only used for memory tools, which these tests don't exercise. */
  private val stubElementComparator = object : ElementComparator {
    override fun getElementValue(prompt: String) = error("unused")
    override fun evaluateBoolean(statement: String) = error("unused")
    override fun evaluateString(query: String) = error("unused")
    override fun extractNumberFromString(input: String) = error("unused")
  }

  private fun createAgent(target: ComposeTestTarget): ComposeTrailblazeAgent {
    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "trail-file-test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
      ),
      trailblazeDriverType = TrailblazeDriverType.COMPOSE,
      widthPixels = 1280,
      heightPixels = 800,
    )
    return ComposeTrailblazeAgent(
      target = target,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = { deviceInfo },
      sessionProvider = {
        TrailblazeSession(
          sessionId = SessionId("trail-file-test-session"),
          startTime = Clock.System.now(),
        )
      },
    )
  }

  /**
   * Parses [yaml] into trail items, then executes each [TrailYamlItem.ToolTrailItem] through the
   * given [agent]. Config items are skipped. Returns the result of the last tool execution.
   */
  private fun executeToolTrailItems(
    agent: ComposeTrailblazeAgent,
    yaml: String,
  ): TrailblazeToolResult {
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    var lastResult: TrailblazeToolResult = TrailblazeToolResult.Success()
    for (item in trailItems) {
      when (item) {
        is TrailYamlItem.ToolTrailItem -> {
          val result = agent.runTrailblazeTools(
            tools = item.tools.map { it.trailblazeTool },
            traceId = null,
            screenState = null,
            elementComparator = stubElementComparator,
            screenStateProvider = null,
          )
          lastResult = result.result
          if (!result.result.isSuccess()) return result.result
        }
        is TrailYamlItem.ConfigTrailItem -> { /* skip */ }
        else -> error("Unexpected trail item type: ${item::class.simpleName}")
      }
    }
    return lastResult
  }

  /** Captures a PNG screenshot from the current ComposeUiTest. */
  private fun ComposeUiTest.captureScreenshot(): ByteArray {
    waitForIdle()
    val image = onRoot().captureToImage()
    return imageBitmapToPngBytes(image)
  }

  // -- Tools Trail Item Tests --

  @Test
  fun `tools trail item - add a todo`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
    - compose_click:
        testTag: add_button
    - compose_verify_text_visible:
        text: 1 items
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `tools trail item - add and delete a todo`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
    - compose_click:
        testTag: add_button
    - compose_verify_text_visible:
        text: 1 items
- tools:
    - compose_click:
        testTag: delete_button_0
    - compose_verify_text_visible:
        text: 0 items
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `tools trail item - verify element by testTag`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- tools:
    - compose_verify_element_visible:
        testTag: add_button
    - compose_verify_element_visible:
        testTag: todo_input
    - compose_verify_element_visible:
        testTag: item_count
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  // -- Snapshot Tool Test --

  @Test
  fun `tools trail item - snapshot captures screenshot`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    // First add a todo so there's visible content, then snapshot
    val yaml = """
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
    - compose_click:
        testTag: add_button
    - takeSnapshot:
        screenName: after_add_todo
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Also verify we can capture a screenshot directly
    val pngBytes = captureScreenshot()
    assertThat(pngBytes.size).isGreaterThan(0)
  }

  // -- Error / Failure Path Tests --

  @Test
  fun `tools trail item - verify missing text returns error`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- tools:
    - compose_verify_text_visible:
        text: this text does not exist anywhere
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `tools trail item - verify missing element returns error`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- tools:
    - compose_verify_element_visible:
        testTag: completely_nonexistent_tag
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `tools trail item - error stops execution of remaining tools`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    // The verify should fail, meaning the second type tool should NOT execute
    val yaml = """
- tools:
    - compose_verify_text_visible:
        text: this text does not exist
    - compose_type:
        text: should not be typed
        testTag: todo_input
    """.trimIndent()

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)

    // Verify the type tool was never executed — input should still be empty
    val screenState = ComposeScreenState(ComposeUiTestTarget(this), 1280, 800)
    val tree = screenState.viewHierarchy.aggregate()
    val input = tree.find { it.resourceId == "todo_input" }
    assertThat(input).isNotNull()
    // The item count should still be 0
    val counter = tree.find { it.resourceId == "item_count" }
    assertThat(counter).isNotNull()
    assertThat(counter!!.text!!).contains("0 items")
  }

  // -- Prompts with Recording Test --

  @Test
  fun `prompts with recordings replays recorded steps`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- prompts:
  - step: Type a todo item
    recording:
      tools:
        - compose_type:
            text: Buy groceries
            testTag: todo_input
  - step: Click the add button
    recording:
      tools:
        - compose_click:
            testTag: add_button
  - verify: Verify item count updated
    recording:
      tools:
        - compose_verify_text_visible:
            text: 1 items
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    assertThat(trailItems.size).isEqualTo(1)
    val promptsItem = trailItems[0] as TrailYamlItem.PromptsTrailItem
    assertThat(promptsItem.promptSteps.size).isEqualTo(3)

    // Replay the recorded tools through the agent — same path as
    // TrailblazeRunnerUtil.runPromptSuspend with useRecordedSteps=true.
    val agent = createAgent(ComposeUiTestTarget(this))
    for (promptStep in promptsItem.promptSteps) {
      val recording = promptStep.recording
      assertThat(recording).isNotNull()
      val tools = recording!!.tools.map { it.trailblazeTool }
      val result = agent.runTrailblazeTools(
        tools = tools,
        traceId = null,
        screenState = null,
        elementComparator = stubElementComparator,
        screenStateProvider = null,
      )
      assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    }
  }

  // -- Config Trail Item Tests --

  @Test
  fun `config trail item context is parsed correctly`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- config:
    id: compose-test
    title: Add a todo item
    context: "User should add a todo and verify it appears"
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
    - compose_click:
        testTag: add_button
    - compose_verify_text_visible:
        text: 1 items
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    assertThat(trailItems.size).isEqualTo(2)

    // Verify config was parsed
    val configItem = trailItems[0] as TrailYamlItem.ConfigTrailItem
    assertThat(configItem.config.id).isEqualTo("compose-test")
    assertThat(configItem.config.title).isEqualTo("Add a todo item")
    assertThat(configItem.config.context)
      .isEqualTo("User should add a todo and verify it appears")

    // Tools still execute correctly
    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `multi-step trail with config and tools`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val yaml = """
- config:
    id: multi-step-test
    title: Multi-step todo management
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
    - compose_click:
        testTag: add_button
- tools:
    - compose_type:
        text: Walk the dog
        testTag: todo_input
    - compose_click:
        testTag: add_button
    - compose_verify_text_visible:
        text: 2 items
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    assertThat(trailItems.size).isEqualTo(3)
    assertThat(trailItems[0]).isInstanceOf(TrailYamlItem.ConfigTrailItem::class)
    assertThat(trailItems[1]).isInstanceOf(TrailYamlItem.ToolTrailItem::class)
    assertThat(trailItems[2]).isInstanceOf(TrailYamlItem.ToolTrailItem::class)

    val result = executeToolTrailItems(createAgent(ComposeUiTestTarget(this)), yaml)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  // -- HTML Screenshot Report --

  @Test
  fun `generate screenshot report for trail execution`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    data class Step(val description: String, val yaml: String)

    val steps = listOf(
      Step("Initial state - empty todo list", ""),
      Step(
        "Type 'Buy groceries' into input",
        """
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
        """.trimIndent(),
      ),
      Step(
        "Click 'Add' button",
        """
- tools:
    - compose_click:
        testTag: add_button
        """.trimIndent(),
      ),
      Step(
        "Type 'Walk the dog' into input",
        """
- tools:
    - compose_type:
        text: Walk the dog
        testTag: todo_input
        """.trimIndent(),
      ),
      Step(
        "Click 'Add' button",
        """
- tools:
    - compose_click:
        testTag: add_button
        """.trimIndent(),
      ),
      Step(
        "Type 'Learn Compose' into input",
        """
- tools:
    - compose_type:
        text: Learn Compose
        testTag: todo_input
        """.trimIndent(),
      ),
      Step(
        "Click 'Add' button",
        """
- tools:
    - compose_click:
        testTag: add_button
        """.trimIndent(),
      ),
      Step(
        "Verify '3 items' visible",
        """
- tools:
    - compose_verify_text_visible:
        text: 3 items
        """.trimIndent(),
      ),
      Step(
        "Delete first todo (Buy groceries)",
        """
- tools:
    - compose_click:
        testTag: delete_button_0
        """.trimIndent(),
      ),
      Step(
        "Verify '2 items' visible",
        """
- tools:
    - compose_verify_text_visible:
        text: 2 items
        """.trimIndent(),
      ),
    )

    val agent = createAgent(ComposeUiTestTarget(this))
    val reportEntries = mutableListOf<ReportEntry>()

    for (step in steps) {
      if (step.yaml.isNotEmpty()) {
        val result = executeToolTrailItems(agent, step.yaml)
        assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      }
      waitForIdle()
      val pngBytes = captureScreenshot()
      val base64 = Base64.getEncoder().encodeToString(pngBytes)
      reportEntries.add(ReportEntry(step.description, step.yaml, base64))
    }

    val reportDir = File("build/reports/compose-trail")
    reportDir.mkdirs()
    val reportFile = File(reportDir, "trail-execution-report.html")
    reportFile.writeText(buildHtmlReport(reportEntries))

    Console.log("Screenshot report written to: ${reportFile.absolutePath}")
    assertThat(reportFile.exists()).isEqualTo(true)
    assertThat(reportFile.length()).isGreaterThan(0)
  }

  // -- Animated GIF --

  @Test
  fun `generate animated GIF of trail execution`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val trailSteps = listOf(
      "" to "Empty todo list",
      """
- tools:
    - compose_type:
        text: Buy groceries
        testTag: todo_input
      """.trimIndent() to "Typing 'Buy groceries'",
      """
- tools:
    - compose_click:
        testTag: add_button
      """.trimIndent() to "Added first todo",
      """
- tools:
    - compose_type:
        text: Walk the dog
        testTag: todo_input
      """.trimIndent() to "Typing 'Walk the dog'",
      """
- tools:
    - compose_click:
        testTag: add_button
      """.trimIndent() to "Added second todo",
      """
- tools:
    - compose_type:
        text: Learn Compose
        testTag: todo_input
      """.trimIndent() to "Typing 'Learn Compose'",
      """
- tools:
    - compose_click:
        testTag: add_button
      """.trimIndent() to "Added third todo",
      """
- tools:
    - compose_click:
        testTag: delete_button_1
      """.trimIndent() to "Deleted 'Walk the dog'",
      """
- tools:
    - compose_verify_text_visible:
        text: 2 items
      """.trimIndent() to "Verified 2 items remain",
    )

    val agent = createAgent(ComposeUiTestTarget(this))
    val framesDir = File("build/reports/compose-trail/frames")
    framesDir.mkdirs()

    for ((index, pair) in trailSteps.withIndex()) {
      val (yaml, _) = pair
      if (yaml.isNotEmpty()) {
        val result = executeToolTrailItems(agent, yaml)
        assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      }
      waitForIdle()
      val pngBytes = captureScreenshot()
      File(framesDir, "frame_${String.format("%03d", index)}.png").writeBytes(pngBytes)
    }

    // Stitch frames into animated GIF using ImageMagick (if available)
    val gifFile = File("build/reports/compose-trail/trail-execution.gif")
    try {
      val process = ProcessBuilder(
        "convert",
        "-delay", "120",
        "-loop", "0",
        "-resize", "640x",
        "${framesDir.absolutePath}/frame_*.png",
        gifFile.absolutePath,
      ).redirectErrorStream(true).start()
      val exitCode = process.waitFor()
      if (exitCode == 0) {
        Console.log("Animated GIF written to: ${gifFile.absolutePath}")
        Console.log("GIF size: ${gifFile.length() / 1024} KB")
        assertThat(gifFile.exists()).isEqualTo(true)
      } else {
        Console.log("ImageMagick convert exited with code $exitCode (GIF generation skipped)")
      }
    } catch (e: Exception) {
      Console.log("ImageMagick not available, skipping GIF generation: ${e.message}")
    }

    // Frames are always saved regardless of GIF generation
    val frameFiles = framesDir.listFiles()?.filter { it.extension == "png" } ?: emptyList()
    assertThat(frameFiles.size).isEqualTo(trailSteps.size)
    Console.log("${frameFiles.size} PNG frames saved to: ${framesDir.absolutePath}")
  }

  // -- Report Helpers --

  private data class ReportEntry(
    val description: String,
    val yaml: String,
    val screenshotBase64: String,
  )

  private fun buildHtmlReport(entries: List<ReportEntry>): String = buildString {
    appendLine("<!DOCTYPE html>")
    appendLine("<html lang=\"en\">")
    appendLine("<head>")
    appendLine("<meta charset=\"UTF-8\">")
    appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
    appendLine("<title>Compose Desktop Trail Execution Report</title>")
    appendLine("<style>")
    appendLine(
      """
      * { margin: 0; padding: 0; box-sizing: border-box; }
      body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
             background: #0f172a; color: #e2e8f0; padding: 2rem; }
      h1 { text-align: center; margin-bottom: 0.5rem; font-size: 1.8rem; color: #f8fafc; }
      .subtitle { text-align: center; color: #94a3b8; margin-bottom: 2rem; font-size: 0.9rem; }
      .timeline { max-width: 900px; margin: 0 auto; position: relative; }
      .timeline::before { content: ''; position: absolute; left: 24px; top: 0; bottom: 0;
                          width: 2px; background: #334155; }
      .step { display: flex; gap: 1.5rem; margin-bottom: 2rem; position: relative; }
      .step-number { flex-shrink: 0; width: 48px; height: 48px; border-radius: 50%;
                     background: #1e293b; border: 2px solid #3b82f6; display: flex;
                     align-items: center; justify-content: center; font-weight: 700;
                     color: #3b82f6; font-size: 1rem; z-index: 1; }
      .step-content { flex: 1; background: #1e293b; border-radius: 12px; overflow: hidden;
                      border: 1px solid #334155; }
      .step-header { padding: 1rem 1.25rem; border-bottom: 1px solid #334155; }
      .step-desc { font-weight: 600; color: #f1f5f9; }
      .step-yaml { margin-top: 0.5rem; }
      .step-yaml pre { background: #0f172a; padding: 0.75rem; border-radius: 6px;
                       font-size: 0.8rem; color: #7dd3fc; overflow-x: auto;
                       font-family: 'JetBrains Mono', 'Fira Code', monospace; }
      .step-screenshot { padding: 1rem; background: #0f172a; display: flex;
                         justify-content: center; }
      .step-screenshot img { max-width: 100%; height: auto; border-radius: 8px;
                             box-shadow: 0 4px 24px rgba(0,0,0,0.4); }
      .summary { max-width: 900px; margin: 2rem auto 0; padding: 1.25rem;
                 background: #1e293b; border-radius: 12px; border: 1px solid #334155;
                 text-align: center; }
      .summary .count { color: #22c55e; font-weight: 700; font-size: 1.3rem; }
      """.trimIndent(),
    )
    appendLine("</style>")
    appendLine("</head>")
    appendLine("<body>")
    appendLine("<h1>Compose Desktop Trail Execution Report</h1>")
    appendLine(
      "<p class=\"subtitle\">SampleTodoApp &mdash; End-to-end trail file execution with screenshots</p>",
    )
    appendLine("<div class=\"timeline\">")

    for ((index, entry) in entries.withIndex()) {
      appendLine("<div class=\"step\">")
      appendLine("  <div class=\"step-number\">${index + 1}</div>")
      appendLine("  <div class=\"step-content\">")
      appendLine("    <div class=\"step-header\">")
      appendLine("      <div class=\"step-desc\">${entry.description}</div>")
      if (entry.yaml.isNotEmpty()) {
        appendLine("      <div class=\"step-yaml\"><pre>${escapeHtml(entry.yaml)}</pre></div>")
      }
      appendLine("    </div>")
      appendLine(
        "    <div class=\"step-screenshot\"><img src=\"data:image/png;base64,${entry.screenshotBase64}\" alt=\"Step ${index + 1}\"/></div>",
      )
      appendLine("  </div>")
      appendLine("</div>")
    }

    appendLine("</div>")
    appendLine("<div class=\"summary\">")
    appendLine(
      "  <span class=\"count\">${entries.size} steps</span> executed successfully",
    )
    appendLine("</div>")
    appendLine("</body>")
    appendLine("</html>")
  }

  private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
}
