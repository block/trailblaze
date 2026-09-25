package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer.DiscoveredDescriptor
import xyz.block.trailblaze.config.project.TrailmapScriptedToolFile
import xyz.block.trailblaze.scripting.ScriptedToolCatalog
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * A scripted tool that exists only in the catalog a test hands to the code under test.
 *
 * Real discovery has never heard of it, so a description that reaches the descriptors by any
 * route other than that catalog leaves the tool out. That is what lets a test tell "read the
 * request's catalog" from "walked the tree on your own" — counting the catalog's walks alone
 * cannot see a walk the code did without it.
 */
internal object CatalogOnlyScriptedTool {
  val name = ToolName("catalogOnlyTool")

  private val descriptor = DiscoveredDescriptor(
    relPath = "ghostmap/tools/catalogOnly.yaml",
    descriptor = TrailmapScriptedToolFile(
      script = "./catalogOnly.ts",
      name = name.toolName,
      description = "Exists only in the request's catalog.",
    ),
  )

  /** A catalog whose whole index is this one tool. [onWalk] runs each time the index is built. */
  fun catalog(onWalk: () -> Unit): ScriptedToolCatalog = ScriptedToolCatalog {
    onWalk()
    mapOf(name to descriptor)
  }
}
