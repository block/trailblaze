package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.Test

/** Covers the crash-aware / transient branching that lives under `SubprocessTrailblazeTool.execute`. */
class MapDispatchFailureTest {

  @Serializable private data class StubTool(val name: String) : TrailblazeTool

  private val stubTool = StubTool("myapp_login")
  private val cause = IllegalStateException("stream closed")

  @Test fun `subprocess still alive produces ExceptionThrown`() {
    val result = mapDispatchFailure(
      cause = cause,
      toolName = "myapp_login",
      isAlive = true,
      stderrTail = listOf("doesn't matter"),
      exitCode = null,
      tool = stubTool,
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
      .prop(TrailblazeToolResult.Error.ExceptionThrown::errorMessage).contains("stream closed")
  }

  @Test fun `subprocess dead produces FatalError with tool name and stderr tail`() {
    val result = mapDispatchFailure(
      cause = cause,
      toolName = "myapp_login",
      isAlive = false,
      stderrTail = listOf("ERR: boot failed", "panic: invalid config"),
      exitCode = 2,
      tool = stubTool,
    )
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.FatalError::class)
    val fatal = result as TrailblazeToolResult.Error.FatalError
    assertThat(fatal.errorMessage).contains("Subprocess MCP server died")
    assertThat(fatal.errorMessage).contains("myapp_login")
    assertThat(fatal.errorMessage).contains("exit code: 2")
    assertThat(fatal.errorMessage).contains("stream closed")
    assertThat(fatal.errorMessage).contains("ERR: boot failed")
    assertThat(fatal.errorMessage).contains("panic: invalid config")
  }

  @Test fun `unavailable exit code surfaces as explicit placeholder`() {
    val result = mapDispatchFailure(
      cause = cause,
      toolName = "x",
      isAlive = false,
      stderrTail = emptyList(),
      exitCode = null,
      tool = stubTool,
    )
    val fatal = result as TrailblazeToolResult.Error.FatalError
    assertThat(fatal.errorMessage).contains("exit code: (unavailable)")
  }

  @Test fun `empty stderr tail surfaces explicit placeholder instead of blank block`() {
    val result = mapDispatchFailure(
      cause = cause,
      toolName = "x",
      isAlive = false,
      stderrTail = emptyList(),
      exitCode = 0,
      tool = stubTool,
    )
    val fatal = result as TrailblazeToolResult.Error.FatalError
    assertThat(fatal.errorMessage).contains("(no stderr captured)")
    // No dangling empty "tail stderr:" block.
    assertThat(fatal.errorMessage.trimEnd()).doesNotContain("tail stderr:\n\n")
  }

  @Test fun `FatalError carries the cause stack trace`() {
    val withStack = RuntimeException("boom").apply { fillInStackTrace() }
    val result = mapDispatchFailure(
      cause = withStack,
      toolName = "x",
      isAlive = false,
      stderrTail = emptyList(),
      exitCode = null,
      tool = stubTool,
    )
    val fatal = result as TrailblazeToolResult.Error.FatalError
    assertThat(fatal.stackTraceString!!).contains("boom")
  }

  @Test fun `null cause message falls back to cause class name`() {
    val nullMessageCause = object : RuntimeException() {}
    val result = mapDispatchFailure(
      cause = nullMessageCause,
      toolName = "x",
      isAlive = false,
      stderrTail = emptyList(),
      exitCode = null,
      tool = stubTool,
    )
    val fatal = result as TrailblazeToolResult.Error.FatalError
    // Should include either the class name or a non-empty cause line (not "cause: null").
    assertThat(fatal.errorMessage).doesNotContain("cause: null")
  }

  // Sanity: both branches produce Error-variant results (not Success).
  @Test fun `both branches produce Error variants`() {
    val alive = mapDispatchFailure(cause, "x", isAlive = true, emptyList(), null, stubTool)
    val dead = mapDispatchFailure(cause, "x", isAlive = false, emptyList(), null, stubTool)
    assertThat(alive).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(dead).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(alive::class).isEqualTo(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat(dead::class).isEqualTo(TrailblazeToolResult.Error.FatalError::class)
  }
}
