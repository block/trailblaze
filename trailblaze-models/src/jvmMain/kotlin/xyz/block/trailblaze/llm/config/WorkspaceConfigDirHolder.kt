package xyz.block.trailblaze.llm.config

import java.io.File

/**
 * Process-wide hook used by [platformConfigResourceSource] to discover the current workspace
 * `trails/config/` directory.
 *
 * The default resolver returns `null` — i.e. "no workspace, classpath-only." Higher modules
 * install a real resolver at process startup; see
 * `xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigBootstrap` in
 * `trailblaze-common`, which delegates to `TrailblazeWorkspaceConfigResolver.resolve(...)`.
 *
 * **Why this exists.** `TrailblazeWorkspaceConfigResolver` lives in `trailblaze-common`, which
 * sits downstream of `trailblaze-models`. The JVM platform default can't import it directly
 * without inverting the module-dependency arrow. The holder is the cheapest bridge: models
 * defines the contract, common installs the implementation, the wiring is visible at any
 * process entry point that calls `TrailblazeWorkspaceConfigBootstrap.ensureInstalled()`.
 *
 * **Tests.** Pin a fixture workspace by swapping [resolver] in `@Before` and restoring in
 * `@After`. The holder is intentionally a single mutable field so tests don't need to spin
 * up the full workspace-config plumbing for unit-level coverage of discovery sites.
 */
object WorkspaceConfigDirHolder {
  @Volatile
  var resolver: () -> File? = { null }
}
