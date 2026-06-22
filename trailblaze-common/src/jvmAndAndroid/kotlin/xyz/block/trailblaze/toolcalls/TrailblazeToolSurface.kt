package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass

/**
 * The three-way tool surface that every "resolved toolset" carries: class-backed tools
 * ([toolClasses]), YAML-defined tool names ([yamlToolNames]), and scripted (`.ts` / `.js`) tool
 * names ([scriptedToolNames]).
 *
 * The name-based authoring surface (a toolset's `tools:` list, a target's `target.tools:`) doesn't
 * distinguish how a tool is backed — but the runtime does: class-backed tools dispatch via their
 * [KClass], YAML-defined tools are composed from primitives at execute time, and scripted tools run
 * through the per-session scripted-tool runtime (host bun/QuickJS, on-device QuickJS bundle).
 *
 * The whole point of this interface is to give consumers **one** way to ask for the complete tool
 * surface ([allToolNames]) instead of re-unioning the three partitions by hand. Hand-rolled unions
 * are the bug that repeatedly dropped a partition: every advertise / gate / doc site that read
 * `toolClasses + yamlToolNames` but forgot `scriptedToolNames` silently lost scripted tools (e.g.
 * `openUrl`, the first scripted tool delivered via a toolset rather than a target's own `tools:`
 * list). A tool is no different for being scripted — so the surface that lists it shouldn't be
 * either.
 */
interface TrailblazeToolSurface {
  val toolClasses: Set<KClass<out TrailblazeTool>>
  val yamlToolNames: Set<ToolName>
  val scriptedToolNames: Set<ToolName>
}

/**
 * The single canonical union of all three tool backings, as [ToolName]s. This is the one place
 * class-backed tool classes, YAML-defined names, and scripted names are combined — every consumer
 * that needs "all the tools this surface exposes" should read this rather than enumerating the
 * partitions itself.
 */
val TrailblazeToolSurface.allToolNames: Set<ToolName>
  get() = buildSet {
    toolClasses.forEach { add(it.toolName()) }
    addAll(yamlToolNames)
    addAll(scriptedToolNames)
  }

/** [allToolNames] as raw strings, for the string-keyed consumers (acceptance gates, doc rows). */
val TrailblazeToolSurface.allToolNameStrings: Set<String>
  get() = allToolNames.mapTo(mutableSetOf()) { it.toolName }

/**
 * A [TrailblazeToolSurface] with no tools of any backing. The neutral element for surface-typed
 * fields — e.g. the default `excluded_tools:` surface a target carries when it opts nothing out.
 */
object EmptyTrailblazeToolSurface : TrailblazeToolSurface {
  override val toolClasses: Set<KClass<out TrailblazeTool>> = emptySet()
  override val yamlToolNames: Set<ToolName> = emptySet()
  override val scriptedToolNames: Set<ToolName> = emptySet()
}
