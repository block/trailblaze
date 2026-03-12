package xyz.block.trailblaze.compose.driver

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeExecutableTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyElementVisibleTool
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Tests for element ID disambiguation in the Compose driver.
 *
 * Uses [SampleTodoApp] with `includeAmbiguousSection = true` to exercise scenarios where
 * multiple elements share the same text/role and can only be distinguished via element IDs.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeElementDisambiguationTest {

  @Test
  fun `element IDs are unique for duplicate text buttons`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val rootNode = onRoot().fetchSemanticsNode()
    val compact = ComposeSemanticTreeMapper.buildCompactElementList(rootNode)

    // Find all element IDs whose descriptors contain "Save"
    val saveEntries =
      compact.elementIdMapping.entries.filter { it.value.descriptor.contains("\"Save\"") }

    assertThat(saveEntries.size).isGreaterThan(1)

    // Each should have a unique element ID
    val ids = saveEntries.map { it.key }.toSet()
    assertThat(ids.size).isEqualTo(saveEntries.size)

    // They should have different nthIndex values or different testTags
    val first = saveEntries[0].value
    val second = saveEntries[1].value
    val differentiation =
      first.testTag != second.testTag || first.nthIndex != second.nthIndex
    assertThat(differentiation).isTrue()
  }

  @Test
  fun `click by element ID targets correct duplicate`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)

    // Find the element ID for the settings save button
    val settingsSaveEntry =
      screenState.elementIdMapping.entries.find {
        it.value.testTag == "settings_save_button"
      }
    assertThat(settingsSaveEntry).isNotNull()

    val ctx = stubContext(screenState)
    val result = runBlocking {
      ComposeClickTool(elementId = settingsSaveEntry!!.key)
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Settings should be saved, profile should not
    val settingsNodes =
      onAllNodes(hasTestTag("settings_saved_indicator")).fetchSemanticsNodes()
    assertThat(settingsNodes).isNotEmpty()

    val profileNodes =
      onAllNodes(hasTestTag("profile_saved_indicator")).fetchSemanticsNodes()
    assertThat(profileNodes.size).isEqualTo(0)
  }

  @Test
  fun `click by element ID on untagged element works`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)

    // Find element IDs for "Learn more" buttons (clickable text, no testTag)
    val learnMoreEntries =
      screenState.elementIdMapping.entries.filter {
        it.value.descriptor.contains("\"Learn more\"") && it.value.testTag == null
      }
    assertThat(learnMoreEntries.size).isGreaterThan(1)

    // Click the second "Learn more" (nthIndex=1)
    val secondLearnMore = learnMoreEntries.find { it.value.nthIndex == 1 }
    assertThat(secondLearnMore).isNotNull()

    val ctx = stubContext(screenState)
    val result = runBlocking {
      ComposeClickTool(elementId = secondLearnMore!!.key)
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Second "Learn more" indicator should appear
    val indicator2 =
      onAllNodes(hasTestTag("learn_more_2_indicator")).fetchSemanticsNodes()
    assertThat(indicator2).isNotEmpty()
  }

  @Test
  fun `type by element ID into correct duplicate text field`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)

    // Find the element ID for the settings field
    val settingsFieldEntry =
      screenState.elementIdMapping.entries.find { it.value.testTag == "settings_field" }
    assertThat(settingsFieldEntry).isNotNull()

    val ctx = stubContext(screenState)
    val result = runBlocking {
      ComposeTypeTool(text = "dark-mode=true", elementId = settingsFieldEntry!!.key)
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `element IDs are stable within a single snapshot`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)

    // Resolve a few IDs and verify they return valid refs
    val mapping = screenState.elementIdMapping
    assertThat(mapping).isNotEmpty()

    for ((id, ref) in mapping) {
      assertThat(id).isNotEmpty()
      assertThat(ref.descriptor).isNotEmpty()
      assertThat(ref.nthIndex).isNotNull()
      // Verify resolveElementId returns the same ref
      val resolved = screenState.resolveElementId(id)
      assertThat(resolved).isEqualTo(ref)
      // Also verify bracket format
      val resolvedBracket = screenState.resolveElementId("[$id]")
      assertThat(resolvedBracket).isEqualTo(ref)
    }
  }

  @Test
  fun `compact element list filters structural noise`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val rootNode = onRoot().fetchSemanticsNode()
    val compact = ComposeSemanticTreeMapper.buildCompactElementList(rootNode)

    // Should not contain "View" (the old catch-all className for structural wrappers)
    assertThat(compact.text).doesNotContain("- View")
    // Should contain element IDs
    assertThat(compact.text).contains("[e1]")
    // Should contain interactive roles
    assertThat(compact.text).contains("button")
    assertThat(compact.text).contains("textbox")
  }

  @Test
  fun `richer role inference maps correctly`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    // Add a todo item so a checkbox appears
    onNodeWithTag("todo_input").performTextInput("Test item")
    onNodeWithTag("add_button").performClick()
    waitForIdle()

    val rootNode = onRoot().fetchSemanticsNode()
    val compact = ComposeSemanticTreeMapper.buildCompactElementList(rootNode)

    // Verify role inference in the text snapshot
    assertThat(compact.text).contains("button \"Add\"")
    assertThat(compact.text).contains("textbox")
    assertThat(compact.text).contains("checkbox")
    assertThat(compact.text).contains("button \"Save\"")
    // "Learn more" clickable text should be inferred as button
    assertThat(compact.text).contains("button \"Learn more\"")
  }

  @Test
  fun `nth-index disambiguation for same descriptor`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val rootNode = onRoot().fetchSemanticsNode()
    val compact = ComposeSemanticTreeMapper.buildCompactElementList(rootNode)

    // Find all "Learn more" buttons (untagged, same descriptor)
    val learnMoreEntries =
      compact.elementIdMapping.entries.filter {
        it.value.descriptor.contains("\"Learn more\"") && it.value.testTag == null
      }
    assertThat(learnMoreEntries.size).isGreaterThan(1)

    val nthIndices = learnMoreEntries.map { it.value.nthIndex }.sorted()
    assertThat(nthIndices[0]).isEqualTo(0)
    assertThat(nthIndices[1]).isEqualTo(1)

    // They should have different element IDs
    val ids = learnMoreEntries.map { it.key }
    assertThat(ids[0]).isNotEqualTo(ids[1])
  }

  @Test
  fun `invalid element ID falls back to testTag`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    val ctx = stubContext(screenState)

    // Use a non-existent element ID but provide a valid testTag fallback
    val result = runBlocking {
      ComposeClickTool(elementId = "e999", testTag = "profile_save_button")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Profile should be saved (testTag fallback worked)
    val profileNodes =
      onAllNodes(hasTestTag("profile_saved_indicator")).fetchSemanticsNodes()
    assertThat(profileNodes).isNotEmpty()
  }

  @Test
  fun `invalid element ID with no fallback returns error`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    val ctx = stubContext(screenState)

    // Non-existent element ID and no testTag/text fallback
    val result = runBlocking {
      ComposeClickTool(elementId = "e999")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `verify element visible by element ID`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)

    // Find the element ID for the add button
    val addButtonEntry =
      screenState.elementIdMapping.entries.find { it.value.testTag == "add_button" }
    assertThat(addButtonEntry).isNotNull()

    val ctx = stubContext(screenState)
    val result = runBlocking {
      ComposeVerifyElementVisibleTool(elementId = addButtonEntry!!.key)
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  // -- resolveElement and buildMatcherFromDescriptor tests --

  @Test
  fun `resolveElement returns matcher from elementId`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    val addButtonEntry =
      screenState.elementIdMapping.entries.find { it.value.testTag == "add_button" }
    assertThat(addButtonEntry).isNotNull()

    val ctx = stubContext(screenState)
    val matcher =
      ComposeExecutableTool.resolveElement(
        elementId = addButtonEntry!!.key,
        testTag = null,
        text = null,
        context = ctx,
      )
    assertThat(matcher).isNotNull()

    // Matcher should find at least one node
    val nodes = onAllNodes(matcher!!).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
  }

  @Test
  fun `resolveElement falls back to text when elementId is null`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val ctx = stubContext()
    val matcher =
      ComposeExecutableTool.resolveElement(
        elementId = null,
        testTag = null,
        text = "0 items",
        context = ctx,
      )
    assertThat(matcher).isNotNull()
    val nodes = onAllNodes(matcher!!).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
  }

  @Test
  fun `resolveElement returns null when no identifiers provided`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val ctx = stubContext()
    val matcher =
      ComposeExecutableTool.resolveElement(
        elementId = null,
        testTag = null,
        text = null,
        context = ctx,
      )
    assertThat(matcher).isNull()
  }

  @Test
  fun `resolveElement with elementId resolves through descriptor`() = runComposeUiTest {
    setContent { SampleTodoApp(includeAmbiguousSection = true) }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    // Find a "Save" button element ID (resolves through buildMatcherFromDescriptor internally)
    val saveEntry =
      screenState.elementIdMapping.entries.find {
        it.value.descriptor.contains("\"Save\"")
      }
    assertThat(saveEntry).isNotNull()

    val ctx = stubContext(screenState)
    val matcher =
      ComposeExecutableTool.resolveElement(
        elementId = saveEntry!!.key,
        testTag = null,
        text = null,
        context = ctx,
      )
    assertThat(matcher).isNotNull()
    val nodes = onAllNodes(matcher!!).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
  }

  companion object {
    private fun stubContext(
      screenState: ComposeScreenState? = null,
    ): TrailblazeToolExecutionContext {
      return TrailblazeToolExecutionContext(
        screenState = screenState,
        traceId = null,
        trailblazeDeviceInfo =
          TrailblazeDeviceInfo(
            trailblazeDeviceId =
              TrailblazeDeviceId(
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
        screenStateProvider = null,
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        memory = AgentMemory(),
      )
    }
  }
}
