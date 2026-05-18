package xyz.block.trailblaze.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the single source of truth for runtime routing of scripted tools:
 *
 *  - Explicit `runtime:` on the descriptor always wins, regardless of extension.
 *  - Otherwise: `.js` / `.mjs` / `.cjs` → subprocess; everything else → in-process QuickJS.
 *
 * The production routing site (`TrailblazeHostYamlRunner`) delegates to
 * [ScriptedToolRuntime.resolve] so a regression here surfaces as a failed unit test
 * rather than as an opaque "tool fails on first `fs.readFileSync` call" runtime error.
 */
class ScriptedToolRuntimeTest {

  @Test
  fun `explicit subprocess override wins over a typescript extension`() {
    assertEquals(
      ScriptedToolRuntime.SUBPROCESS,
      ScriptedToolRuntime.resolve("./foo.ts", ScriptedToolRuntime.SUBPROCESS),
    )
  }

  @Test
  fun `explicit inProcess override wins over a javascript extension`() {
    assertEquals(
      ScriptedToolRuntime.IN_PROCESS,
      ScriptedToolRuntime.resolve("./foo.js", ScriptedToolRuntime.IN_PROCESS),
    )
  }

  @Test
  fun `null override falls back to extension for js mjs cjs`() {
    // Each Node-conventional extension routes to subprocess so authors who pre-compiled
    // to JS (or use ESM .mjs / CJS .cjs) automatically get the Node runtime they expect.
    assertEquals(ScriptedToolRuntime.SUBPROCESS, ScriptedToolRuntime.resolve("./foo.js", null))
    assertEquals(ScriptedToolRuntime.SUBPROCESS, ScriptedToolRuntime.resolve("./foo.mjs", null))
    assertEquals(ScriptedToolRuntime.SUBPROCESS, ScriptedToolRuntime.resolve("./foo.cjs", null))
  }

  @Test
  fun `null override falls back to in-process for ts and unknown extensions`() {
    assertEquals(ScriptedToolRuntime.IN_PROCESS, ScriptedToolRuntime.resolve("./foo.ts", null))
    assertEquals(ScriptedToolRuntime.IN_PROCESS, ScriptedToolRuntime.resolve("./foo.tsx", null))
    // Fall-through case — keep the contract conservative for surprising suffixes so the
    // QuickJS bundler is what surfaces the error, not a silent reroute to subprocess.
    assertEquals(ScriptedToolRuntime.IN_PROCESS, ScriptedToolRuntime.resolve("./foo.kt", null))
  }

  @Test
  fun `extension matching is case-insensitive`() {
    // Authors copy paths from Finder / Windows Explorer; tolerate `.JS` so a stray
    // uppercase doesn't silently reroute a Node-API-dependent tool to QuickJS.
    assertEquals(ScriptedToolRuntime.SUBPROCESS, ScriptedToolRuntime.resolve("./Foo.JS", null))
    assertEquals(ScriptedToolRuntime.IN_PROCESS, ScriptedToolRuntime.resolve("./Foo.TS", null))
  }
}
