package xyz.block.trailblaze.agent.model

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.DirectionStep

class PromptStepStatusTest {

  private val mockScreenState =
    object : ScreenState {
      override val screenshotBytes: ByteArray? = null
      override val deviceWidth: Int = 1080
      override val deviceHeight: Int = 1920
      override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
      override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
        TrailblazeDevicePlatform.ANDROID
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }

  private fun createStatus(
    maxHistorySize: Int = PromptStepStatus.DEFAULT_MAX_HISTORY_SIZE,
    prePopulated: MutableList<Message> = mutableListOf(),
  ) =
    PromptStepStatus(
      promptStep = DirectionStep(step = "test prompt"),
      screenStateProvider = { mockScreenState },
      maxHistorySize = maxHistorySize,
      koogLlmResponseHistory = prePopulated,
    )

  private fun userMessage(content: String) =
    Message.User(
      content = content,
      metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System),
    )

  @Test
  fun `history is capped at maxHistorySize`() {
    val status = createStatus(maxHistorySize = 3)

    // Add 5 tool calls — only the last 3 messages should be retained
    repeat(5) { i ->
      status.addCompletedToolCallToChatHistory(
        commandResult = TrailblazeToolResult.Success("result $i"),
        llmResponseContent = null,
        toolName = "tool",
        toolArgs = kotlinx.serialization.json.buildJsonObject {},
      )
    }

    assertEquals(3, status.getLimitedHistory().size)
    // The retained messages should be the last 3 added (indices 2, 3, 4)
    val oldestRetained = (status.getLimitedHistory()[0] as Message.User).content
    assert(oldestRetained.contains("result 2")) {
      "Expected oldest retained message to contain 'result 2', but was: $oldestRetained"
    }
  }

  @Test
  fun `callCount reflects total messages added, not retained`() {
    val status = createStatus(maxHistorySize = 2)

    repeat(10) {
      status.addCompletedToolCallToChatHistory(
        commandResult = TrailblazeToolResult.Success("ok"),
        llmResponseContent = null,
        toolName = "tool",
        toolArgs = kotlinx.serialization.json.buildJsonObject {},
      )
    }

    // Only 2 retained, but callCount should reflect all 10
    assertEquals(2, status.getLimitedHistory().size)
    status.markAsComplete()
    val completedStatus = status.currentStatus.value as AgentTaskStatus.Success.ObjectiveComplete
    assertEquals(10, completedStatus.statusData.callCount)
  }

  @Test
  fun `pre-populated history is trimmed on construction`() {
    val messages = (1..10).map { userMessage("msg $it") as Message }.toMutableList()
    val status = createStatus(maxHistorySize = 3, prePopulated = messages)

    // Should be trimmed to 3, keeping the last 3
    assertEquals(3, status.getLimitedHistory().size)
    assertEquals("msg 10", (status.getLimitedHistory().last() as Message.User).content)
  }

  @Test
  fun `pre-populated history count is preserved in callCount`() {
    val messages = (1..8).map { userMessage("msg $it") as Message }.toMutableList()
    val status = createStatus(maxHistorySize = 3, prePopulated = messages)

    // Add 2 more
    repeat(2) {
      status.addCompletedToolCallToChatHistory(
        commandResult = TrailblazeToolResult.Success("new"),
        llmResponseContent = null,
        toolName = "tool",
        toolArgs = kotlinx.serialization.json.buildJsonObject {},
      )
    }

    status.markAsComplete()
    val completedStatus = status.currentStatus.value as AgentTaskStatus.Success.ObjectiveComplete
    // 8 pre-populated + 2 added = 10 total
    assertEquals(10, completedStatus.statusData.callCount)
  }

  @Test
  fun `maxHistorySize must be positive`() {
    assertFailsWith<IllegalArgumentException> { createStatus(maxHistorySize = 0) }
    assertFailsWith<IllegalArgumentException> { createStatus(maxHistorySize = -1) }
  }
}
