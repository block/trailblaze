package xyz.block.trailblaze.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Explicit runtime selector for scripted tools.
 *
 * Trailblaze can execute author-supplied scripted tools under two runtimes:
 *
 *  - [SUBPROCESS] — a `bun` child process, via the MCP stdio transport. The handler runs in
 *    a real Node-like environment with the full `node:fs`, `node:child_process`, etc.
 *    surface. Use this for tools that need to read/write files, hold cross-process locks,
 *    spawn helper processes, or anything else QuickJS can't do.
 *  - [IN_PROCESS] — the embedded QuickJS engine. The handler runs in-JVM, has access to
 *    `client.callTool(...)` for composing other Trailblaze tools, but does NOT have Node
 *    APIs. Faster startup, deterministic sandboxing, and the only path that works inside
 *    the on-device `:trailblaze-scripting-bundle`.
 *
 * **Default routing (when [TrailmapScriptedToolFile.runtime] / [InlineScriptToolConfig.runtime]
 * is `null`) is [IN_PROCESS], unconditionally.** [SUBPROCESS] is opt-in only — a tool gets it
 * exactly when its descriptor declares `runtime: subprocess`. There is no extension heuristic;
 * a `.js` file is *not* auto-routed to a subprocess.
 *
 * This makes orchestration the default. Scripted tools mostly compose framework/driver tools
 * (which do the real system work inside Trailblaze), so in-process QuickJS is the right home for
 * almost all of them. A tool needs [SUBPROCESS] only when its *own* TypeScript touches Node APIs
 * (`node:fs`, `node:child_process`, …) — and since a device can't spawn a subprocess, such a tool
 * is host-only by nature. Requiring the explicit flag keeps that the rare, visible exception.
 *
 * Example:
 *
 *  - `runtime: subprocess` with `script: ./writeArtifact.ts` — a tool that itself writes files
 *    via `node:fs`. Without the explicit flag it defaults to QuickJS, where `node:fs` is absent.
 */
@Serializable
enum class ScriptedToolRuntime {
  /** `bun run <script>` subprocess. Full Node API surface. */
  @SerialName("subprocess")
  SUBPROCESS,

  /** QuickJS in-JVM. No Node APIs; composes via `client.callTool`. */
  @SerialName("inProcess")
  IN_PROCESS,
  ;

  companion object {
    /**
     * Resolves the effective runtime for a scripted tool: the explicit [override] if the
     * descriptor declared one, otherwise [IN_PROCESS]. [SUBPROCESS] is never inferred — it is
     * chosen only when a descriptor explicitly sets `runtime: subprocess`.
     *
     * Single source of truth shared by `TrailblazeHostYamlRunner` (the production routing site)
     * and the unit tests that pin the routing contract — the policy ("in-process unless
     * explicitly subprocess") lives in one testable place.
     */
    fun resolve(override: ScriptedToolRuntime?): ScriptedToolRuntime = override ?: IN_PROCESS
  }
}
