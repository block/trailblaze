package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the runtime-routing policy for scripted tools:
 *
 *  - Default (no explicit `runtime:`) is IN_PROCESS, unconditionally.
 *  - SUBPROCESS is opt-in only — chosen exactly when the descriptor declares it.
 *  - There is no extension heuristic; a `.js` file is NOT auto-routed to a subprocess.
 *
 * The production routing site (`TrailblazeHostYamlRunner`) delegates to
 * [ScriptedToolRuntime.resolve] so a regression here surfaces as a failed unit test rather
 * than an opaque "tool fails on first `node:fs` call" runtime error.
 */
class ScriptedToolRuntimeTest {

  @Test
  fun `explicit subprocess override is honored`() {
    assertEquals(
      ScriptedToolRuntime.SUBPROCESS,
      ScriptedToolRuntime.resolve(ScriptedToolRuntime.SUBPROCESS),
    )
  }

  @Test
  fun `explicit inProcess override is honored`() {
    assertEquals(
      ScriptedToolRuntime.IN_PROCESS,
      ScriptedToolRuntime.resolve(ScriptedToolRuntime.IN_PROCESS),
    )
  }

  @Test
  fun `null override defaults to in-process`() {
    // The default is in-process, full stop — subprocess is never inferred from the script's
    // name or anything else. A tool that needs Node APIs must declare `runtime: subprocess`.
    assertEquals(ScriptedToolRuntime.IN_PROCESS, ScriptedToolRuntime.resolve(null))
  }
}
