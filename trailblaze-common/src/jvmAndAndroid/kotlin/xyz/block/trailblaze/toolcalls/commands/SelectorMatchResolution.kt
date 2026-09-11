package xyz.block.trailblaze.toolcalls.commands

import xyz.block.trailblaze.api.MatchDescriptor
import xyz.block.trailblaze.api.MatchDescriptorBuilder
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.toMatchDescriptor

/**
 * Resolving one selector against one captured tree — shared by [FindMatchesTrailblazeTool] and
 * [FindSelectorMatchesTrailblazeTool] so the two query tools cannot drift apart on what
 * "matches" means.
 *
 * It exists as a separate object rather than a method on either tool because the sameness IS the
 * point: `findSelectorMatches([a, b])` has to answer exactly what `findMatches(a)` and
 * `findMatches(b)` would have, or callers migrating to the batched tool to save captures would be
 * silently changing their predicates at the same time.
 */
internal object SelectorMatchResolution {

  /**
   * Resolves [selector] against an already-captured [tree]. Pure computation — no device round
   * trip, which is what lets a caller answer N selectors out of one capture.
   *
   * Intentionally UNTRACED. Both callers emit a single [xyz.block.trailblaze.tracing.TrailblazeTracer]
   * span per tool CALL; tracing here would emit one span per re-capture (and per selector), which is
   * what flooded `session.trace.json` before the span was hoisted to the call site.
   *
   * **Throws** if the descriptor builder cannot path-resolve a node the resolver just matched in
   * this same tree. That is unreachable by construction — the resolver's search scope is the tree's
   * own nodes — so it can only mean a resolver or capture bug, and the alternative is worse than an
   * error: dropping the match returns a SHORT list, and a caller reads a missing match as "not on
   * screen". Every other error path in these tools means the question could not be asked, and this
   * one has to mean the same rather than be a quiet wrong answer. [logTag] names the calling tool,
   * since the two have different debugging stories.
   *
   * @param indexPaths every node's index path, from one walk of [tree]. **[Lazy] so a resolve that
   *   matches nothing never walks the tree at all** — a presence probe that comes back empty is the
   *   common case, it needs no index path, and on the polling path it would otherwise rebuild a
   *   full-tree map every 300ms to describe zero matches. A caller resolving SEVERAL selectors
   *   against one capture should pass the SAME [Lazy] to each: it depends only on the tree, so it
   *   is built at most once for the batch and only if some selector actually matched.
   */
  fun resolve(
    tree: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    selectorDesc: String,
    logTag: String,
    indexPaths: Lazy<Map<Long, List<Int>>> = lazy { MatchDescriptorBuilder.indexPaths(tree) },
  ): List<MatchDescriptor> {
    val matchedNodes: List<TrailblazeNode> = when (
      val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)
    ) {
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
    }
    // `map` on an empty list never touches `indexPaths.value`, which is the whole point of the
    // parameter being lazy — a no-match resolve costs nothing beyond the selector match itself.
    return matchedNodes.map { node ->
      node.toMatchDescriptor(indexPaths.value) ?: error(
        "[$logTag] matched nodeId=${node.nodeId} for selector=$selectorDesc, but that node is not " +
          "path-resolvable in the tree it was matched against. Refusing to return a short match " +
          "list, because a caller reads a missing match as 'not on screen'. This is a " +
          "resolver/capture bug rather than a device condition.",
      )
    }
  }
}
