package xyz.block.trailblaze.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [HostLocalToolFailure.describe] sits at every level of a nested scripted-tool call, so these
 * tests build the chain the way production does: describe the innermost failure, wrap it the way
 * the SDK rethrows a nested failure (`callTool("<name>") tool failed: …` plus the caller's own
 * stack), describe again.
 */
class HostLocalToolFailureTest {

  @Test
  fun `a directly called tool keeps its message and whole stack, minus the generic header`() {
    val raw = "Error: tap: Element ref 'h619' not found on current screen\n" +
      "    at tap (tools/tap.ts:12:9)\n" +
      "    at async <anonymous> (tools/tap.ts:40:3)"

    assertEquals(
      "tap: Element ref 'h619' not found on current screen\n" +
        "    at tap (tools/tap.ts:12:9)\n" +
        "    at async <anonymous> (tools/tap.ts:40:3)",
      HostLocalToolFailure.describe(raw),
    )
  }

  @Test
  fun `a typed error keeps its name`() {
    val raw = "TypeError: ctx.tools.tapp is not a function\n    at run (tools/flow.ts:8:5)"

    assertEquals(raw, HostLocalToolFailure.describe(raw))
  }

  @Test
  fun `a message that is not a scripted-tool envelope passes through untouched`() {
    val raw = "QuickJS tool returned an envelope without `content`"

    assertEquals(raw, HostLocalToolFailure.describe(raw))
  }

  @Test
  fun `a failure three tools deep leads with the cause, names the path, and keeps the throw site`() {
    val innermost = HostLocalToolFailure.describe(
      "Error: clearAndLaunch: could not find an installed app. Installed apps: a, b\n" +
        "    at clearAndLaunch (tools/clearAndLaunch.ts:620:11)\n" +
        "    at async <anonymous> (tools/clearAndLaunch.ts:700:3)",
    )
    val middle = HostLocalToolFailure.describe(
      sdkRethrow(nested = "clearAndLaunch", failure = innermost, caller = "tools/launchSignedIn.ts:55:7"),
    )
    val outermost = HostLocalToolFailure.describe(
      sdkRethrow(nested = "launchSignedIn", failure = middle, caller = "tools/launchWithAccount.ts:30:5"),
    )

    assertEquals(
      "clearAndLaunch: could not find an installed app. Installed apps: a, b\n" +
        "  via clearAndLaunch\n" +
        "    at clearAndLaunch (tools/clearAndLaunch.ts:620:11)",
      middle,
    )
    assertEquals(
      "clearAndLaunch: could not find an installed app. Installed apps: a, b\n" +
        "  via launchSignedIn → clearAndLaunch\n" +
        "    at clearAndLaunch (tools/clearAndLaunch.ts:620:11)",
      outermost,
    )
    assertFalse("tool failed:" in outermost, "The SDK's per-level wrapper must not survive")
    assertEquals(1, outermost.lines().count { it.trimStart().startsWith("at ") }, "Only the throw site stays")
  }

  @Test
  fun `the wrappers an earlier daemon stacked unwrap to the same shape`() {
    // The exact shape reported from the field: three `Host-local tool execution failed: Error:`
    // wrappers around the cause, then every level's stack.
    val raw = "Host-local tool execution failed: Error: trailblaze.client.callTool(\"b\") tool failed: " +
      "Host-local tool execution failed: Error: trailblaze.client.callTool(\"c\") tool failed: " +
      "Host-local tool execution failed: Error: c: boom\n" +
      "    at c (c.ts:1:1)\n" +
      "    at b (b.ts:2:2)\n" +
      "    at a (a.ts:3:3)"

    assertEquals("c: boom\n  via b → c\n    at c (c.ts:1:1)", HostLocalToolFailure.describe(raw))
  }

  @Test
  fun `a nested device tool failure drops the driver wrapper`() {
    val raw = sdkRethrow(
      nested = "tap",
      failure = "ANDROID_ONDEVICE_INSTRUMENTATION tool execution failed: tap: Element ref 'h619' not found",
      caller = "tools/flow.ts:4:4",
    )

    assertEquals(
      "tap: Element ref 'h619' not found\n  via tap\n    at run (tools/flow.ts:4:4)",
      HostLocalToolFailure.describe(raw),
    )
  }

  @Test
  fun `a multi-line cause keeps every line and drops a truncation marker`() {
    val raw = sdkRethrow(
      nested = "x",
      failure = "line one\nline two\n    at x (x.ts:1:1)\n...[stack truncated]",
      caller = "flow.ts:9:9",
    )

    assertEquals("line one\nline two\n  via x\n    at x (x.ts:1:1)", HostLocalToolFailure.describe(raw))
  }

  @Test
  fun `a nested failure without a stack has no throw-site line`() {
    assertEquals(
      "(no message)\n  via x",
      HostLocalToolFailure.describe("Error: trailblaze.client.callTool(\"x\") tool failed: (no message)"),
    )
  }

  @Test
  fun `a tool message that reads like a wrapper is not cut down to its last word`() {
    // "<word> tool execution failed: " is a shape a tool's own message can have. Only the
    // wrappers Trailblaze adds may be stripped, or the cause is thrown away.
    val raw = "Error: Payment tool execution failed: declined"

    assertEquals("Payment tool execution failed: declined", HostLocalToolFailure.describe(raw))
    assertEquals(
      "Payment tool execution failed: declined\n  via charge",
      HostLocalToolFailure.describe(
        "Error: trailblaze.client.callTool(\"charge\") tool failed: Payment tool execution failed: declined",
      ),
    )
  }

  @Test
  fun `a command's own output keeps its stack, including the frames below the first`() {
    // `exec` puts the command's unfiltered output in the failure message on purpose — hiding the
    // lines needed to diagnose it is the bug that flag exists to avoid. A JVM subprocess's frames
    // are that output, not the JS stack this formatter dedupes.
    val commandFailure = "Command exited with 1 (expected 0): ./gradlew check\n" +
      "Caused by: java.lang.IllegalStateException: boom\n" +
      "    at com.example.Thing.method(Thing.kt:42)\n" +
      "    at com.example.Other.run(Other.kt:7)"

    val described = HostLocalToolFailure.describe(
      sdkRethrow(nested = "build", failure = commandFailure, caller = "tools/build.ts:3:3"),
    )

    assertEquals(
      commandFailure + "\n  via build\n    at run (tools/build.ts:3:3)",
      described,
      "the command's output must survive whole, with only the JS throw site added",
    )
  }

  @Test
  fun `a bare Bun frame is a frame, so nesting does not add one stack line per level`() {
    // Bun writes the top-level frame without a function name: `at <file>:<line>:<column>`, no
    // parentheses. Read as message text, one of them rides along at every level of nesting —
    // exactly the growth this formatter exists to stop.
    val innermost = HostLocalToolFailure.describe(
      "Error: seed: no merchant on this device\n" +
        "    at seed (/app/tools/seed.ts:9:3)\n" +
        "    at /app/tools/seed.ts:1:145",
    )
    val middle = HostLocalToolFailure.describe(
      sdkRethrow(nested = "seed", failure = innermost, caller = "/app/tools/flow.ts:4:4") +
        "\n    at /app/tools/flow.ts:1:145",
    )
    val outermost = HostLocalToolFailure.describe(
      sdkRethrow(nested = "flow", failure = middle, caller = "/app/tools/top.ts:2:2") +
        "\n    at /app/tools/top.ts:1:145",
    )

    assertEquals(
      "seed: no merchant on this device\n  via seed\n    at seed (/app/tools/seed.ts:9:3)",
      middle,
    )
    assertEquals(
      "seed: no merchant on this device\n  via flow → seed\n    at seed (/app/tools/seed.ts:9:3)",
      outermost,
    )
    assertEquals(1, outermost.lines().count { it.trimStart().startsWith("at ") }, "Only the throw site stays")
  }

  @Test
  fun `a message line that ends in a clock time is not mistaken for a bare frame`() {
    val raw = sdkRethrow(
      nested = "poll",
      failure = "timed out waiting for the batch\n    at 12:30:45",
      caller = "/app/tools/poll.ts:5:5",
    )

    assertEquals(
      "timed out waiting for the batch\n    at 12:30:45\n  via poll\n    at run (/app/tools/poll.ts:5:5)",
      HostLocalToolFailure.describe(raw),
    )
  }

  /** The SDK's rethrow of a nested failure, wrapped in the calling tool's own envelope. */
  private fun sdkRethrow(nested: String, failure: String, caller: String): String =
    "Error: trailblaze.client.callTool(\"$nested\") tool failed: $failure\n" +
      "    at run ($caller)\n" +
      "    at async <anonymous> ($caller)"
}
