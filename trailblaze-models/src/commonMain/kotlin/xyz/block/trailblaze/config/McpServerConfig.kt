package xyz.block.trailblaze.config

import kotlinx.serialization.Serializable

/**
 * Declaration of a single MCP server that contributes tools to the Trailblaze
 * tool registry. Appears in `AppTargetYamlConfig.mcpServers` at the target
 * root, and (future) inside `ToolSetYamlConfig.mcpServers` at the toolset
 * level.
 *
 * Two mutually-exclusive entry shapes:
 *
 *  - [script] — path to a `.ts` (or pre-compiled `.js`) file. Dual-target:
 *    Trailblaze spawns as a bun/node subprocess for host-agent sessions, or
 *    compiles + bundles for on-device-agent sessions (via the on-device
 *    QuickJS bundle path). First-class for authoring Trailblaze-aware tools
 *    with typesafe annotations through `@trailblaze/scripting`.
 *
 *  - [command] — explicit stdio MCP server command. Host-agent only — cannot
 *    be bundled for on-device. Any MCP-speaking executable works: Python
 *    servers, compiled binaries, `npx`-vendored npm packages. **Reserved in
 *    the schema for forward compatibility; not implemented in the current
 *    landing.** See
 *    `docs/devlog/2026-04-21-scripted-tools-mcp-integration-patterns.md`
 *    for the future integration design.
 *
 * Per the Decision 038 scope devlog
 * (`docs/devlog/2026-04-20-scripted-tools-a3-host-subprocess.md`): tool names
 * are registered exactly as the MCP server advertises them — no mechanical
 * prefixing or renaming. Authors own names at the source, per Decision 014.
 */
@Serializable
data class McpServerConfig(
  /**
   * Path to a `.ts` / `.js` script implementing an MCP server, typically
   * authored with `@trailblaze/scripting`. Dual-target (host subprocess +
   * future on-device bundle). Mutually exclusive with [command].
   *
   * **Path resolution:** absolute paths pass through unchanged. Relative
   * paths resolve against the JVM's current working directory — i.e. where
   * the author ran `./trailblaze` from, which is the project root for the
   * common single-repo layout. Resolution happens in the
   * `:trailblaze-scripting-subprocess` runtime
   * (`McpSubprocessSpawner.resolveScriptPath`); the scope devlog's §
   * Config surface documents the rationale. A future per-target
   * `script_root:` override can be added additively if a concrete need
   * surfaces.
   */
  val script: String? = null,
  /**
   * Explicit stdio MCP server command (e.g. `python`, `npx`, `./my-binary`).
   * Host-agent only. Mutually exclusive with [script]. [args] and [env]
   * configure the spawn. **Schema reserved; the current landing does not
   * yet implement `command:` spawns.**
   */
  val command: String? = null,
  val args: List<String>? = null,
  val env: Map<String, String>? = null,
) {
  init {
    require((script?.isNotBlank() == true) xor (command?.isNotBlank() == true)) {
      "mcp_servers entry requires exactly one of `script:` or `command:` (non-blank)"
    }
    require(command != null || (args == null && env == null)) {
      "mcp_servers `args:` and `env:` are only valid alongside `command:` entries"
    }
  }

  /**
   * True iff this entry is bundleable for on-device-agent execution (the
   * on-device bundler's filter). Only `script:` entries are bundleable;
   * `command:` entries are host-only.
   */
  val isBundleable: Boolean get() = script != null
}
