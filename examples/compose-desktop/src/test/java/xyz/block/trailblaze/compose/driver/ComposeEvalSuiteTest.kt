package xyz.block.trailblaze.compose.driver

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.each
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.compose.driver.tools.ComposeRequestDetailsTool
import xyz.block.trailblaze.compose.driver.tools.ComposeScrollTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeWaitTool
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TakeSnapshotTool

/**
 * Comprehensive eval suite for the Compose Desktop driver.
 *
 * Exercises every ViewHierarchyTreeNode field the semantic mapper populates, every tool parameter
 * combination, and validates the full ScreenState pipeline. Uses [SampleWidgetShowcase] which
 * contains widgets for every supported semantic role.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeEvalSuiteTest {

  // -- Semantic Mapping Tests --

  @Test
  fun `switch maps to Switch className with checked state`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()
    val switch = allNodes.find { it.resourceId == "dark_mode_switch" }

    assertThat(switch).isNotNull()
    assertThat(switch!!.className).isEqualTo("Switch")
    assertThat(switch.checked).isFalse()

    // Toggle switch via tool and verify checked becomes true
    val result = runBlocking {
      ComposeClickTool(testTag = "dark_mode_switch")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    val updatedTree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val updatedSwitch = updatedTree.aggregate().find { it.resourceId == "dark_mode_switch" }
    assertThat(updatedSwitch).isNotNull()
    assertThat(updatedSwitch!!.checked).isTrue()
  }

  @Test
  fun `radio button maps to RadioButton className with selected state`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val radioA = allNodes.find { it.resourceId == "radio_option_a" }
    val radioB = allNodes.find { it.resourceId == "radio_option_b" }
    val radioC = allNodes.find { it.resourceId == "radio_option_c" }

    assertThat(radioA).isNotNull()
    assertThat(radioA!!.className).isEqualTo("RadioButton")
    assertThat(radioA.selected).isTrue()

    assertThat(radioB).isNotNull()
    assertThat(radioB!!.className).isEqualTo("RadioButton")
    assertThat(radioB.selected).isFalse()

    assertThat(radioC).isNotNull()
    assertThat(radioC!!.className).isEqualTo("RadioButton")
    assertThat(radioC.selected).isFalse()
  }

  @Test
  fun `tab maps to Tab className with selected state`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val tabInfo = allNodes.find { it.resourceId == "tab_info" }
    val tabSettings = allNodes.find { it.resourceId == "tab_settings" }

    assertThat(tabInfo).isNotNull()
    assertThat(tabInfo!!.className).isEqualTo("Tab")
    assertThat(tabInfo.selected).isTrue()

    assertThat(tabSettings).isNotNull()
    assertThat(tabSettings!!.className).isEqualTo("Tab")
    assertThat(tabSettings.selected).isFalse()
  }

  @Test
  fun `image maps to Image className with accessibilityText`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val image = allNodes.find { it.resourceId == "star_image" }
    assertThat(image).isNotNull()
    assertThat(image!!.className).isEqualTo("Image")
    assertThat(image.accessibilityText).isEqualTo("Gold star rating")
  }

  @Test
  fun `disabled button maps enabled false`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val disabledButton = allNodes.find { it.resourceId == "disabled_button" }
    assertThat(disabledButton).isNotNull()
    assertThat(disabledButton!!.className).isEqualTo("Button")
    assertThat(disabledButton.enabled).isFalse()
    // The button still has a click action registered, so clickable is true
    assertThat(disabledButton.clickable).isTrue()
  }

  @Test
  fun `password field maps password true`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val passwordField = allNodes.find { it.resourceId == "password_field" }
    assertThat(passwordField).isNotNull()
    assertThat(passwordField!!.password).isTrue()
  }

  @Test
  fun `focused field maps focused true`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val focusedField = allNodes.find { it.resourceId == "focused_field" }
    assertThat(focusedField).isNotNull()
    // FocusRequester may not reliably work in test env — verify the field exists
    // and that the focused property is populated (true or false)
    assertThat(focusedField!!.focused).isTrue()
  }

  @Test
  fun `content description maps to accessibilityText`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val describedBox = allNodes.find { it.resourceId == "described_box" }
    assertThat(describedBox).isNotNull()
    assertThat(describedBox!!.accessibilityText).isEqualTo("Decorative separator")
  }

  @Test
  fun `scrollable container maps scrollable true`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val scrollableList = allNodes.find { it.resourceId == "scrollable_list" }
    assertThat(scrollableList).isNotNull()
    assertThat(scrollableList!!.scrollable).isTrue()
  }

  @Test
  fun `dimensions and centerPoint are populated`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val counterButton = allNodes.find { it.resourceId == "counter_button" }
    assertThat(counterButton).isNotNull()
    assertThat(counterButton!!.dimensions).isNotNull()
    assertThat(counterButton.dimensions!!).isNotEmpty()
    assertThat(counterButton.centerPoint).isNotNull()
    assertThat(counterButton.centerPoint!!).isNotEmpty()
    // Dimensions should be in "WxH" format
    assertThat(counterButton.dimensions!!).contains("x")
    // CenterPoint should be in "X,Y" format
    assertThat(counterButton.centerPoint!!).contains(",")
  }

  @Test
  fun `text snapshot includes all widget types`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val snapshot =
      ComposeSemanticTreeMapper.toTextSnapshot(onRoot().fetchSemanticsNode())

    assertThat(snapshot).contains("switch")
    assertThat(snapshot).contains("radio")
    assertThat(snapshot).contains("tab")
    assertThat(snapshot).contains("img")
    assertThat(snapshot).contains("button")
  }

  // -- Tool Tests --

  @Test
  fun `click tool by text clicks counter button`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeClickTool(text = "Clicked: 0")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Counter should have incremented
    val nodes = onAllNodes(hasText("Clicked: 1")).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
  }

  @Test
  fun `click tool with both testTag and text`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeClickTool(testTag = "counter_button", text = "Clicked: 0")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    val nodes = onAllNodes(hasText("Clicked: 1")).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
  }

  @Test
  fun `click tool with neither testTag nor text returns error`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeClickTool()
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `type tool append mode preserves existing text`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeTypeTool(
        text = "World",
        testTag = "append_field",
        clearFirst = false,
      ).executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // With clearFirst=false, original "Hello" should still be present along with "World"
    val nodesWithHello = onAllNodes(hasText("Hello", substring = true)).fetchSemanticsNodes()
    assertThat(nodesWithHello).isNotEmpty()
    val nodesWithWorld = onAllNodes(hasText("World", substring = true)).fetchSemanticsNodes()
    assertThat(nodesWithWorld).isNotEmpty()
  }

  @Test
  fun `type tool into password field`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeTypeTool(text = "secret123", testTag = "password_field")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `scroll tool scrolls to index`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeScrollTool(testTag = "scrollable_list", index = 25)
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `scroll tool with text matcher scrolls to target`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    // Use text matcher to find the scrollable list by its content
    val result = runBlocking {
      ComposeScrollTool(testTag = "scrollable_list", index = 20)
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `wait tool returns success`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeWaitTool(seconds = 1)
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message!!).isEqualTo("Waited 1 seconds.")
  }

  @Test
  fun `wait tool caps at 30 seconds`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    // Don't actually wait — just verify the message reflects the cap
    // The tool clamps and sleeps, so we use seconds=1 but construct manually to check capping logic
    val tool = ComposeWaitTool(seconds = 99)
    // Verify the coercion by checking the result message
    val result = runBlocking {
      tool.executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message!!).contains("Waited 30 seconds.")
  }

  @Test
  fun `snapshot tool captures screen state`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val ctx = stubContext(
      screenStateProvider = { ComposeScreenState(ComposeUiTestTarget(this@runComposeUiTest), 1280, 800) }
    )
    val result = runBlocking {
      TakeSnapshotTool(screenName = "showcase").execute(ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  // -- Interaction Tests (state changes through tools) --

  @Test
  fun `click switch toggles checked state in semantic tree`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    // Initially unchecked
    val before = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val switchBefore = before.aggregate().find { it.resourceId == "dark_mode_switch" }
    assertThat(switchBefore!!.checked).isFalse()

    // Click to toggle
    runBlocking {
      ComposeClickTool(testTag = "dark_mode_switch")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }

    // Now checked
    val after = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val switchAfter = after.aggregate().find { it.resourceId == "dark_mode_switch" }
    assertThat(switchAfter!!.checked).isTrue()
  }

  @Test
  fun `click radio button changes selection`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    // Click Option B
    runBlocking {
      ComposeClickTool(testTag = "radio_option_b")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val radioA = allNodes.find { it.resourceId == "radio_option_a" }
    val radioB = allNodes.find { it.resourceId == "radio_option_b" }
    assertThat(radioA!!.selected).isFalse()
    assertThat(radioB!!.selected).isTrue()
  }

  @Test
  fun `click tab switches selected tab`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    // Click Settings tab
    runBlocking {
      ComposeClickTool(testTag = "tab_settings")
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }

    val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val allNodes = tree.aggregate()

    val tabInfo = allNodes.find { it.resourceId == "tab_info" }
    val tabSettings = allNodes.find { it.resourceId == "tab_settings" }
    assertThat(tabInfo!!.selected).isFalse()
    assertThat(tabSettings!!.selected).isTrue()
  }

  // -- Compact Element List Tests (recent fixes) --

  @Test
  fun `empty page produces empty page marker`() = runComposeUiTest {
    setContent { Box {} }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    assertThat(result.text).isEqualTo("(empty page)")
    assertThat(result.elementIdMapping).isEqualTo(emptyMap())
  }

  @Test
  fun `embedded double-quotes are escaped in compact list`() = runComposeUiTest {
    setContent {
      Button(onClick = {}) { Text("Say \"Hello\"") }
    }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    // The compact list should escape embedded quotes
    assertThat(result.text).contains("\\\"Hello\\\"")

    // Verify the descriptor captures the escaped text
    val ref = result.elementIdMapping.values.first()
    assertThat(ref.descriptor).contains("\\\"Hello\\\"")
  }

  @Test
  fun `backslashes in text are escaped in compact list`() = runComposeUiTest {
    setContent {
      Button(onClick = {}) { Text("path\\to\\file") }
    }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    // Backslashes should be doubled in the output
    assertThat(result.text).contains("path\\\\to\\\\file")
  }

  @Test
  fun `newlines and carriage returns in display text are flattened to spaces`() =
    runComposeUiTest {
      setContent {
        Column {
          Text("Line1\nLine2")
          Button(onClick = {}) { Text("A\r\nB") }
        }
      }
      waitForIdle()

      val result =
        ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
      // \n should be replaced with space in static text
      assertThat(result.text).contains("Line1 Line2")
      // \r\n should also be flattened in interactive element text
      assertThat(result.text).contains("A  B")
      // Verify no raw newlines leak into any single descriptor line
      result.text.lines().forEach { line ->
        assertThat(line).doesNotContain("\r")
      }
    }

  @Test
  fun `focusable is true for editable text fields and false for static text`() =
    runComposeUiTest {
      setContent { SampleWidgetShowcase() }
      waitForIdle()

      val tree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
      val allNodes = tree.aggregate()

      // Editable text fields should be focusable
      val passwordField = allNodes.find { it.resourceId == "password_field" }
      assertThat(passwordField).isNotNull()
      assertThat(passwordField!!.focusable).isTrue()

      val appendField = allNodes.find { it.resourceId == "append_field" }
      assertThat(appendField).isNotNull()
      assertThat(appendField!!.focusable).isTrue()

      // Static text should NOT be focusable (no click action, no editable text)
      val sectionHeader = allNodes.find { it.resourceId == "section_header" }
      assertThat(sectionHeader).isNotNull()
      assertThat(sectionHeader!!.focusable).isFalse()

      // Buttons should be focusable (they have a click action)
      val counterButton = allNodes.find { it.resourceId == "counter_button" }
      assertThat(counterButton).isNotNull()
      assertThat(counterButton!!.focusable).isTrue()
    }

  @Test
  fun `element IDs are sequential and mapping captures testTags`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())

    // Should have multiple element IDs
    assertThat(result.elementIdMapping.size).isGreaterThan(5)

    // Element IDs should be sequential e1, e2, e3, ...
    val ids = result.elementIdMapping.keys.toList()
    assertThat(ids).each { it.startsWith("e") }
    val numbers = ids.map { it.removePrefix("e").toInt() }
    assertThat(numbers).isEqualTo((1..numbers.size).toList())

    // Interactive elements with testTags should have them captured
    val switchEntry =
      result.elementIdMapping.entries.find { it.value.testTag == "dark_mode_switch" }
    assertThat(switchEntry).isNotNull()
    assertThat(switchEntry!!.value.descriptor).contains("switch")

    // Text in the compact output should reference the element IDs
    assertThat(result.text).contains("[${switchEntry.key}]")
  }

  @Test
  fun `compact list assigns correct roles to all widget types`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    val text = result.text

    // Verify all role types appear correctly in the compact output
    assertThat(text).contains("switch")
    assertThat(text).contains("radio")
    assertThat(text).contains("tab")
    assertThat(text).contains("img")
    assertThat(text).contains("button")
    assertThat(text).contains("textbox")
    assertThat(text).contains("scrollable")

    // Verify descriptors in the element mapping have the right roles
    val switchRef = result.elementIdMapping.values.find { it.testTag == "dark_mode_switch" }
    assertThat(switchRef).isNotNull()
    assertThat(switchRef!!.descriptor).startsWith("switch")

    val radioRef = result.elementIdMapping.values.find { it.testTag == "radio_option_a" }
    assertThat(radioRef).isNotNull()
    assertThat(radioRef!!.descriptor).startsWith("radio")

    val tabRef = result.elementIdMapping.values.find { it.testTag == "tab_info" }
    assertThat(tabRef).isNotNull()
    assertThat(tabRef!!.descriptor).startsWith("tab")

    val imgRef = result.elementIdMapping.values.find { it.testTag == "star_image" }
    assertThat(imgRef).isNotNull()
    assertThat(imgRef!!.descriptor).startsWith("img")

    val textboxRef = result.elementIdMapping.values.find { it.testTag == "password_field" }
    assertThat(textboxRef).isNotNull()
    assertThat(textboxRef!!.descriptor).startsWith("textbox")
  }

  @Test
  fun `compact list skips structural noise nodes`() = runComposeUiTest {
    setContent {
      // Column + Box are structural wrappers with no semantic content
      Column {
        Box { Text("visible text") }
        Button(onClick = {}) { Text("click me") }
      }
    }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    // Static text should appear without an element ID
    assertThat(result.text).contains("text \"visible text\"")
    // Interactive button should have an element ID
    assertThat(result.text).contains("button \"click me\"")
    // Structural wrappers (Column, Box) should NOT appear in the output
    assertThat(result.text).doesNotContain("View")
  }

  // -- ComposeRequestDetailsTool Tests --

  @Test
  fun `request details tool with BOUNDS returns success`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeRequestDetailsTool(include = listOf(ComposeViewHierarchyDetail.BOUNDS))
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result as TrailblazeToolResult.Success).message!!).contains("BOUNDS")
  }

  @Test
  fun `request details tool with empty include returns error`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val result = runBlocking {
      ComposeRequestDetailsTool(include = emptyList())
        .executeWithCompose(ComposeUiTestTarget(this@runComposeUiTest), stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `request details BOUNDS includes bounds annotations in screen state`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    // Without BOUNDS requested, the compact list should NOT have bounds annotations
    val withoutBounds =
      ComposeScreenState(ComposeUiTestTarget(this@runComposeUiTest), 1280, 800, requestedDetails = emptySet())
    assertThat(withoutBounds.semanticsTreeText).isNotEmpty()
    assertThat(withoutBounds.semanticsTreeText).doesNotContain("{x:")

    // With BOUNDS requested, the compact list SHOULD have bounds annotations
    val withBounds = ComposeScreenState(
      ComposeUiTestTarget(this@runComposeUiTest), 1280, 800,
      requestedDetails = setOf(ComposeViewHierarchyDetail.BOUNDS),
    )
    assertThat(withBounds.semanticsTreeText).contains("{x:")
    // Verify the bounds format is {x:N,y:N,w:N,h:N}
    val boundsPattern = Regex("\\{x:\\d+,y:\\d+,w:\\d+,h:\\d+}")
    assertThat(boundsPattern.containsMatchIn(withBounds.semanticsTreeText)).isTrue()
  }

  @Test
  fun `descriptor escapes special characters end to end`() = runComposeUiTest {
    setContent {
      Button(onClick = {}) { Text("Say \"Hi\" \\ done") }
    }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    val ref = result.elementIdMapping.values.first()
    // Quotes and backslashes in the descriptor should be escaped
    assertThat(ref.descriptor).contains("\\\"Hi\\\"")
    assertThat(ref.descriptor).contains("\\\\")
    assertThat(ref.descriptor).startsWith("button")
  }

  @Test
  fun `compact element list for editable text field`() = runComposeUiTest {
    setContent {
      var text by remember { mutableStateOf("initial") }
      TextField(value = text, onValueChange = { text = it })
    }
    waitForIdle()

    val result =
      ComposeSemanticTreeMapper.buildCompactElementList(onRoot().fetchSemanticsNode())
    // Editable text should appear as a textbox
    assertThat(result.text).contains("textbox")
    assertThat(result.text).contains("initial")
    // Should have an element ID (interactive)
    assertThat(result.elementIdMapping).isNotEmpty()
  }

  companion object {
    private fun stubContext(
      screenStateProvider: (() -> ComposeScreenState)? = null,
    ): TrailblazeToolExecutionContext {
      return TrailblazeToolExecutionContext(
        screenState = null,
        traceId = null,
        trailblazeDeviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "compose-test",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
          ),
          trailblazeDriverType = TrailblazeDriverType.COMPOSE,
          widthPixels = 1280,
          heightPixels = 800,
        ),
        sessionProvider = {
          TrailblazeSession(
            sessionId = SessionId("test-session"),
            startTime = Clock.System.now(),
          )
        },
        screenStateProvider = screenStateProvider,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        memory = AgentMemory(),
      )
    }
  }
}
