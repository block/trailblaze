package xyz.block.trailblaze.llm.config

import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.util.Console

/**
 * JVM platform default: workspace-layered when a workspace resolves, classpath-only otherwise.
 *
 * The workspace dir is looked up through [WorkspaceConfigDirHolder.resolver]. Higher modules
 * (typically `trailblaze-common` via `TrailblazeWorkspaceConfigBootstrap`) install a resolver
 * that walks up from the CWD and honors the `TRAILBLAZE_CONFIG_DIR` env var. When no resolver
 * is installed (or the resolver returns `null`), this collapses to
 * [ClasspathConfigResourceSource] — the same behavior the platform had before workspace
 * layering moved into the default.
 *
 * The layered result respects the "later sources override earlier" rule documented on
 * [CompositeConfigResourceSource] — workspace-on-disk beats classpath-bundled when both
 * contribute the same logical key.
 *
 * **Debuggability.** The first time the classpath-only fallback fires we emit a one-time log
 * line so an operator triaging "where did my workspace tools go?" has a hint. The line is
 * silent on subsequent calls (the bootstrap state never flips back) and silent entirely when
 * a workspace resolves — i.e. it only appears in the actual "bootstrap omitted or no
 * workspace" case, which is the case worth flagging.
 */
private val classpathFallbackLogged = AtomicBoolean(false)

actual fun platformConfigResourceSource(): ConfigResourceSource {
  val configDir = WorkspaceConfigDirHolder.resolver()
  if (configDir == null && classpathFallbackLogged.compareAndSet(false, true)) {
    Console.log(
      "[platformConfigResourceSource] No workspace config dir resolved; using classpath-only " +
        "discovery. If you expected workspace `trails/config/` files to surface, ensure " +
        "`TrailblazeWorkspaceConfigBootstrap.ensureInstalled()` was called at process startup.",
    )
  }
  return workspaceLayeredConfigResourceSource(
    configDir = configDir,
    logPrefix = "[platformConfigResourceSource]",
  )
}

/** On JVM, the bundled (non-workspace-layered) view is just the classpath. */
actual fun bundledConfigResourceSource(): ConfigResourceSource = ClasspathConfigResourceSource
