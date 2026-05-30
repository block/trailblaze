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
 *  - [command] — explicit stdio MCP server command as an argument list, where
 *    the first element is the executable and the rest are arguments (e.g.
 *    `["python", "my-server.py"]`, `["npx", "my-package"]`). Host-agent only
 *    — cannot be bundled for on-device. Any MCP-speaking executable works.
 *    Using a list instead of a shell string avoids shell-injection risks and
 *    escaping errors. **Reserved in the schema for forward compatibility; not
 *    implemented in the current landing.** See
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
   * **Path resolution:**
   *  - For trailmap-loaded manifests (`trailmap.yaml`), relative paths resolve against
   *    the trailmap manifest's directory. The trailmap loader rewrites them to absolute
   *    paths before they reach the runtime, so a trailmap is self-contained.
   *  - For legacy non-trailmap target YAMLs, relative paths resolve against the
   *    JVM's current working directory (the directory `./trailblaze` was
   *    invoked from). This is the original `McpSubprocessSpawner` contract.
   *  - Absolute paths pass through unchanged in either case.
   */
  val script: String? = null,
  /**
   * Explicit stdio MCP server command as an argument list (`[executable, arg1, arg2, ...]`,
   * e.g. `["python", "my-server.py"]` or `["npx", "my-package"]`). The first element is the
   * executable; remaining elements are passed as individual arguments — no shell is invoked,
   * so no escaping is required. Host-agent only. Mutually exclusive with [script]. [env]
   * configures the spawn environment. **Schema reserved; the current landing does not yet
   * implement `command:` spawns.**
   */
  val command: List<String>? = null,
  val env: Map<String, String>? = null,
) {
  init {
    require((script?.isNotBlank() == true) xor (command?.isNotEmpty() == true)) {
      "mcp_servers entry requires exactly one of `script:` or `command:` (non-empty list)"
    }
    require(command != null || env == null) {
      "mcp_servers `env:` is only valid alongside `command:` entries"
    }
  }

  /**
   * True iff this entry is bundleable for on-device-agent execution (the
   * on-device bundler's filter). Only `script:` entries are bundleable;
   * `command:` entries are host-only.
   */
  val isBundleable: Boolean get() = script != null
}
