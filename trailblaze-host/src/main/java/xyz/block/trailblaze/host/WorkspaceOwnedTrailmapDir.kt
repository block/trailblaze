package xyz.block.trailblaze.host

import xyz.block.trailblaze.util.Console
import java.io.IOException
import java.nio.file.Path

/**
 * Whether [trailmapDir] really lives under [workspaceRoot].
 *
 * A trailmap reached through a symlink into another checkout — for example a workspace whose
 * `config/trailmaps/sampleapp` points into the example app's own workspace — belongs to the
 * workspace that owns the real directory. Generating this workspace's files there would hand the
 * example app this workspace's entire tool catalog, and every daemon start would rewrite them.
 *
 * Fails closed: a path that cannot be resolved is reported as outside, because the caller's
 * response to `false` is to skip generating files there. Answering `true` on an unresolvable path
 * would let exactly the cross-workspace write this guard exists to prevent through whenever the
 * symlink is broken or unreadable — the case where the ownership question is least certain.
 */
internal fun trailmapDirIsInsideWorkspace(workspaceRoot: Path, trailmapDir: Path): Boolean = try {
  trailmapDir.toRealPath().startsWith(workspaceRoot.toRealPath())
} catch (e: IOException) {
  Console.info("[Trailmaps] Skipping generated files for $trailmapDir — its real path could not be resolved: $e")
  false
}
