package xyz.block.trailblaze.host

import xyz.block.trailblaze.config.MemoryKeyDeclaration
import xyz.block.trailblaze.config.project.ResolvedTrailmap
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry

/**
 * The memory keys a trailmap's session can meet, from every `memory:` block in its reach: its own,
 * those of the trailmaps in its `dependencies:` closure, and those of every toolset that closure
 * names in `tool_sets:` (target, library or `defaults:` platforms) plus the always-enabled ones.
 *
 * Memory is one flat namespace — a tool reads a key by name and cannot tell who declared it — so a
 * key declared in two places in one trailmap's reach is a [Conflict], never an override. Conflicts
 * fail `trailblaze check` and only warn elsewhere: a declaration never gates a trail run.
 */
object TrailmapMemoryKeys {

  /**
   * One declared key, the place that declares it (`trailmap `id`` or `toolset `id``), and the
   * trailmap it belongs to — the declaring trailmap, or the one whose `toolsets/` holds the
   * declaring toolset (the toolset's id when it came from elsewhere). Scripts reach it as
   * `memoryKeys.<trailmap>.<name>`; the key itself is still the flat [name].
   */
  data class Key(val name: String, val declaration: MemoryKeyDeclaration, val declaredBy: String, val trailmap: String)

  /** [name] is declared by every entry of [declaredBy] (two or more), all within reach of [reachedBy]. */
  data class Conflict(val name: String, val declaredBy: List<String>, val reachedBy: List<String>) {
    fun message(): String {
      val reachers = reachedBy.map { "trailmap `$it`" }
      return "memory key '$name' is declared by ${prose(declaredBy)}, and ${prose(reachers)} " +
        "${if (reachers.size == 1) "reaches" else "reach"} ${if (declaredBy.size == 2) "both" else "all of them"}. " +
        "Memory keys are global, so each is declared once: delete the copy, or move the declaration " +
        "into a dependency they share."
    }

    private fun prose(items: List<String>): String =
      if (items.size <= 2) items.joinToString(" and ") else items.dropLast(1).joinToString(", ") + " and " + items.last()
  }

  data class InReach(val keys: List<Key>, val conflicts: List<Conflict>)

  /** Every key in [trailmap]'s reach, sorted by name; a key declared twice keeps its nearest declaration. */
  fun inReach(
    trailmap: ResolvedTrailmap,
    trailmapsById: Map<String, ResolvedTrailmap>,
    catalog: List<ToolSetCatalogEntry>,
  ): InReach {
    val closure = closureIds(trailmap, trailmapsById)
    val manifests = closure.mapNotNull { id ->
      (if (id == trailmap.manifest.id) trailmap else trailmapsById[id])?.manifest
    }
    val declarations = mutableListOf<Key>()
    manifests.forEach { manifest ->
      manifest.memory.forEach { (name, key) -> declarations += Key(name, key, "trailmap `${manifest.id}`", manifest.id) }
    }
    toolSetsInReach(manifests, catalog).forEach { entry ->
      entry.memoryKeys.forEach { (name, key) ->
        declarations += Key(name, key, "toolset `${entry.id}`", entry.trailmapId ?: entry.id)
      }
    }
    val byName = declarations.groupBy { it.name }.toSortedMap()
    return InReach(
      keys = byName.values.map { it.first() },
      conflicts = byName.values.filter { it.size > 1 }.map { all ->
        Conflict(all.first().name, all.map { it.declaredBy }, listOf(trailmap.manifest.id))
      },
    )
  }

  /** The conflicts in reach of any of [trailmaps], each reported once with every trailmap that reaches it. */
  fun conflicts(
    trailmaps: List<ResolvedTrailmap>,
    trailmapsById: Map<String, ResolvedTrailmap>,
    catalog: List<ToolSetCatalogEntry>,
  ): List<Conflict> =
    trailmaps.flatMap { inReach(it, trailmapsById, catalog).conflicts }
      .groupBy { it.name to it.declaredBy.sorted() }
      .map { (_, same) -> same.first().copy(reachedBy = same.flatMap { it.reachedBy }.distinct().sorted()) }
      .sortedBy { it.name }

  /**
   * One line per toolset in reach of any of [trailmaps] whose `memory:` block is malformed, so its
   * keys are ignored. `trailblaze check` fails on these; the toolset itself still loads.
   */
  fun malformedToolSets(
    trailmaps: List<ResolvedTrailmap>,
    trailmapsById: Map<String, ResolvedTrailmap>,
    catalog: List<ToolSetCatalogEntry>,
  ): List<String> =
    trailmaps.flatMap { trailmap ->
      val manifests = closureIds(trailmap, trailmapsById).mapNotNull { id ->
        (if (id == trailmap.manifest.id) trailmap else trailmapsById[id])?.manifest
      }
      toolSetsInReach(manifests, catalog)
    }.filter { it.memoryProblems.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.id }.map { entry ->
      "toolset `${entry.id}` has an invalid memory: block, so its keys are ignored: " +
        entry.memoryProblems.joinToString("; ")
    }

  /** The toolsets [manifests] name in `tool_sets:` (target, library or `defaults:`), plus the always-enabled ones. */
  private fun toolSetsInReach(
    manifests: List<TrailblazeTrailmapManifest>,
    catalog: List<ToolSetCatalogEntry>,
  ): List<ToolSetCatalogEntry> {
    val named = manifests.flatMap { manifest ->
      listOfNotNull(manifest.target?.platforms, manifest.platforms, manifest.defaults)
        .flatMap { platforms -> platforms.values.flatMap { it.toolSets.orEmpty() } }
    }.toSet()
    return catalog.filter { it.id in named || it.alwaysEnabled }.sortedBy { it.id }
  }

  /** [trailmap]'s id, then every id reachable through `dependencies:`, nearest first. */
  private fun closureIds(trailmap: ResolvedTrailmap, trailmapsById: Map<String, ResolvedTrailmap>): Set<String> {
    val closure = linkedSetOf(trailmap.manifest.id)
    val frontier = ArrayDeque(trailmap.manifest.dependencies)
    while (frontier.isNotEmpty()) {
      val id = frontier.removeFirst()
      if (closure.add(id)) trailmapsById[id]?.manifest?.dependencies?.let(frontier::addAll)
    }
    return closure
  }
}
