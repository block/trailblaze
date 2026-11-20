package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `trailblaze toolbox` leaves out the tools the in-session LLM is not offered, ends with a note
 * that counts them, and lists them, marked, under `--all`.
 */
class ToolboxNotOfferedTest {

  private val command = ToolboxCommand()

  private val defaultTargetResponse = """
    {
      "target": "auditapp",
      "displayName": "Audit App",
      "currentPlatform": "android",
      "toolGroups": [
        {"name": "auditapp", "description": "Audit App tools",
         "toolDetails": [{"name": "audit_visible", "description": "Offered to the LLM."}]}
      ],
      "notOfferedToolCount": 2
    }
  """.trimIndent()

  private val allTargetResponse = """
    {
      "target": "auditapp",
      "displayName": "Audit App",
      "currentPlatform": "android",
      "toolGroups": [
        {"name": "auditapp", "description": "Audit App tools",
         "toolDetails": [
           {"name": "audit_visible", "description": "Offered to the LLM."},
           {"name": "audit_hidden", "description": "Hides itself."},
           {"name": "audit_excluded", "description": "Dropped by the target."}
         ],
         "notOffered": {"audit_hidden": "hidden_by_tool", "audit_excluded": "excluded_by_target"}}
      ],
      "includesNotOffered": true
    }
  """.trimIndent()

  private fun render(content: String, showAll: Boolean, nameFilter: String? = null) =
    renderCapture(content, showAll, nameFilter).out

  private fun renderCapture(
    content: String,
    showAll: Boolean,
    nameFilter: String? = null,
    searchQuery: String? = null,
  ) = captureConsole {
    command.formatToolsResult(
      content = content,
      nameFilter = nameFilter,
      targetFilter = "auditapp",
      searchQuery = searchQuery,
      showDetail = false,
      roleFilter = null,
      suppressIndexContextLine = true,
      showAll = showAll,
      allCommand = ToolboxFormatter.allToolsCommand("auditapp", "android", searchQuery),
    )
  }

  @Test
  fun `default listing ends with a note counting the tools it left out and how to see them`() {
    val out = render(defaultTargetResponse, showAll = false)
    assertFalse("audit_hidden" in out, out)
    assertFalse("audit_excluded" in out, out)
    assertContains(out, "2 more tools are not offered to the LLM")
    assertContains(out, "call one with `trailblaze tool`, a trail step, or a TS tool")
    assertFalse("record" in out.lowercase(), out)
    assertContains(out, "Run `trailblaze toolbox --target auditapp --device android --all` to see them.")
    assertContains(out, "drive a flow")
  }

  @Test
  fun `all lists the hidden and the excluded tool, each marked, with no note`() {
    val out = render(allTargetResponse, showAll = true)
    assertContains(out, "audit_hidden [not offered to LLM]")
    assertContains(out, "audit_excluded [excluded by target]")
    assertFalse("audit_visible [" in out, out)
    assertContains(out, "Marked tools are not offered to the in-session LLM")
    assertFalse("more tools are available" in out, out)
  }

  @Test
  fun `with no device connected, all marks the per-platform listing too`() {
    val response = """
      {
        "target": "auditapp",
        "displayName": "Audit App",
        "toolsByPlatform": [
          {"platform": "Android",
           "tools": [{"name": "audit_visible"}, {"name": "audit_hidden"}],
           "notOffered": {"audit_hidden": "hidden_by_tool"}}
        ],
        "includesNotOffered": true
      }
    """.trimIndent()
    val out = render(response, showAll = true)
    assertContains(out, "audit_hidden [not offered to LLM]")
    assertContains(out, "Marked tools are not offered to the in-session LLM")
  }

  @Test
  fun `a listing with nothing left out prints no note`() {
    val out = render(defaultTargetResponse.replace(",\n  \"notOfferedToolCount\": 2", ""), showAll = false)
    assertFalse("not offered to the LLM" in out, out)
  }

  @Test
  fun `a name lookup of a tool the LLM is not offered says why and shows how to call it instead of Run`() {
    val response = """
      {
        "tool": {"name": "audit_hidden", "description": "Hides itself."},
        "availability": "Not offered to the LLM: the tool hides itself (`surfaceToLlm: false`).",
        "notOffered": "hidden_by_tool",
        "usage": "In a trail recording:\n  - audit_hidden: {}\nFrom a TS tool: ctx.tools.audit_hidden({})"
      }
    """.trimIndent()
    val out = render(response, showAll = false, nameFilter = "audit_hidden")
    assertContains(out, "Not offered to the LLM")
    assertContains(out, "ctx.tools.audit_hidden(")
    assertTrue("Run: " !in out, out)
  }

  @Test
  fun `a search whose every match is not offered points at --all instead of calling it a miss`() {
    val response = """{"query": "audit", "notOfferedToolCount": 2}"""
    val captured = renderCapture(response, showAll = false, searchQuery = "audit")
    assertEquals(ToolboxRenderOutcome.OK, captured.result)
    assertContains(captured.out, "All 2 tools matching \"audit\" are not offered to the LLM")
    assertContains(captured.out, "Run `trailblaze toolbox --search 'audit' --target auditapp --device android --all`")
    assertFalse("No tools found" in captured.out, captured.out)
    assertFalse("more matching tools" in captured.out, "the note repeats the count: ${captured.out}")
  }

  @Test
  fun `a search with no matches at all is still a miss`() {
    val captured = renderCapture("""{"query": "nothing"}""", showAll = false, searchQuery = "nothing")
    assertEquals(ToolboxRenderOutcome.NOT_FOUND, captured.result)
    assertContains(captured.out, "No tools found")
  }

  @Test
  fun `all against a daemon that does not say it honoured the flag warns that it may predate it`() {
    val oldDaemon = allTargetResponse.replace(",\n  \"includesNotOffered\": true", "")
    val warned = renderCapture(oldDaemon, showAll = true)
    assertContains(warned.err, "may predate --all")
    assertContains(warned.err, "trailblaze app --stop")

    assertFalse("predate" in renderCapture(allTargetResponse, showAll = true).err)
    assertFalse("predate" in renderCapture(defaultTargetResponse, showAll = false).err, "no --all, no warning")
  }
}
