package xyz.block.trailblaze.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Explicit runtime selector for scripted tools.
 *
 * Trailblaze can execute author-supplied scripted tools under two runtimes:
 *
 *  - [SUBPROCESS] — a `bun` or `tsx` child process, via the MCP stdio transport. The handler
 *    runs in a real Node-like environment with the full `node:fs`, `node:child_process`, etc.
 *    surface. Use this for tools that need to read/write files, hold cross-process locks,
 *    spawn helper processes, or anything else QuickJS can't do.
 *  - [IN_PROCESS] — the embedded QuickJS engine. The handler runs in-JVM, has access to
 *    `client.callTool(...)` for composing other Trailblaze tools, but does NOT have Node
 *    APIs. Faster startup, deterministic sandboxing, and the only path that works inside
 *    the on-device `:trailblaze-scripting-bundle`.
 *
 * **Default routing (when [PackScriptedToolFile.runtime] / [InlineScriptToolConfig.runtime]
 * is `null`)** is extension-based: `.js` / `.mjs` / `.cjs` → [SUBPROCESS]; everything else
 * (notably `.ts`) → [IN_PROCESS]. The extension heuristic remains for backwards compatibility
 * with the descriptors that pre-date this field. Authors who want explicit control —
 * particularly authors who want to use `.ts` syntax under bun/tsx where it's natively
 * supported — set this field on the descriptor.
 *
 * Example use cases:
 *
 *  - `runtime: subprocess` with `script: ./foo.ts` — TypeScript author who needs `node:fs`
 *    for an on-disk cache. Without the override, `.ts` would route to QuickJS and `fs`
 *    would be missing.
 *  - `runtime: inProcess` with `script: ./bar.js` — JS author who wants the faster
 *    in-process path despite the conventional extension default. Rare, but legal.
 */
@Serializable
enum class ScriptedToolRuntime {
  /** `bun run <script>` / `tsx <script>` subprocess. Full Node API surface. */
  @SerialName("subprocess")
  SUBPROCESS,

  /** QuickJS in-JVM. No Node APIs; composes via `client.callTool`. */
  @SerialName("inProcess")
  IN_PROCESS,
  ;

  companion object {
    /**
     * Resolves the effective runtime for a scripted tool: the explicit [override] if set,
     * otherwise the extension-based default derived from [scriptPath] (`.js`/`.mjs`/`.cjs`
     * → [SUBPROCESS]; everything else → [IN_PROCESS]).
     *
     * Single source of truth shared by `TrailblazeHostYamlRunner` (the production routing
     * site) and the unit tests that pin the routing contract. Putting the decision here
     * rather than inline in the runner means the test suite can exercise every branch of
     * the rule without spinning up the runner.
     */
    fun resolve(scriptPath: String, override: ScriptedToolRuntime?): ScriptedToolRuntime {
      if (override != null) return override
      val lowered = scriptPath.lowercase()
      val nodeExtension =
        lowered.endsWith(".js") || lowered.endsWith(".mjs") || lowered.endsWith(".cjs")
      return if (nodeExtension) SUBPROCESS else IN_PROCESS
    }
  }
}
