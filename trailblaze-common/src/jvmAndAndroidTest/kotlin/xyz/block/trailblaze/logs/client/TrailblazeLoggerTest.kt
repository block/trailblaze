package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
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
    val call = Message.Tool.Call(
      id = "call-1",
      tool = "tap",
      content = "{\"x\":1,\"y\":2}",
      metaInfo = ResponseMetaInfo.create(kotlin.time.Clock.System),
    )
    val result = Message.Tool.Result(
      id = "call-1",
      tool = "tap",
      content = "ok",
      metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System),
      isError = false,
    )

    val rendered = with(logger) { listOf<Message>(call, result).toTrailblazeLlmMessages() }

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
