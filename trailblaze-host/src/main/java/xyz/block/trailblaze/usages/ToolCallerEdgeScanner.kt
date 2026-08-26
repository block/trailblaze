package xyz.block.trailblaze.usages

/**
 * Derives tool→tool DISPATCH edges — "tool A's implementation invokes tool B" — by reading each
 * tool's bundled implementation and looking for other registered tools' names in it.
 *
 * ### Why the bundle, and not the source
 *
 * A tool dispatching another (`ctx.tools.someOtherTool(...)`) creates no IMPORT edge: the call goes
 * out through the host at runtime, so the callee's source never enters the caller's module graph.
 * That is precisely why [ChangedToolAnalysis]'s fingerprint — which folds in the resolved import
 * closure — cannot see it, and why a tool that is 99% a delegation to a changed tool fingerprints as
 * unchanged.
 *
 * Scanning the BUNDLE rather than the tool's own source file is what makes this transitive and
 * alias-proof, and it costs nothing extra because `--changed-since` already produces exactly this
 * artifact to fingerprint. The bundle is the tool's whole import closure flattened by esbuild with
 * real compiler semantics, so a dispatch buried in a helper five modules deep is present in the
 * bundle text just like a direct one. Renaming survives too: the bundling profile does not minify
 * (see `DaemonScriptedToolBundler.runEsbuild`), and esbuild never rewrites PROPERTY names anyway, so
 * `ctx.tools.foo(...)` reached through any number of aliases still reads as `.foo(` in the output.
 *
 * ### What it can and cannot see
 *
 * In scope: dispatch whose callee name is written down — a property call (`x.foo(...)`) or a string
 * literal in argument or index position (`callTool("foo", …)`, `tools["foo"](…)`).
 *
 * Out of scope, permanently: a name COMPUTED at runtime (`callTool(prefix + suffix)`). No static
 * analysis can resolve that — it is the JS equivalent of reflection, and an IDE's "find usages"
 * misses it for the same reason. Such an edge is invisible here and always will be.
 *
 * The bias is deliberately toward OVER-reporting: a name that merely looks like a dispatch costs a
 * redundant replay, while a missed edge is a tool change that ships with no coverage at all.
 */
object ToolCallerEdgeScanner {

  /**
   * [edges] maps a caller tool's name to the registered tools its bundle dispatches (never itself).
   * [unscannable] maps a tool whose bundle could not be produced to WHY, so it was never examined
   * as a caller — reported rather than dropped, since a caller nobody scanned looks identical to a
   * caller with no edges.
   */
  data class CallerEdges(
    val edges: Map<String, Set<String>>,
    val unscannable: Map<String, String> = emptyMap(),
  ) {
    /**
     * One warning naming every tool that went unscanned WITH its failure reason, or none.
     *
     * Named in FULL rather than counted: the whole point of the list is that a reader can check
     * whether the tool they care about is on it, and "3 tools were skipped" answers that for
     * nobody. The reason is carried per tool rather than blamed on `runtime: subprocess` up front
     * because that is only the EXPECTED cause — a permissions error, a full disk, or a missing
     * esbuild produces the same empty edge set, and silently filing those under "expected" would
     * turn a broken scan into a clean-looking report.
     */
    fun unscannableWarnings(): List<String> = if (unscannable.isEmpty()) {
      emptyList()
    } else {
      listOf(
        "not scanned for tool-to-tool dispatch because their bundle could not be produced " +
          "(expected for `runtime: subprocess` tools, which the in-process bundler cannot build; " +
          "any other reason below is a real failure worth investigating): " +
          unscannable.entries.joinToString(", ") { (name, reason) -> "$name ($reason)" } +
          ". They can still appear as a CALLEE; what is missing is any edge pointing OUT of them, " +
          "so a tool reaching a changed tool only through one of these is absent from " +
          "impactedViaCallers.",
      )
    }
  }

  /**
   * Dispatch edges for every tool in [current], as read from its bundle.
   *
   * [bundleTextOf] returns a tool's bundled implementation, or a failure carrying WHY it could not
   * be produced — the caller supplies it so this stays testable without an esbuild on PATH,
   * mirroring how [ChangedToolAnalysis.compute] takes its fingerprint function. It returns a
   * [Result] rather than a nullable string precisely so the reason survives into the warning; a
   * bare null cannot distinguish an expected `runtime: subprocess` tool from a broken toolchain.
   *
   * [changed] joins the candidate set so that a REMOVED tool still counts: a caller dispatching a
   * name that no longer exists is broken by exactly this change, which makes its trails the ones
   * most worth replaying.
   */
  fun scan(
    current: ToolSourceSnapshot,
    changed: Set<String>,
    bundleTextOf: (ToolSource, String) -> Result<String>,
  ): CallerEdges {
    if (changed.isEmpty()) return CallerEdges(emptyMap())
    val candidates = current.toolSources.keys.mapTo(mutableSetOf()) { it.name } + changed
    val edges = LinkedHashMap<String, Set<String>>()
    val unscannable = sortedMapOf<String, String>()
    for ((key, source) in current.toolSources) {
      val bundle = bundleTextOf(source, key.name).getOrElse { failure ->
        unscannable[key.name] = describeFailure(failure)
        continue
      }
      // UNION across trailmaps rather than overwrite: two trailmaps may legally declare the same
      // tool name, and a trail invoking it resolves by name alone, so an edge from either copy is
      // an edge that can fire.
      val referenced = referencedToolsIn(bundle, candidates, key.name)
      edges[key.name] = edges[key.name]?.plus(referenced) ?: referenced
    }
    return CallerEdges(edges, unscannable)
  }

  /**
   * A single line naming what went wrong. esbuild failures arrive as multi-line build logs, and a
   * warning that embeds one becomes unreadable, so this takes the first non-blank line and falls
   * back to the exception type when there is no message at all.
   */
  private fun describeFailure(failure: Throwable): String =
    failure.message?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
      ?: failure::class.simpleName
      ?: "unknown failure"

  /**
   * Registered tool names referenced by [bundle], excluding [self].
   *
   * Two passes over the text, not one per candidate: the bundle is large and the candidate set is
   * the whole workspace's tool inventory, so this collects every dispatch-shaped token once and
   * intersects with [candidates] afterwards. Intersecting with the real inventory is also what
   * keeps the loose token patterns honest — an arbitrary property call only becomes an edge when a
   * tool by that exact name actually exists.
   */
  fun referencedToolsIn(bundle: String, candidates: Set<String>, self: String): Set<String> {
    if (candidates.isEmpty()) return emptySet()
    val found = mutableSetOf<String>()
    PROPERTY_CALL_RX.findAll(bundle).mapTo(found) { it.groupValues[1] }
    STRING_ARG_RX.findAll(bundle).mapTo(found) { it.groupValues[1] }
    found.retainAll(candidates)
    found.remove(self)
    return found
  }

  /**
   * Every tool that reaches one of [changed] through one or more dispatch edges, excluding the
   * changed tools themselves.
   *
   * Transitive by fixpoint rather than a fixed depth: if A dispatches B and B dispatches a changed
   * C, then A is just as uncovered as B is. A depth limit here would be an arbitrary line across a
   * call graph nobody drew with one in mind.
   */
  fun impactedBy(changed: Set<String>, edges: Map<String, Set<String>>): List<String> {
    if (changed.isEmpty() || edges.isEmpty()) return emptyList()
    val impacted = mutableSetOf<String>()
    var frontier: Set<String> = changed
    while (frontier.isNotEmpty()) {
      val next = edges.entries
        .filter { (caller, callees) ->
          caller !in changed && caller !in impacted && callees.any { it in frontier }
        }
        .map { it.key }
        .toSet()
      if (next.isEmpty()) break
      impacted += next
      frontier = next
    }
    return impacted.sorted()
  }

  /**
   * A property call — `x.foo(`, however the receiver got its name. Only JS identifiers can be
   * reached this way; a tool name carrying a hyphen or dot is dispatched as a string instead and is
   * caught by [STRING_ARG_RX].
   */
  private val PROPERTY_CALL_RX = Regex("""\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

  /**
   * A string literal in ARGUMENT or INDEX position — `callTool("foo")`, `tools["foo"]`. Requiring a
   * trailing `,`, `)` or `]` is what separates a dispatch from prose: a tool name mentioned inside a
   * description or an error message does not sit in that position.
   */
  private val STRING_ARG_RX = Regex("""["']([A-Za-z_][A-Za-z0-9_.\-]*)["']\s*[,)\]]""")
}
