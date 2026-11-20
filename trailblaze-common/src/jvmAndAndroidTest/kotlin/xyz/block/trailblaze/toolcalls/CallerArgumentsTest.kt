package xyz.block.trailblaze.toolcalls

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool

class CallerArgumentsTest {

  private val forwardsExec = OtherTrailblazeTool(
    "runIf",
    buildJsonObject {
      put("then", buildJsonArray { add(buildJsonObject { put("exec", buildJsonObject { put("argv", JsonPrimitive("x")) }) }) })
      put("else", buildJsonArray { add(buildJsonObject { put("tool", RegisterCleanupTrailblazeTool.TOOL_NAME) }) })
    },
  )

  @Test
  fun `a tool-only name a caller outside any tool passed in is refused, and other names are not`() {
    val origin = CallerArguments()
    origin.noteDispatch(forwardsExec)

    assertNotNull(origin.forwardedToolOnlyRefusal("exec", repo = null))
    assertNotNull(origin.forwardedToolOnlyRefusal(RegisterCleanupTrailblazeTool.TOOL_NAME, repo = null))
    // Named, but not tool-only.
    assertNull(origin.forwardedToolOnlyRefusal("runIf", repo = null))
    // Tool-only, but never named by the caller.
    assertNull(CallerArguments().forwardedToolOnlyRefusal("exec", repo = null))
  }

  @Test
  fun `a caller's names count only until its call ends`() {
    val origin = CallerArguments()
    val note = assertNotNull(origin.noteDispatch(forwardsExec))

    note.end()

    // A later call in the same batch whose own code runs `exec`.
    assertNull(origin.forwardedToolOnlyRefusal("exec", repo = null))
  }

  @Test
  fun `a memory token resolving to a tool-only name is refused`() {
    val origin = CallerArguments()
    val forwardsToken = OtherTrailblazeTool("forward", buildJsonObject { put("tool", "{{t}}") })

    origin.noteDispatch(forwardsToken) { if (it == "{{t}}") "exec" else it }

    assertNotNull(origin.forwardedToolOnlyRefusal("exec", repo = null))
  }

  @Test
  fun `a call a tool makes is not noted, and a context built for it shares the caller's origin`() = runBlocking {
    val origin = CallerArguments()
    origin.callFromTool {
      // E.g. `ctx.session.addCleanup({ tool: "exec" })`: the tool's own code chose `exec`.
      assertNull(origin.noteDispatch(OtherTrailblazeTool("trailblaze_registerCleanup", buildJsonObject { put("tool", "exec") })))
      // The daemon builds a fresh context for each nested host-local call from this.
      assertSame(origin, CallerArguments.current())
    }
    assertNull(origin.forwardedToolOnlyRefusal("exec", repo = null))
    assertNull(CallerArguments.current())
  }

  @Test
  fun `an outside call is noted while another coroutine runs a tool's call`() = runBlocking {
    val origin = CallerArguments()
    val inToolCall = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val toolCall = launch {
      origin.callFromTool {
        inToolCall.complete(Unit)
        release.await()
      }
    }
    inToolCall.await()

    val note = origin.noteDispatch(forwardsExec)

    release.complete(Unit)
    toolCall.join()
    assertNotNull(note)
    assertNotNull(origin.forwardedToolOnlyRefusal("exec", repo = null))
    Unit
  }

  @Test
  fun `the repo decides which names are tool-only`() {
    val origin = CallerArguments()
    origin.noteDispatch(forwardsExec)
    val repo = TrailblazeToolRepo(TrailblazeToolSet.DynamicTrailblazeToolSet("lists-exec", setOf(ExecTrailblazeTool::class)))

    assertNotNull(origin.forwardedToolOnlyRefusal("exec", repo))
  }
}
