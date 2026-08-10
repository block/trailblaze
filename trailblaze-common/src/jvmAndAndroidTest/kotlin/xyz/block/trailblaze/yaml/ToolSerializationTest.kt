package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import maestro.SwipeDirection
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.toolcalls.commands.AssertMatchCountTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool.PressKeyCode
import xyz.block.trailblaze.toolcalls.commands.SleepTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForChangeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertMathTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertNotEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberNumberTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberWithAiTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.TrailSerializerTest.TotallyCustomTool

/**
 * These tests pin down each tool's YAML (de)serialization. The v1 top-level-list trail shape
 * (`- tools:`) is gone, so each recorded tool is wrapped in a unified `trail:` step under an
 * `android` recording classifier and decoded with that classifier. The wrapper only changes the
 * enclosing document shape — the per-tool object + typed args being asserted are unchanged.
 */
class ToolSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml(setOf(TotallyCustomTool::class))

  private val androidClassifier = listOf(TrailblazeDeviceClassifier("android"))

  /** Decode a unified doc and return the single recorded step's tool wrappers. */
  private fun decodeRecordedTools(yaml: String): List<TrailblazeToolYamlWrapper> =
    trailblazeYaml.decodeTrail(yaml, deviceClassifiers = androidClassifier)
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools

  // Tool deserialization tests
  @Test
  fun deserializeRememberTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - rememberText:
            prompt: here is a prompt
            variable: promptVar
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "rememberText",
        trailblazeTool = RememberTextTrailblazeTool(
          prompt = "here is a prompt",
          variable = "promptVar",
        ),
      ),
    )
  }

  @Test
  fun deserializeRememberNumberTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - rememberNumber:
            prompt: here is a prompt
            variable: promptVar
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "rememberNumber",
        trailblazeTool = RememberNumberTrailblazeTool(
          prompt = "here is a prompt",
          variable = "promptVar",
        ),
      ),
    )
  }

  @Test
  fun deserializeRememberWithAiTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - rememberWithAi:
            prompt: here is a prompt
            variable: promptVar
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "rememberWithAi",
        trailblazeTool = RememberWithAiTrailblazeTool(
          prompt = "here is a prompt",
          variable = "promptVar",
        ),
      ),
    )
  }

  @Test
  fun deserializeAssertMathTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - assertMath:
            expression: "[[number of water bottles available]] - {{stockCount}}"
            expected: 1
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "assertMath",
        trailblazeTool = AssertMathTrailblazeTool(
          expression = "[[number of water bottles available]] - {{stockCount}}",
          expected = "1",
        ),
      ),
    )
  }

  @Test
  fun deserializeAssertEqualsTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - assertEquals:
            actual: "some actual value"
            expected: "some expected value"
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "assertEquals",
        trailblazeTool = AssertEqualsTrailblazeTool(
          actual = "some actual value",
          expected = "some expected value",
        ),
      ),
    )
  }

  @Test
  fun deserializeAssertNotEqualsTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - assertNotEquals:
            actual: "some actual value"
            expected: "some expected value"
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "assertNotEquals",
        trailblazeTool = AssertNotEqualsTrailblazeTool(
          actual = "some actual value",
          expected = "some expected value",
        ),
      ),
    )
  }

  @Test
  fun deserializeHideKeyboardTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - hideKeyboard: {}
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    with(tools[0]) {
      assertThat(name).isEqualTo("hideKeyboard")
      assertThat(trailblazeTool).isInstanceOf(HideKeyboardTrailblazeTool::class)
    }
  }

  @Test
  fun deserializeInputTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - inputText:
            text: Text to enter
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "inputText",
      trailblazeTool = InputTextTrailblazeTool(
        text = "Text to enter",
      ),
    )
  }

  @Test
  fun deserializeEmptyEraseTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - eraseText: {}
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    val wrapper = tools[0]
    assertThat(wrapper.name).isEqualTo("eraseText")
    // eraseText is now a YAML-defined (`tools:` mode) tool — decodes to
    // YamlDefinedTrailblazeTool with an empty caller-params map.
    assertThat(wrapper.trailblazeTool)
      .isInstanceOf(xyz.block.trailblaze.config.YamlDefinedTrailblazeTool::class)
  }

  @Test
  fun deserializeEraseTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - eraseText:
            charactersToErase: 10
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    val wrapper = tools[0]
    assertThat(wrapper.name).isEqualTo("eraseText")
    val tool = wrapper.trailblazeTool as xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
    assertThat(tool.params.containsKey("charactersToErase")).isEqualTo(true)
  }

  @Test
  fun deserializePressBackTool() {
    // `pressBack` is now a YAML-defined tool (see trails/config/tools/pressBack.yaml).
    // It deserializes as a YamlDefinedTrailblazeTool rather than a KClass-backed data object.
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - pressBack: {}
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    val wrapper = tools[0]
    assertThat(wrapper.name).isEqualTo("pressBack")
    assertThat(wrapper.trailblazeTool).isInstanceOf(xyz.block.trailblaze.config.YamlDefinedTrailblazeTool::class)
  }

  @Test
  fun deserializePressKeyEnterTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - pressKey:
            keyCode: ENTER
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "pressKey",
        trailblazeTool = PressKeyTrailblazeTool(keyCode = PressKeyCode.ENTER),
      ),
    )
  }

  @Test
  fun deserializePressKeyHomeTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - pressKey:
            keyCode: HOME
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "pressKey",
        trailblazeTool = PressKeyTrailblazeTool(keyCode = PressKeyCode.HOME),
      ),
    )
  }

  @Test
  fun deserializePressKeyLowercaseKeyCode() {
    // The LLM sometimes emits lowercase enum values; the custom serializer normalizes them.
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - pressKey:
            keyCode: enter
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "pressKey",
        trailblazeTool = PressKeyTrailblazeTool(keyCode = PressKeyCode.ENTER),
      ),
    )
  }

  @Test
  fun deserializeSwipeUpTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: UP
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.UP,
      ),
    )
  }

  @Test
  fun deserializeSwipeDownTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: DOWN
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.DOWN,
      ),
    )
  }

  @Test
  fun deserializeSwipeLeftTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: LEFT
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.LEFT,
      ),
    )
  }

  @Test
  fun deserializeSwipeRightTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: RIGHT
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.RIGHT,
      ),
    )
  }

  @Test
  fun deserializeSwipeUpToolWithText() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: UP
            swipeOnElementText: Text
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.UP,
        swipeOnElementText = "Text",
      ),
    )
  }

  @Test
  fun deserializeSwipeDownToolWithText() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: DOWN
            swipeOnElementText: Text
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.DOWN,
        swipeOnElementText = "Text",
      ),
    )
  }

  @Test
  fun deserializeSwipeLeftToolWithText() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: LEFT
            swipeOnElementText: Text
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.LEFT,
        swipeOnElementText = "Text",
      ),
    )
  }

  @Test
  fun deserializeSwipeRightToolWithText() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - swipe:
            direction: RIGHT
            swipeOnElementText: Text
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "swipe",
      trailblazeTool = SwipeTrailblazeTool(
        direction = SwipeDirection.RIGHT,
        swipeOnElementText = "Text",
      ),
    )
  }

  @Test
  fun deserializeWaitForIdleSync() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - wait: {}
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "wait",
      trailblazeTool = WaitForIdleSyncTrailblazeTool(
        timeToWaitInSeconds = 5,
      ),
    )
  }

  @Test
  fun deserializeWaitForIdleSyncWithTime() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - wait:
            timeToWaitInSeconds: 15
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "wait",
      trailblazeTool = WaitForIdleSyncTrailblazeTool(
        timeToWaitInSeconds = 15,
      ),
    )
  }

  @Test
  fun deserializeWaitForChangeDefaults() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - waitForChange: {}
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "waitForChange",
        trailblazeTool = WaitForChangeTrailblazeTool(),
      ),
    )
  }

  @Test
  fun deserializeWaitForChangeWithFields() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - waitForChange:
            timeoutMs: 12000
            quietWindowMs: 500
            requireChange: false
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "waitForChange",
        trailblazeTool = WaitForChangeTrailblazeTool(
          timeoutMs = 12000,
          quietWindowMs = 500,
          requireChange = false,
        ),
      ),
    )
  }

  @Test
  fun waitForChangeRoundTrip() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - waitForChange:
            timeoutMs: 12000
            quietWindowMs: 500
            requireChange: false
    """.trimIndent()

    // Round-trip the recorded tool itself (encode/decode the tool-wrapper list), not the raw
    // trail-document string — the tool object surviving the trip is the contract under test.
    val tools = decodeRecordedTools(yaml)
    val reDecoded = trailblazeYaml.decodeTools(trailblazeYaml.encodeTools(tools))
    assertThat(reDecoded.size).isEqualTo(1)
    assertThat(reDecoded[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "waitForChange",
        trailblazeTool = WaitForChangeTrailblazeTool(
          timeoutMs = 12000,
          quietWindowMs = 500,
          requireChange = false,
        ),
      ),
    )
  }

  @Test
  fun deserializeSleepDefaults() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - sleep: {}
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "sleep",
        trailblazeTool = SleepTrailblazeTool(),
      ),
    )
  }

  @Test
  fun deserializeSleepWithDuration() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - sleep:
            durationMs: 12000
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "sleep",
        trailblazeTool = SleepTrailblazeTool(durationMs = 12000),
      ),
    )
  }

  @Test
  fun sleepRoundTrip() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - sleep:
            durationMs: 12000
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    val reDecoded = trailblazeYaml.decodeTools(trailblazeYaml.encodeTools(tools))
    assertThat(reDecoded.size).isEqualTo(1)
    assertThat(reDecoded[0]).isEqualTo(
      TrailblazeToolYamlWrapper(
        name = "sleep",
        trailblazeTool = SleepTrailblazeTool(durationMs = 12000),
      ),
    )
  }

  @Test
  fun deserializeLaunchAppTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - launchApp:
            appId: com.example.myapp.debug
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "wait",
      trailblazeTool = LaunchAppTrailblazeTool(
        appId = "com.example.myapp.debug",
        launchMode = LaunchMode.REINSTALL,
      ),
    )
  }

  @Test
  fun deserializeLaunchAppToolReinstall() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - launchApp:
            appId: com.example.myapp.debug
            launchMode: REINSTALL
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "wait",
      trailblazeTool = LaunchAppTrailblazeTool(
        appId = "com.example.myapp.debug",
        launchMode = LaunchMode.REINSTALL,
      ),
    )
  }

  @Test
  fun deserializeLaunchAppToolResume() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - launchApp:
            appId: com.example.myapp.debug
            launchMode: RESUME
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "wait",
      trailblazeTool = LaunchAppTrailblazeTool(
        appId = "com.example.myapp.debug",
        launchMode = LaunchMode.RESUME,
      ),
    )
  }

  @Test
  fun deserializeLaunchAppToolForceRestart() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - launchApp:
            appId: com.example.myapp.debug
            launchMode: FORCE_RESTART
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "wait",
      trailblazeTool = LaunchAppTrailblazeTool(
        appId = "com.example.myapp.debug",
        launchMode = LaunchMode.FORCE_RESTART,
      ),
    )
  }

  @Test
  fun deserializeTapOnPointTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - tapOnPoint:
            x: 100
            y: 200
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "tapOnPoint",
      trailblazeTool = TapOnPointTrailblazeTool(
        x = 100,
        y = 200,
      ),
    )
  }

  @Test
  fun deserializeTapOnElementWithTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - tapOnElementWithText:
            text: Sign Out
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "tapOnElementWithText",
      trailblazeTool = TapOnElementWithTextTrailblazeTool(
        text = "Sign Out",
        index = 0,
        id = null,
        enabled = null,
        selected = null,
      ),
    )
  }

  @Test
  fun deserializeTapOnElementWithAccessibilityTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - tapOnElementWithAccessibilityText:
            accessibilityText: Accounts
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "tapOnElementWithAccessibilityText",
      trailblazeTool = TapOnElementWithAccessiblityTextTrailblazeTool(
        accessibilityText = "Accounts",
        index = null,
        id = null,
        enabled = null,
        selected = null,
      ),
    )
  }

  @Test
  fun deserializeLongPressOnElementWithTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - longPressOnElementWithText:
            text: Sign Out
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "tapOnElementWithText",
      trailblazeTool = LongPressOnElementWithTextTrailblazeTool(
        text = "Sign Out",
        index = null,
        id = null,
        enabled = null,
        selected = null,
      ),
    )
  }

  @Test
  fun deserializeLongPressOnElementWithAccessibilityTextTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - longPressElementWithAccessibilityText:
            accessibilityText: Accounts
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    TrailblazeToolYamlWrapper(
      name = "tapOnElementWithAccessibilityText",
      trailblazeTool = LongPressElementWithAccessibilityTextTrailblazeTool(
        accessibilityText = "Accounts",
        index = null,
        id = null,
        enabled = null,
        selected = null,
      ),
    )
  }

  @Test
  fun deserializeMaestroTool() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - mobile_maestro:
            commands:
              - extendedWaitUntil:
                  notVisible: Gift card added to cart
                  timeout: 20000
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0].name).isEqualTo("mobile_maestro")
    assertThat(tools[0].trailblazeTool).isInstanceOf(MaestroTrailblazeTool::class)
    with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
      // yaml holds the Maestro commands-list YAML; substring checks keep this stable
      // whether kaml renders flow style or block style.
      assertThat(yaml).contains("extendedWaitUntil")
      assertThat(yaml).contains("Gift card added to cart")
      assertThat(yaml).contains("20000")
    }
  }

  @Test
  fun deserializeMaestroToolMultipleCommands() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - mobile_maestro:
            commands:
              - assertVisible:
                  text: Hello
              - tapOn:
                  text: OK
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
      assertThat(yaml).contains("assertVisible")
      assertThat(yaml).contains("tapOn")
    }
  }

  @Test
  fun maestroToolRoundTrip() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - mobile_maestro:
            commands:
              - extendedWaitUntil:
                  notVisible: Gift card added to cart
                  timeout: 20000
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    val reDecoded = trailblazeYaml.decodeTools(trailblazeYaml.encodeTools(tools))
    assertThat(reDecoded.size).isEqualTo(1)
    assertThat(reDecoded[0].name).isEqualTo("mobile_maestro")
    with(reDecoded[0].trailblazeTool as MaestroTrailblazeTool) {
      assertThat(yaml).contains("extendedWaitUntil")
    }
  }

  @Test
  fun deserializeMaestroSetOrientation() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - mobile_maestro:
            commands:
              - setOrientation: LANDSCAPE_LEFT
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0].name).isEqualTo("mobile_maestro")
    assertThat(tools[0].trailblazeTool).isInstanceOf(MaestroTrailblazeTool::class)
    with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
      assertThat(yaml).contains("setOrientation")
      assertThat(yaml).contains("LANDSCAPE_LEFT")
    }
  }

  @Test
  fun maestroSetOrientationRoundTrip() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - mobile_maestro:
            commands:
              - setOrientation: LANDSCAPE_LEFT
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    val reDecoded = trailblazeYaml.decodeTools(trailblazeYaml.encodeTools(tools))
    assertThat(reDecoded.size).isEqualTo(1)
    assertThat(reDecoded[0].name).isEqualTo("mobile_maestro")
    with(reDecoded[0].trailblazeTool as MaestroTrailblazeTool) {
      assertThat(yaml).contains("setOrientation")
    }
  }

  @Test
  fun deserializeAssertVisibleBySelectorWithNodeSelector() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - assertVisibleBySelector:
            reason: The ALARM tab should be visible.
            nodeSelector:
              androidAccessibility:
                textRegex: ALARM
                resourceIdRegex: "android:id/text1"
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    val tool = tools[0].trailblazeTool as AssertVisibleBySelectorTrailblazeTool
    assertThat(tool.reason).isEqualTo("The ALARM tab should be visible.")
    assertThat(tool.nodeSelector).isNotNull()
    val match = tool.nodeSelector!!.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertThat(match.textRegex).isEqualTo("ALARM")
    assertThat(match.resourceIdRegex).isEqualTo("android:id/text1")
  }

  @Test
  fun deserializeAssertMatchCountWithNodeSelector() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - assertMatchCount:
            reason: The report should list at least one item row.
            min: 1
            nodeSelector:
              androidAccessibility:
                textRegex: "Net sales by item"
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    assertThat(tools.size).isEqualTo(1)
    assertThat(tools[0].name).isEqualTo("assertMatchCount")
    val tool = tools[0].trailblazeTool as AssertMatchCountTrailblazeTool
    assertThat(tool.reason).isEqualTo("The report should list at least one item row.")
    assertThat(tool.min).isEqualTo(1)
    val match = tool.nodeSelector!!.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertThat(match.textRegex).isEqualTo("Net sales by item")
  }

  @Test
  fun assertMatchCountExactRoundTrip() {
    val yaml = """
config: {}
trail:
  - step: recorded
    recording:
      android:
        - assertMatchCount:
            exact: 3
            nodeSelector:
              androidAccessibility:
                textRegex: "Item row"
    """.trimIndent()

    val tools = decodeRecordedTools(yaml)
    val reDecoded = trailblazeYaml.decodeTools(trailblazeYaml.encodeTools(tools))
    assertThat(reDecoded.size).isEqualTo(1)
    assertThat(reDecoded[0].name).isEqualTo("assertMatchCount")
    val tool = reDecoded[0].trailblazeTool as AssertMatchCountTrailblazeTool
    assertThat(tool.exact).isEqualTo(3)
    assertThat(tool.min).isEqualTo(null)
  }
}
