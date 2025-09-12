package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test
import xyz.block.trailblaze.TrailSerializerTest.TotallyCustomTool
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode
import xyz.block.trailblaze.toolcalls.commands.LongPressElementWithAccessibilityTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LongPressOnElementWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressBackTrailblazeTool
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
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml

class ToolSerializationTest {
  private val trailblazeYaml = TrailblazeYaml(setOf(TotallyCustomTool::class))

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
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = EraseTextTrailblazeTool(
            charactersToErase = null,
          ),
        )
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
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = EraseTextTrailblazeTool(
            charactersToErase = 10,
          ),
        )
      }
    }
  }

  @Test
  fun deserializePressBackTool() {
    val yaml = """
- tools:
    - pressBack: {}
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "inputText",
          trailblazeTool = PressBackTrailblazeTool,
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
            direction = "UP",
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
            direction = "DOWN",
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
            direction = "LEFT",
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
            direction = "RIGHT",
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
            direction = "UP",
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
            direction = "DOWN",
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
            direction = "LEFT",
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
            direction = "RIGHT",
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
        appId: com.squareup.cash.beta.debug
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as TrailYamlItem.ToolTrailItem) {
        assertThat(tools.size).isEqualTo(1)
        TrailblazeToolYamlWrapper(
          name = "wait",
          trailblazeTool = LaunchAppTrailblazeTool(
            appId = "com.squareup.cash.beta.debug",
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
        appId: com.squareup.cash.beta.debug
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
            appId = "com.squareup.cash.beta.debug",
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
        appId: com.squareup.cash.beta.debug
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
            appId = "com.squareup.cash.beta.debug",
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
        appId: com.squareup.cash.beta.debug
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
            appId = "com.squareup.cash.beta.debug",
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
}
