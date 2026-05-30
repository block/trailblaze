package xyz.block.trailblaze.config.project

import java.nio.file.Paths
import xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder

/**
 * Wires [WorkspaceConfigDirHolder] (in `trailblaze-models`) to delegate workspace-dir
 * resolution to [TrailblazeWorkspaceConfigResolver] (in this module).
 *
 * Bridges the model-boundary gap: `trailblaze-models` defines
 * [WorkspaceConfigDirHolder.resolver] as a `() -> File?` hook with a `null`-returning default,
 * and we install the real implementation here so any consumer that loads this module gets
 * workspace-aware [xyz.block.trailblaze.llm.config.platformConfigResourceSource] for free.
 *
 * **Why `ensureInstalled()` is a no-op.** Kotlin `object` initialization runs the `init {}`
 * block on first class load. The function is the cheapest way for a caller at a process
 * entry point (CLI main, MCP server bootstrap, desktop app `Application`, test base class)
 * to *trigger* class load and thus the install — without exposing the holder mutation.
 *
 * **Idempotency.** Re-installing is safe: the resolver is a pure CWD lookup, no side effects.
 * Call sites that want to pin a fixture workspace dir in tests can override
 * [WorkspaceConfigDirHolder.resolver] directly *after* `ensureInstalled()` runs.
 */
object TrailblazeWorkspaceConfigBootstrap {
  init {
    WorkspaceConfigDirHolder.resolver = {
      TrailblazeWorkspaceConfigResolver.resolve(Paths.get("")).configDir
    }
  }

  fun ensureInstalled() = Unit
}
