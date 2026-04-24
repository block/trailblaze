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
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool.PressKeyCode
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithAccessiblityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertMathTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.AssertNotEqualsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberNumberTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberWithAiTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.TrailSerializerTest.TotallyCustomTool

class ToolSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml(setOf(TotallyCustomTool::class))

  // Tool deserialization tests
  @Test
  fun deserializeRememberTextTool() {
    val yaml = """
- tools:
    - rememberText:
        prompt: here is a prompt
        variable: promptVar
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeRememberNumberTool() {
    val yaml = """
- tools:
    - rememberNumber:
        prompt: here is a prompt
        variable: promptVar
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeRememberWithAiTool() {
    val yaml = """
- tools:
    - rememberWithAi:
        prompt: here is a prompt
        variable: promptVar
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeAssertMathTool() {
    val yaml = """
- tools:
    - assertMath:
        expression: "[[number of water bottles available]] - {{stockCount}}"
        expected: 1
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeAssertEqualsTool() {
    val yaml = """
- tools:
    - assertEquals:
        actual: "some actual value"
        expected: "some expected value"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeAssertNotEqualsTool() {
    val yaml = """
- tools:
    - assertNotEquals:
        actual: "some actual value"
        expected: "some expected value"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeHideKeyboardTool() {
    val yaml = """
- tools:
    - hideKeyboard: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        with(tools[0]) {
          assertThat(name).isEqualTo("hideKeyboard")
          assertThat(trailblazeTool).isInstanceOf(HideKeyboardTrailblazeTool::class)
        }
      }
    }
  }

  @Test
  fun deserializeInputTextTool() {
    val yaml = """
- tools:
    - inputText:
        text: Text to enter
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = InputTextTrailblazeTool(
            text = "Text to enter",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeEmptyEraseTextTool() {
    val yaml = """
- tools:
    - eraseText: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        val wrapper = tools[0]
        assertThat(wrapper.name).isEqualTo("eraseText")
        // eraseText is now a YAML-defined (`tools:` mode) tool — decodes to
        // YamlDefinedTrailblazeTool with an empty caller-params map.
        assertThat(wrapper.trailblazeTool)
          .isInstanceOf(xyz.block.trailblaze.config.YamlDefinedTrailblazeTool::class)
      }
    }
  }

  @Test
  fun deserializeEraseTextTool() {
    val yaml = """
- tools:
    - eraseText:
        charactersToErase: 10
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        val wrapper = tools[0]
        assertThat(wrapper.name).isEqualTo("eraseText")
        val tool = wrapper.trailblazeTool as xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
        assertThat(tool.params.containsKey("charactersToErase")).isEqualTo(true)
      }
    }
  }

  @Test
  fun deserializePressBackTool() {
    // `pressBack` is now a YAML-defined tool (see trailblaze-config/tools/pressBack.yaml).
    // It deserializes as a YamlDefinedTrailblazeTool rather than a KClass-backed data object.
    val yaml = """
- tools:
    - pressBack: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        val wrapper = tools[0]
        assertThat(wrapper.name).isEqualTo("pressBack")
        assertThat(wrapper.trailblazeTool).isInstanceOf(xyz.block.trailblaze.config.YamlDefinedTrailblazeTool::class)
      }
    }
  }

  @Test
  fun deserializePressKeyEnterTool() {
    val yaml = """
- tools:
    - pressKey:
        keyCode: ENTER
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "pressKey",
            trailblazeTool = PressKeyTrailblazeTool(keyCode = PressKeyCode.ENTER),
          ),
        )
      }
    }
  }

  @Test
  fun deserializePressKeyHomeTool() {
    val yaml = """
- tools:
    - pressKey:
        keyCode: HOME
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "pressKey",
            trailblazeTool = PressKeyTrailblazeTool(keyCode = PressKeyCode.HOME),
          ),
        )
      }
    }
  }

  @Test
  fun deserializePressKeyLowercaseKeyCode() {
    // The LLM sometimes emits lowercase enum values; the custom serializer normalizes them.
    val yaml = """
- tools:
    - pressKey:
        keyCode: enter
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0]).isEqualTo(
          TrailblazeToolYamlWrapper(
            name = "pressKey",
            trailblazeTool = PressKeyTrailblazeTool(keyCode = PressKeyCode.ENTER),
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeUpTool() {
    val yaml = """
- tools:
    - swipe:
        direction: UP
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.UP,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeDownTool() {
    val yaml = """
- tools:
    - swipe:
        direction: DOWN
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.DOWN,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeLeftTool() {
    val yaml = """
- tools:
    - swipe:
        direction: LEFT
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.LEFT,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeRightTool() {
    val yaml = """
- tools:
    - swipe:
        direction: RIGHT
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.RIGHT,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeUpToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: UP
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.UP,
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeDownToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: DOWN
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.DOWN,
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeLeftToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: LEFT
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.LEFT,
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeSwipeRightToolWithText() {
    val yaml = """
- tools:
    - swipe:
        direction: RIGHT
        swipeOnElementText: Text
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "swipe",
          trailblazeTool = SwipeTrailblazeTool(
            direction = SwipeDirection.RIGHT,
            swipeOnElementText = "Text",
          ),
        )
      }
    }
  }

  @Test
  fun deserializeWaitForIdleSync() {
    val yaml = """
- tools:
    - wait: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = WaitForIdleSyncTrailblazeTool(
            timeToWaitInSeconds = 5,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeWaitForIdleSyncWithTime() {
    val yaml = """
- tools:
    - wait:
        timeToWaitInSeconds: 15
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = WaitForIdleSyncTrailblazeTool(
            timeToWaitInSeconds = 15,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppTool() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.example.myapp.debug
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.example.myapp.debug",
            launchMode = LaunchMode.REINSTALL,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppToolReinstall() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.example.myapp.debug
        launchMode: REINSTALL
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.example.myapp.debug",
            launchMode = LaunchMode.REINSTALL,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppToolResume() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.example.myapp.debug
        launchMode: RESUME
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.example.myapp.debug",
            launchMode = LaunchMode.RESUME,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeLaunchAppToolForceRestart() {
    val yaml = """
- tools:
    - launchApp:
        appId: com.example.myapp.debug
        launchMode: FORCE_RESTART
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.example.myapp.debug",
            launchMode = LaunchMode.FORCE_RESTART,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeTapOnPointTool() {
    val yaml = """
- tools:
    - tapOnPoint:
        x: 100
        y: 200
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "tapOnPoint",
          trailblazeTool = TapOnPointTrailblazeTool(
            x = 100,
            y = 200,
          ),
        )
      }
    }
  }

  @Test
  fun deserializeTapOnElementWithTextTool() {
    val yaml = """
- tools:
    - tapOnElementWithText:
        text: Sign Out
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeTapOnElementWithAccessibilityTextTool() {
    val yaml = """
- tools:
    - tapOnElementWithAccessibilityText:
        accessibilityText: Accounts
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeLongPressOnElementWithTextTool() {
    val yaml = """
- tools:
    - longPressOnElementWithText:
        text: Sign Out
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeLongPressOnElementWithAccessibilityTextTool() {
    val yaml = """
- tools:
    - longPressElementWithAccessibilityText:
        accessibilityText: Accounts
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
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
    }
  }

  @Test
  fun deserializeMaestroTool() {
    val yaml = """
- tools:
    - maestro:
        commands:
          - extendedWaitUntil:
              notVisible: Gift card added to cart
              timeout: 20000
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0].name).isEqualTo("maestro")
        assertThat(tools[0].trailblazeTool).isInstanceOf(MaestroTrailblazeTool::class)
        with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
          // yaml holds the Maestro commands-list YAML; substring checks keep this stable
          // whether kaml renders flow style or block style.
          assertThat(yaml).contains("extendedWaitUntil")
          assertThat(yaml).contains("Gift card added to cart")
          assertThat(yaml).contains("20000")
        }
      }
    }
  }

  @Test
  fun deserializeMaestroToolMultipleCommands() {
    val yaml = """
- tools:
    - maestro:
        commands:
          - assertVisible:
              text: Hello
          - tapOn:
              text: OK
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
          assertThat(yaml).contains("assertVisible")
          assertThat(yaml).contains("tapOn")
        }
      }
    }
  }

  @Test
  fun maestroToolRoundTrip() {
    val yaml = """
- tools:
    - maestro:
        commands:
          - extendedWaitUntil:
              notVisible: Gift card added to cart
              timeout: 20000
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    val reEncoded = trailblazeYaml.encodeToString(trailItems)

    val reDecoded = trailblazeYaml.decodeTrail(reEncoded)
    with(reDecoded) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0].name).isEqualTo("maestro")
        with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
          assertThat(yaml).contains("extendedWaitUntil")
        }
      }
    }
  }

  @Test
  fun deserializeMaestroSetOrientation() {
    val yaml = """
- tools:
    - maestro:
        commands:
          - setOrientation: LANDSCAPE_LEFT
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0].name).isEqualTo("maestro")
        assertThat(tools[0].trailblazeTool).isInstanceOf(MaestroTrailblazeTool::class)
        with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
          assertThat(yaml).contains("setOrientation")
          assertThat(yaml).contains("LANDSCAPE_LEFT")
        }
      }
    }
  }

  @Test
  fun maestroSetOrientationRoundTrip() {
    val yaml = """
- tools:
    - maestro:
        commands:
          - setOrientation: LANDSCAPE_LEFT
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    val reEncoded = trailblazeYaml.encodeToString(trailItems)

    val reDecoded = trailblazeYaml.decodeTrail(reEncoded)
    with(reDecoded) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        assertThat(tools[0].name).isEqualTo("maestro")
        with(tools[0].trailblazeTool as MaestroTrailblazeTool) {
          assertThat(yaml).contains("setOrientation")
        }
      }
    }
  }

  @Test
  fun deserializeAssertVisibleBySelectorWithNodeSelector() {
    val yaml = """
- tools:
    - assertVisibleBySelector:
        reason: The ALARM tab should be visible.
        selector:
          textRegex: ALARM
        nodeSelector:
          androidAccessibility:
            textRegex: ALARM
            resourceIdRegex: "android:id/text1"
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        val tool = tools[0].trailblazeTool as AssertVisibleBySelectorTrailblazeTool
        assertThat(tool.reason).isEqualTo("The ALARM tab should be visible.")
        assertThat(tool.selector.textRegex).isEqualTo("ALARM")
        assertThat(tool.nodeSelector).isNotNull()
        val match = tool.nodeSelector!!.driverMatch as DriverNodeMatch.AndroidAccessibility
        assertThat(match.textRegex).isEqualTo("ALARM")
        assertThat(match.resourceIdRegex).isEqualTo("android:id/text1")
      }
    }
  }
}
