package xyz.block.trailblaze.docs

import java.io.File

/**
 * Extracts tool names advertised by an MCP server script by grepping the common registration
 * call sites. Used by [TargetToolBaselineGenerator] so target baselines list MCP-provided tools
 * alongside Kotlin-defined ones — without spawning the MCP subprocess at doc-generation time.
 *
 * Two patterns are recognized:
 *  - `trailblaze.tool("name", ...)` — `@trailblaze/scripting` SDK
 *  - `<server>.registerTool("name", ...)` — raw `@modelcontextprotocol/sdk`
 *
 * Tradeoff: regex-driven extraction won't follow dynamic registration (`for (name of names) ...`),
 * but every `tools.ts` in the repo today uses a static literal name per call. If that changes
 * the affected file will silently underreport here; surfacing the gap in the generated baseline
 * is preferable to either (a) shipping bun + node_modules to the docs pipeline or (b) blocking
 * doc regen on a TS parser.
 */
object McpToolNameExtractor {
  private val TRAILBLAZE_TOOL_REGEX =
    Regex("""trailblaze\.tool\s*\(\s*["']([^"']+)["']""")
  private val MCP_REGISTER_TOOL_REGEX =
    Regex("""\.registerTool\s*\(\s*["']([^"']+)["']""")

  /** Returns the set of tool names registered in [scriptFile], or empty if unreadable. */
  fun extractToolNames(scriptFile: File): Set<String> {
    if (!scriptFile.isFile) return emptySet()
    val content = runCatching { scriptFile.readText() }.getOrElse { return emptySet() }
    return (TRAILBLAZE_TOOL_REGEX.findAll(content).map { it.groupValues[1] } +
      MCP_REGISTER_TOOL_REGEX.findAll(content).map { it.groupValues[1] })
      .toSortedSet()
  }

  /**
   * Resolves [scriptPath] against [baseDir] when relative. Pack-loaded manifests pre-rewrite
   * relative paths to absolute, so [baseDir] only kicks in for legacy non-pack target YAMLs
   * (and for bundled target YAMLs, which carry repo-root-relative paths verbatim).
   */
  fun resolveScript(scriptPath: String, baseDir: File): File {
    val f = File(scriptPath)
    return if (f.isAbsolute) f else File(baseDir, scriptPath)
  }
}
