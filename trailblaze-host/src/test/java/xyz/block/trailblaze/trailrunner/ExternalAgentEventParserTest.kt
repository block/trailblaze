package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExternalAgentEventParserTest {
  @Test
  fun claudeToolUseBecomesToolCallEvent() {
    val events = parseExternalAgentLine(
      ExternalAgentType.CLAUDE,
      """
      {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"pwd","description":"show cwd"}}]}}
      """.trimIndent(),
    )

    val event = events.single()
    assertEquals(ExternalAgentEventKind.TOOL_CALL, event.kind)
    assertEquals("Bash", event.toolName)
    assertEquals("toolu_1", event.toolCallId)
    assertTrue(event.input.toString().contains("\"command\":\"pwd\""))
  }

  @Test
  fun claudeTextCanCarryTrailrunnerUiCommand() {
    val events = parseExternalAgentLine(
      ExternalAgentType.CLAUDE,
      """
      {"type":"assistant","message":{"content":[{"type":"text","text":"Opening the active run.\nTRAILRUNNER_UI {\"version\":1,\"action\":\"open_session\",\"sessionId\":\"session-1\",\"params\":{\"view\":\"active\"}}\nReady."}]}}
      """.trimIndent(),
    )

    assertEquals(
      listOf(ExternalAgentEventKind.ASSISTANT_MESSAGE, ExternalAgentEventKind.UI_COMMAND),
      events.map { it.kind },
    )
    val command = assertNotNull(events.single { it.kind == ExternalAgentEventKind.UI_COMMAND }.uiCommand)
    assertEquals("open_session", command.action)
    assertEquals("session-1", command.sessionId)
    assertEquals("active", command.params["view"])
  }

  @Test
  fun claudeAskUserQuestionToolUseAlsoEmitsAskUserUiCommands() {
    val events = parseExternalAgentLine(
      ExternalAgentType.CLAUDE,
      """
      {"type":"assistant","message":{"content":[{"type":"tool_use","id":"toolu_2","name":"AskUserQuestion","input":{"questions":[{"question":"Which device should the trail run on?","header":"Device","options":[{"label":"Pixel emulator","description":"local"},{"label":"iPhone simulator","description":"local"}],"multiSelect":false}]}}]}}
      """.trimIndent(),
    )

    // The raw tool call stays in the stream (the transcript shows it); the ask_user command is
    // what the composer renders as clickable answer chips.
    assertEquals(
      listOf(ExternalAgentEventKind.TOOL_CALL, ExternalAgentEventKind.UI_COMMAND),
      events.map { it.kind },
    )
    val command = assertNotNull(events.single { it.kind == ExternalAgentEventKind.UI_COMMAND }.uiCommand)
    assertEquals("ask_user", command.action)
    assertEquals("Which device should the trail run on?", command.message)
    assertEquals("Pixel emulator|iPhone simulator", command.params["options"])
  }

  @Test
  fun claudeThinkingTokenTicksAreDropped() {
    val events = parseExternalAgentLine(
      ExternalAgentType.CLAUDE,
      """
      {"type":"system","subtype":"thinking_tokens","estimated_tokens":45,"estimated_tokens_delta":42,"session_id":"s1"}
      """.trimIndent(),
    )

    assertTrue(events.isEmpty())
  }

  @Test
  fun claudeToolResultBlockArrayContentReadsAsJoinedText() {
    val events = parseExternalAgentLine(
      ExternalAgentType.CLAUDE,
      """
      {"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"line one"},{"type":"text","text":"line two"}]}]}}
      """.trimIndent(),
    )

    val event = events.single()
    assertEquals(ExternalAgentEventKind.TOOL_RESULT, event.kind)
    assertEquals("toolu_1", event.toolCallId)
    assertEquals("line one\nline two", event.text)
  }

  @Test
  fun codexCommandExecutionBecomesToolCallAndToolResultEvents() {
    val started = parseExternalAgentLine(
      ExternalAgentType.CODEX,
      """
      {"type":"item.started","item":{"id":"cmd_1","type":"command_execution","command":"printf hi","status":"in_progress"}}
      """.trimIndent(),
    ).single()
    val completed = parseExternalAgentLine(
      ExternalAgentType.CODEX,
      """
      {"type":"item.completed","item":{"id":"cmd_1","type":"command_execution","command":"printf hi","aggregated_output":"hi","exit_code":0,"status":"completed"}}
      """.trimIndent(),
    ).single()

    assertEquals(ExternalAgentEventKind.TOOL_CALL, started.kind)
    assertEquals("shell", started.toolName)
    assertEquals("cmd_1", started.toolCallId)
    assertEquals("printf hi", started.text)

    assertEquals(ExternalAgentEventKind.TOOL_RESULT, completed.kind)
    assertEquals("shell", completed.toolName)
    assertEquals("cmd_1", completed.toolCallId)
    assertEquals("hi", completed.text)
    assertTrue(completed.output.toString().contains("\"exit_code\":0"))
  }

  @Test
  fun codexAgentMessageCanCarryTrailrunnerUiCommand() {
    val events = parseExternalAgentLine(
      ExternalAgentType.CODEX,
      """
      {"type":"item.completed","item":{"id":"msg_1","type":"agent_message","text":"Showing the trail.\nTRAILRUNNER_UI {\"version\":1,\"action\":\"open_trail\",\"trailId\":\"demo/login\"}"}}
      """.trimIndent(),
    )

    assertEquals(
      listOf(ExternalAgentEventKind.ASSISTANT_MESSAGE, ExternalAgentEventKind.UI_COMMAND),
      events.map { it.kind },
    )
    val command = assertNotNull(events.single { it.kind == ExternalAgentEventKind.UI_COMMAND }.uiCommand)
    assertEquals("open_trail", command.action)
    assertEquals("demo/login", command.trailId)
  }
}
