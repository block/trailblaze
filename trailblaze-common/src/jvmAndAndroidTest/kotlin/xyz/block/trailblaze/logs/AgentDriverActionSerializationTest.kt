package xyz.block.trailblaze.logs

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.junit.Test
import xyz.block.trailblaze.api.AgentActionType
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for [AgentDriverAction] and [TrailblazeLog.AgentDriverLog] serialization.
 */
class AgentDriverActionSerializationTest {

  private val json = TrailblazeJson.createTrailblazeJsonInstance(emptyMap())

  // --- AgentDriverAction round-trip tests ---

  @Test
  fun `TapPoint serializes and deserializes`() {
    val action = AgentDriverAction.TapPoint(x = 100, y = 200)
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.TapPoint>(deserialized)
    assertEquals(100, deserialized.x)
    assertEquals(200, deserialized.y)
    assertEquals(AgentActionType.TAP_POINT, deserialized.type)
  }

  @Test
  fun `AssertCondition serializes and deserializes`() {
    val action = AgentDriverAction.AssertCondition(
      conditionDescription = "text visible",
      x = 50, y = 75,
      isVisible = true,
      textToDisplay = "Hello",
      succeeded = false,
    )
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.AssertCondition>(deserialized)
    assertEquals("text visible", deserialized.conditionDescription)
    assertEquals(50, deserialized.x)
    assertEquals(75, deserialized.y)
    assertEquals(true, deserialized.isVisible)
    assertEquals("Hello", deserialized.textToDisplay)
    assertEquals(false, deserialized.succeeded)
  }

  @Test
  fun `Swipe serializes and deserializes with coordinates`() {
    val action = AgentDriverAction.Swipe(
      direction = "UP",
      durationMs = 400,
      startX = 100, startY = 500,
      endX = 100, endY = 100,
    )
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.Swipe>(deserialized)
    assertEquals("UP", deserialized.direction)
    assertEquals(400L, deserialized.durationMs)
    assertEquals(100, deserialized.startX)
    assertEquals(500, deserialized.startY)
  }

  // --- New subtype tests ---

  @Test
  fun `PressHome serializes and deserializes`() {
    val action: AgentDriverAction = AgentDriverAction.PressHome
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.PressHome>(deserialized)
    assertEquals(AgentActionType.PRESS_HOME, deserialized.type)
  }

  @Test
  fun `HideKeyboard serializes and deserializes`() {
    val action: AgentDriverAction = AgentDriverAction.HideKeyboard
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.HideKeyboard>(deserialized)
    assertEquals(AgentActionType.HIDE_KEYBOARD, deserialized.type)
  }

  @Test
  fun `EraseText serializes and deserializes`() {
    val action = AgentDriverAction.EraseText(characters = 5)
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.EraseText>(deserialized)
    assertEquals(5, deserialized.characters)
    assertEquals(AgentActionType.ERASE_TEXT, deserialized.type)
  }

  @Test
  fun `Scroll serializes and deserializes`() {
    val action = AgentDriverAction.Scroll(forward = true)
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.Scroll>(deserialized)
    assertEquals(true, deserialized.forward)
    assertEquals(AgentActionType.SCROLL, deserialized.type)
  }

  @Test
  fun `WaitForSettle serializes and deserializes`() {
    val action = AgentDriverAction.WaitForSettle(timeoutMs = 3000)
    val serialized = json.encodeToString(AgentDriverAction.serializer(), action)
    val deserialized = json.decodeFromString(AgentDriverAction.serializer(), serialized)
    assertIs<AgentDriverAction.WaitForSettle>(deserialized)
    assertEquals(3000L, deserialized.timeoutMs)
    assertEquals(AgentActionType.WAIT_FOR_SETTLE, deserialized.type)
  }

  @Test
  fun `AgentDriverLog round-trip preserves all fields`() {
    val log = TrailblazeLog.AgentDriverLog(
      viewHierarchy = ViewHierarchyTreeNode(),
      screenshotFile = "test.png",
      action = AgentDriverAction.AssertCondition(
        conditionDescription = "verify button",
        x = 50, y = 100,
        isVisible = true,
        textToDisplay = "Submit",
        succeeded = true,
      ),
      durationMs = 100,
      session = SessionId("session-1"),
      timestamp = Clock.System.now(),
      deviceHeight = 800,
      deviceWidth = 1280,
    )
    val serialized = json.encodeToString(TrailblazeLog.serializer(), log)
    val deserialized = json.decodeFromString(TrailblazeLog.serializer(), serialized)
    assertIs<TrailblazeLog.AgentDriverLog>(deserialized)
    assertEquals("test.png", deserialized.screenshotFile)
    assertEquals(1280, deserialized.deviceWidth)

    val action = deserialized.action
    assertIs<AgentDriverAction.AssertCondition>(action)
    assertEquals("verify button", action.conditionDescription)
    assertEquals("Submit", action.textToDisplay)
    assertEquals(true, action.succeeded)
  }

}
