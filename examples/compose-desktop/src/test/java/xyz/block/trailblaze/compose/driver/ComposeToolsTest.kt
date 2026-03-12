package xyz.block.trailblaze.compose.driver

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyElementVisibleTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyTextVisibleTool
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
 * Tests exercising the compose-driver tools against a real Compose UI (SampleTodoApp).
 *
 * Each test renders the Todo app via [runComposeUiTest], then exercises the tools directly — no LLM
 * needed. This validates that semantics tree mapping, click/type/verify tools, and screenshot
 * capture all work correctly.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeToolsTest {

  // -- Semantic Tree Mapper Tests --

  @Test
  fun `map produces valid ViewHierarchyTreeNode tree`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val rootSemanticsNode = onRoot().fetchSemanticsNode()
    val tree = ComposeSemanticTreeMapper.map(rootSemanticsNode)

    val allNodes = tree.aggregate()

    // Should find our tagged elements in the tree
    val addButton = allNodes.find { it.resourceId == "add_button" }
    assertThat(addButton).isNotNull()
    assertThat(addButton!!.clickable).isTrue()

    val todoInput = allNodes.find { it.resourceId == "todo_input" }
    assertThat(todoInput).isNotNull()

    val itemCount = allNodes.find { it.resourceId == "item_count" }
    assertThat(itemCount).isNotNull()
    assertThat(itemCount!!.text).isNotNull()
    assertThat(itemCount.text!!).contains("0 items")
  }

  @Test
  fun `toTextSnapshot produces readable text output`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val rootSemanticsNode = onRoot().fetchSemanticsNode()
    val snapshot = ComposeSemanticTreeMapper.toTextSnapshot(rootSemanticsNode)

    assertThat(snapshot).isNotEmpty()
    assertThat(snapshot).contains("[testTag=add_button]")
    assertThat(snapshot).contains("[testTag=todo_input]")
    assertThat(snapshot).contains("[testTag=item_count]")
    assertThat(snapshot).contains("0 items")
  }

  @Test
  fun `checked field reflects actual ToggleableState not just presence`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    // Add a todo item so a checkbox appears
    onNodeWithTag("todo_input").performTextInput("Test item")
    onNodeWithTag("add_button").performClick()
    waitForIdle()

    // Checkbox should be unchecked initially
    val rootNode = onRoot().fetchSemanticsNode()
    val tree = ComposeSemanticTreeMapper.map(rootNode)
    val allNodes = tree.aggregate()
    val checkbox = allNodes.find { it.resourceId == "todo_checkbox_0" }
    assertThat(checkbox).isNotNull()
    assertThat(checkbox!!.checked).isFalse()

    // Click the checkbox to check it
    onNodeWithTag("todo_checkbox_0").performClick()
    waitForIdle()

    // Now checked should be true
    val updatedTree = ComposeSemanticTreeMapper.map(onRoot().fetchSemanticsNode())
    val updatedCheckbox = updatedTree.aggregate().find { it.resourceId == "todo_checkbox_0" }
    assertThat(updatedCheckbox).isNotNull()
    assertThat(updatedCheckbox!!.checked).isTrue()
  }

  // -- Screen State Tests --

  @Test
  fun `ComposeScreenState captures screenshot bytes`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    val bytes = screenState.screenshotBytes

    assertThat(bytes).isNotNull()
    assertThat(bytes!!.size).isGreaterThan(0)
  }

  @Test
  fun `ComposeScreenState exposes viewHierarchyTextRepresentation`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    val textRep = screenState.viewHierarchyTextRepresentation

    assertThat(textRep).isNotNull()
    assertThat(textRep!!).isNotEmpty()
    assertThat(textRep).contains("[testTag=add_button]")
    assertThat(textRep).contains("[testTag=todo_input]")
    assertThat(textRep).contains(screenState.semanticsTreeText)
  }

  @Test
  fun `ComposeScreenState builds view hierarchy`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val screenState = ComposeScreenState(this, 1280, 800)
    val tree = screenState.viewHierarchy

    val allNodes = tree.aggregate()
    val addButton = allNodes.find { it.resourceId == "add_button" }
    assertThat(addButton).isNotNull()
  }

  // -- Click Tool Test --

  @Test
  fun `click tool clicks the add button`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    // Type text first so the add button has something to add
    onNodeWithTag("todo_input").performTextInput("Test item")
    waitForIdle()

    val clickTool = ComposeClickTool(testTag = "add_button")
    val result = runBlocking {
      clickTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Verify the item was added — counter should now show "1 items"
    val nodes = onAllNodes(hasText("1 items", substring = false)).fetchSemanticsNodes()
    assertThat(nodes).isNotEmpty()
  }

  // -- Type Tool Test --

  @Test
  fun `type tool types text into input field`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val typeTool = ComposeTypeTool(
      text = "Buy groceries",
      testTag = "todo_input",
    )
    val result = runBlocking {
      typeTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // Verify the typed text appears in the input
    val inputNodes = onAllNodes(hasText("Buy groceries")).fetchSemanticsNodes()
    assertThat(inputNodes).isNotEmpty()
  }

  @Test
  fun `type tool clearFirst replaces existing text`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    // First type some text
    onNodeWithTag("todo_input").performTextInput("old text")
    waitForIdle()

    // Now type with clearFirst=true (default) — should replace
    val typeTool = ComposeTypeTool(
      text = "new text",
      testTag = "todo_input",
      clearFirst = true,
    )
    val result = runBlocking {
      typeTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)

    // "new text" should be present, "old text" should not
    val newNodes = onAllNodes(hasText("new text")).fetchSemanticsNodes()
    assertThat(newNodes).isNotEmpty()
    val oldNodes = onAllNodes(hasText("old text")).fetchSemanticsNodes()
    assertThat(oldNodes.size).isEqualTo(0)
  }

  // -- Verify Text Visible Tool Tests --

  @Test
  fun `verify text visible passes for existing text`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val verifyTool = ComposeVerifyTextVisibleTool(text = "0 items")
    val result = runBlocking {
      verifyTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `verify text visible fails for missing text`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val verifyTool = ComposeVerifyTextVisibleTool(text = "this text does not exist")
    val result = runBlocking {
      verifyTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  @Test
  fun `verify text updates after adding a todo`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    // Initially "0 items"
    val before = runBlocking {
      ComposeVerifyTextVisibleTool(text = "0 items")
        .executeWithCompose(this@runComposeUiTest, stubContext())
    }
    assertThat(before).isInstanceOf(TrailblazeToolResult.Success::class)

    // Add a todo
    onNodeWithTag("todo_input").performTextInput("Walk the dog")
    onNodeWithTag("add_button").performClick()
    waitForIdle()

    // Now "1 items"
    val after = runBlocking {
      ComposeVerifyTextVisibleTool(text = "1 items")
        .executeWithCompose(this@runComposeUiTest, stubContext())
    }
    assertThat(after).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  // -- Verify Element Visible Tool Tests --

  @Test
  fun `verify element visible passes for existing element`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val verifyTool = ComposeVerifyElementVisibleTool(testTag = "add_button")
    val result = runBlocking {
      verifyTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `verify element visible fails for missing element`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val verifyTool = ComposeVerifyElementVisibleTool(testTag = "nonexistent_tag")
    val result = runBlocking {
      verifyTool.executeWithCompose(this@runComposeUiTest, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
  }

  // -- End-to-End Flow Test --

  @Test
  fun `end-to-end add and verify todo flow`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()
    val ctx = stubContext()

    // Type a todo
    val typeResult = runBlocking {
      ComposeTypeTool(text = "Learn Compose", testTag = "todo_input")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(typeResult).isInstanceOf(TrailblazeToolResult.Success::class)

    // Click add
    val clickResult = runBlocking {
      ComposeClickTool(testTag = "add_button")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(clickResult).isInstanceOf(TrailblazeToolResult.Success::class)

    // Verify the todo item is visible
    val verifyItem = runBlocking {
      ComposeVerifyTextVisibleTool(text = "Learn Compose")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(verifyItem).isInstanceOf(TrailblazeToolResult.Success::class)

    // Verify counter updated
    val verifyCount = runBlocking {
      ComposeVerifyTextVisibleTool(text = "1 items")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(verifyCount).isInstanceOf(TrailblazeToolResult.Success::class)

    // Verify the todo item element exists by testTag
    val verifyElement = runBlocking {
      ComposeVerifyElementVisibleTool(testTag = "todo_item_0")
        .executeWithCompose(this@runComposeUiTest, ctx)
    }
    assertThat(verifyElement).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  companion object {
    /** Creates a minimal [TrailblazeToolExecutionContext] for tool execution in tests. */
    private fun stubContext(): TrailblazeToolExecutionContext {
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
        trailblazeLogger = TrailblazeLogger.createNoOp(),
        memory = AgentMemory(),
      )
    }
  }
}
