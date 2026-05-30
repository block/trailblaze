package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailblazeLoggerTest {

  private val logger = TrailblazeLogger(
    logEmitter = LogEmitter { },
    screenStateLogger = ScreenStateLogger { "" },
  )

  @Test
  fun `Tool_Call renders as tool_use and Tool_Result renders as tool_result`() {
    // Koog 1.0.0: Tool.Call / Tool.Result are now `MessagePart` parts that live inside an
    // enclosing Assistant / User message (the role-level Tool message was removed).
    val callPart = MessagePart.Tool.Call(
      id = "call-1",
      tool = "tap",
      args = "{\"x\":1,\"y\":2}",
    )
    val resultPart = MessagePart.Tool.Result(
      id = "call-1",
      tool = "tap",
      output = "ok",
      isError = false,
    )
    val callMessage = Message.Assistant(
      part = callPart,
      metaInfo = ResponseMetaInfo.create(KoogClock.System),
    )
    val resultMessage = Message.User(
      part = resultPart,
      metaInfo = RequestMetaInfo.create(KoogClock.System),
    )

    val rendered = with(logger) { listOf<Message>(callMessage, resultMessage).toTrailblazeLlmMessages() }

    assertEquals(2, rendered.size)
    assertEquals("tool_use", rendered[0].role)
    assertEquals("tool_result", rendered[1].role)
    assertEquals("tap", rendered[0].toolName)
    assertEquals("tap", rendered[1].toolName)
    assertTrue(
      "tool name should appear in rendered call payload",
      rendered[0].message!!.contains("**tap**"),
    )
    assertTrue(
      "tool result content should appear in rendered result payload",
      rendered[1].message!!.contains("ok"),
    )
  }
}
