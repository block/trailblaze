package xyz.block.trailblaze.api

/**
 * Resolves [TrailblazeNodeSelector] against a [TrailblazeNode] tree.
 *
 * This is the [TrailblazeNode]-native equivalent of Maestro's Orchestra `buildFilter`.
 * It operates directly on the rich [DriverNodeDetail] properties without going through
 * any compatibility layer.
 *
 * ## Resolution strategy
 * 1. Flatten the search scope (respecting [TrailblazeNodeSelector.childOf] for scoped searches)
 * 2. Apply driver-specific property matching via [DriverNodeMatch]
 * 3. Apply spatial relationship predicates (above, below, leftOf, rightOf)
 * 4. Apply hierarchy predicates (containsChild, containsDescendants)
 * 5. Sort by position (top-to-bottom, left-to-right)
 * 6. Apply index if specified
 *
 * @see TrailblazeNodeSelector for the selector model
 */
object TrailblazeNodeSelectorResolver {

  /** Maximum nesting depth for recursive resolve() calls (spatial/hierarchy selectors). */
  private const val MAX_RESOLVE_DEPTH = 10

  /** XCUIElementType names whose AXLabel/AXValue carries the placeholder while the field is empty. */
  private val IOS_TEXT_INPUT_TYPES = setOf("TextField", "SecureTextField", "SearchField", "TextView")

  /**
   * Maestro-era iOS class names accepted per AXe element type by the `iosMaestro` → `iosAxe`
   * compatibility bridge. Maestro's iOS tree reports the *label view* (`LabelView`,
   * `UIButtonLabel`, `UITextFieldLabel`) or the UIKit class (`UILabel`) where AXe reports the
   * semantic element type — so a recorded `classNameRegex: LabelView` must still match the AXe
   * `StaticText` node that carries the same text. Tab-bar/nav items and buttons surface on an
   * AXe tree as `Button` carrying the label directly (no separate StaticText child), so the
   * label-view classes are accepted there too. Curated from the class names recorded
   * selectors actually carry; unmapped custom classes fail the constraint (and fall to the
   * recorded coordinate fallback at replay).
   */
  private val MAESTRO_IOS_CLASS_ALIASES: Map<String, List<String>> = mapOf(
    "StaticText" to listOf("UILabel", "LabelView"),
    "Button" to listOf("UIButton", "UIButtonLabel", "UILabel", "LabelView"),
    "TextField" to listOf("UITextField", "UITextFieldLabel"),
    "SecureTextField" to listOf("UISecureTextField", "UITextFieldLabel"),
  )

  /** Result of an element resolution attempt. */
  sealed interface ResolveResult {
    /** Exactly one element matched — the success case for interactions. */
    data class SingleMatch(val node: TrailblazeNode) : ResolveResult

    /** No elements matched the selector. */
    data class NoMatch(val selector: TrailblazeNodeSelector) : ResolveResult

    /** Multiple elements matched — the selector is ambiguous. */
    data class MultipleMatches(
      val nodes: List<TrailblazeNode>,
      val selector: TrailblazeNodeSelector,
    ) : ResolveResult
  }

  /**
   * Resolves a [TrailblazeNodeSelector] against the tree rooted at [root].
   *
   * When [target] is supplied, the selector is expanded once via
   * [SelectorTemplating.expand] before resolution — every `{{target.appId}}` placeholder
   * inside the selector tree (including nested spatial/hierarchy sub-selectors) is
   * substituted before any regex compile happens. Callers that hold session context
   * (agent / matcher / executor) thread it through here so the resolver is the single
   * site that knows about templating; callers without context (inspector UI, ad-hoc
   * selector evaluation, unit-test fixtures with literal selectors) pass null.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param selector The selector to match
   * @param target Optional template context for `{{target.appId}}` substitution
   * @return [ResolveResult] indicating zero, one, or multiple matches
   */
  fun resolve(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    target: TargetTemplateContext?,
  ): ResolveResult {
    val expanded = if (target != null) SelectorTemplating.expand(selector, target) else selector
    return resolve(root, expanded, depth = 0)
  }

  /**
   * 2-arg overload preserving the wire/binary signature of the previously-published
   * `resolve(root, selector)` method. `@JvmOverloads` would be the idiomatic JVM-only
   * answer, but this object lives in `commonMain` and `kotlin.jvm.JvmOverloads` isn't
   * available on the wasmJs target — the explicit overload is multiplatform-safe.
   */
  fun resolve(root: TrailblazeNode, selector: TrailblazeNodeSelector): ResolveResult =
    resolve(root, selector, target = null)

  private fun resolve(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    depth: Int,
  ): ResolveResult {
    if (depth > MAX_RESOLVE_DEPTH) return ResolveResult.NoMatch(selector)

    // Step 1: Determine search scope via childOf (exclude parent itself — only descendants)
    val searchScope = selector.childOf?.let { childOfSelector ->
      when (val parentResult = resolve(root, childOfSelector, depth + 1)) {
        is ResolveResult.SingleMatch -> parentResult.node.aggregate().drop(1)
        is ResolveResult.MultipleMatches -> {
          // The scope is a SET of nodes, but a flat union duplicates: when one anchor match
          // contains another (an ancestor chain, which the estate bridge's descendant-shaped
          // containsChild produces routinely), every node under the inner anchor enters the
          // union once per enclosing anchor, and each duplicate then counts as its own match
          // downstream — one widget reported as dozens of "elements". In a tree, two nodes'
          // descendant sets overlap ONLY when one contains the other, so keeping just the
          // anchors no other anchor contains yields the identical union, duplicate-free.
          val anchors = parentResult.nodes
          // Each anchor's descendants ONCE. `aggregate()` walks the whole subtree, and the
          // containment scan below asks about every ordered pair, so recomputing it inside the
          // scan makes a deep tree with many anchors quadratic in full subtree walks.
          val descendants = anchors.map { anchor ->
            anchor.aggregate().drop(1).mapTo(ArrayList<TrailblazeNode>()) { it }
          }
          anchors
            .filterIndexed { candidateIndex, candidate ->
              anchors.indices.none { otherIndex ->
                otherIndex != candidateIndex &&
                  descendants[otherIndex].any { it === candidate }
              }
            }
            // Identity-distinct: an anchor list that itself carried duplicates (a nested childOf
            // resolved before this fix existed cannot, but defensive) must not reintroduce them.
            .let { outer -> outer.filterIndexed { i, n -> outer.subList(0, i).none { it === n } } }
            .flatMap { it.aggregate().drop(1) }
        }
        is ResolveResult.NoMatch -> return ResolveResult.NoMatch(selector)
      }
    } ?: root.aggregate()

    // Step 2: Apply driver match + spatial + hierarchy predicates, sort by position
    val matched = searchScope
      .filter { node -> matchesSelector(node, selector, root, depth) }
      // Top level only: the outer result names the widget acted on, so one widget must be one
      // match. An anchor resolved mid-recursion (childOf, spatial) must NOT collapse — childOf
      // unions every match's descendants into the search scope, and collapsing an ancestor chain
      // to its innermost node there would shrink a merged container's scope to a text wrapper.
      // Projected-shape selectors only: the collapse undoes a density mismatch, so it applies
      // exactly where one exists. A bare positional selector deliberately enumerates every node,
      // a native in-process selector was authored against this tree's own density, and a
      // projected selector on a projected tree (the accessibility driver's own path) has no
      // mismatch to undo — each of those keeps its recorded match count untouched.
      .let { if (depth == 0 && selector.projectsContent()) collapseNestedDuplicates(it) else it }
      .sortedWith(
        compareBy(
          { it.bounds?.top ?: Int.MAX_VALUE },
          { it.bounds?.left ?: Int.MAX_VALUE },
        ),
      )

    // Step 3: Apply index if specified
    val finalResults = selector.index?.let { idx ->
      if (idx in matched.indices) listOf(matched[idx]) else emptyList()
    } ?: matched

    return when (finalResults.size) {
      0 -> ResolveResult.NoMatch(selector)
      1 -> ResolveResult.SingleMatch(finalResults.first())
      else -> ResolveResult.MultipleMatches(finalResults, selector)
    }
  }

  /**
   * Convenience: resolves and returns the center point for tapping, or null.
   * Uses the first match if multiple are found.
   *
   * See [resolve] for the [target] parameter semantics.
   */
  fun resolveToCenter(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    target: TargetTemplateContext?,
  ): Pair<Int, Int>? = when (val result = resolve(root, selector, target)) {
    is ResolveResult.SingleMatch -> result.node.centerPoint()
    is ResolveResult.MultipleMatches -> result.nodes.first().centerPoint()
    is ResolveResult.NoMatch -> null
  }

  /** See [resolve] — same multiplatform-safe explicit-overload rationale. */
  fun resolveToCenter(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Pair<Int, Int>? = resolveToCenter(root, selector, target = null)

  // --- Private matching logic ---

  /**
   * Returns true if [node] matches all predicates in [selector] (excluding childOf and index).
   *
   * The [depth] parameter guards against unbounded recursion from nested containsChild/
   * containsDescendants selectors. Each recursive call increments depth.
   */
  private fun matchesSelector(
    node: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    root: TrailblazeNode,
    depth: Int = 0,
  ): Boolean {
    if (depth > MAX_RESOLVE_DEPTH) return false

    // Container-chrome guard (iOS AXe): a text-driven selector never resolves to the
    // screen-sized Application/Window chrome — see isIosAxeContainerChrome. Applied at the
    // selector level (not per driver-match) so hierarchy shapes like a bare
    // `containsChild: {textRegex: …}` can't match the chrome via a text-bearing descendant.
    if (isExcludedAsContainerChrome(node, selector)) return false

    // Driver-specific property matching
    selector.driverMatch?.let { match ->
      if (!matchesDriverDetail(node.driverDetail, match)) return false
    }

    // Spatial relationships
    val spatialChecks = listOf(
      selector.below to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.top >= anchor.bottom },
      selector.above to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.bottom <= anchor.top },
      selector.leftOf to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.right <= anchor.left },
      selector.rightOf to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.left >= anchor.right },
    )
    for ((spatialSelector, predicate) in spatialChecks) {
      if (spatialSelector == null) continue
      val anchorBounds = resolveFirstBounds(root, spatialSelector, depth) ?: return false
      val nodeBounds = node.bounds ?: return false
      if (!predicate(anchorBounds, nodeBounds)) return false
    }

    // Hierarchy: containsChild — depth incremented to guard against nested containsChild chains
    selector.containsChild?.let { childSelector ->
      val directChildMatches =
        node.children.any { child -> matchesSelector(child, childSelector, root, depth + 1) }
      // Densification rule for bridged evaluation: "child" in a selector recorded against a
      // PROJECTED tree (accessibility, UiAutomator) means "the node directly under this one
      // there" — and the dense in-process tree inserts wrapper nodes the projection pruned, so
      // the same relationship is descendant-shaped here. Relaxing to descendants is exactly the
      // densification, and only the densification: it applies only when a projected-shape child
      // selector is evaluated against an in-process node, so native `androidView:`/`compose:`
      // selectors (authored against this tree) keep strict direct-child semantics.
      val bridgedDescendantMatches = !directChildMatches &&
        isProjectedAndroidShape(childSelector.driverMatch) &&
        isInProcessAndroidDetail(node.driverDetail) &&
        node.aggregate().drop(1).any { desc -> matchesSelector(desc, childSelector, root, depth + 1) }
      if (!directChildMatches && !bridgedDescendantMatches) return false
    }

    // Hierarchy: containsDescendants — must match ALL, depth incremented
    selector.containsDescendants?.let { descendantSelectors ->
      val allDescendants = node.aggregate().drop(1) // exclude self
      val allMatch = descendantSelectors.all { descendantSelector ->
        allDescendants.any { desc -> matchesSelector(desc, descendantSelector, root, depth + 1) }
      }
      if (!allMatch) return false
    }

    return true
  }

  /**
   * Whether the selector's content constraint was recorded against a PROJECTED Android tree
   * (accessibility or UiAutomator): its own driver match is a projected shape, or it is a
   * structural selector whose containsChild constraint carries one. That is the shape whose
   * density can mismatch the tree it is evaluated on — a bare positional selector says nothing
   * about content, and a native in-process shape was authored against the dense tree itself.
   */
  private fun TrailblazeNodeSelector.projectsContent(): Boolean =
    isProjectedAndroidShape(driverMatch) ||
      (driverMatch == null && containsChild?.let { isProjectedAndroidShape(it.driverMatch) } == true)

  /** A selector shape recorded from a projected Android tree (accessibility or UiAutomator). */
  private fun isProjectedAndroidShape(match: DriverNodeMatch?): Boolean =
    match is DriverNodeMatch.AndroidAccessibility || match is DriverNodeMatch.AndroidMaestro

  /** A node from the ANDROID_TEST driver's dense in-process hybrid tree. */
  private fun isInProcessAndroidDetail(detail: DriverNodeDetail): Boolean =
    detail is DriverNodeDetail.AndroidView || detail is DriverNodeDetail.Compose

  /**
   * Collapses matches that are one widget seen at two tree densities into the innermost node.
   *
   * A projected tree (accessibility, UiAutomator) MERGES a widget into one node — a clickable
   * container whose content description and its child text both read "More" is a single node
   * there. The dense in-process tree keeps them apart, so the same selector matches both, and a
   * strict resolver reports an ambiguity the recording's tree could never produce. When one match
   * is an ancestor of another, they are the same widget, not two candidates: keep the innermost
   * (its bounds are the tightest), and let genuinely distinct widgets stay ambiguous.
   *
   * Runs BEFORE the index step so a recorded `index:` counts widgets the way the projected tree
   * did — one per widget — rather than shifting by however many wrappers the dense tree adds.
   * Strictly narrowing: it can only reduce a match set, never admit a node that did not match.
   *
   * Per-pair as well as per-selector, a match only ever collapses into an IN-PROCESS descendant:
   * on a projected tree — the accessibility driver evaluating its own recordings — an ancestor
   * and descendant matching the same predicate are genuinely two nodes of that tree, and recorded
   * `index:` selectors counted both.
   */
  private fun collapseNestedDuplicates(matched: List<TrailblazeNode>): List<TrailblazeNode> {
    if (matched.size < 2) return matched
    return matched.filter { candidate ->
      // Drop a match that contains another match: the inner one is the same widget, seen closer.
      val descendants = candidate.aggregate().drop(1)
      matched.none { other ->
        other !== candidate &&
          isInProcessAndroidDetail(other.driverDetail) &&
          descendants.any { it === other }
      }
    }
  }

  /** Resolves the first match's bounds from a selector. */
  private fun resolveFirstBounds(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    depth: Int,
  ): TrailblazeNode.Bounds? = when (val result = resolve(root, selector, depth + 1)) {
    is ResolveResult.SingleMatch -> result.node.bounds
    is ResolveResult.MultipleMatches -> result.nodes.firstOrNull()?.bounds
    is ResolveResult.NoMatch -> null
  }

  // --- Driver-specific matching ---

  /** Dispatches to the appropriate driver-specific matcher. */
  private fun matchesDriverDetail(
    detail: DriverNodeDetail,
    match: DriverNodeMatch,
  ): Boolean = when (match) {
    is DriverNodeMatch.AndroidAccessibility ->
      when (detail) {
        is DriverNodeDetail.AndroidAccessibility -> matchesAndroidAccessibility(detail, match)
        // Canonical-selector bridge: an a11y-shaped selector — the one shape every Android
        // driver can evaluate — also resolves against the ANDROID_TEST driver's in-process
        // hybrid tree. See matchesAndroidAccessibilityAgainstView / ...AgainstCompose.
        is DriverNodeDetail.AndroidView -> matchesAndroidAccessibilityAgainstView(detail, match)
        is DriverNodeDetail.Compose -> matchesAndroidAccessibilityAgainstCompose(detail, match)
        else -> false
      }
    is DriverNodeMatch.AndroidView ->
      detail is DriverNodeDetail.AndroidView && matchesAndroidView(detail, match)
    is DriverNodeMatch.AndroidMaestro ->
      when (detail) {
        is DriverNodeDetail.AndroidMaestro -> matchesAndroidMaestro(detail, match)
        // Estate bridge: the instrumentation-driver recordings this repo already holds are
        // Maestro-shaped, and they must replay on the in-process hybrid tree without being
        // re-recorded. See matchesAndroidMaestroAgainstView / ...AgainstCompose.
        is DriverNodeDetail.AndroidView -> matchesAndroidMaestroAgainstView(detail, match)
        is DriverNodeDetail.Compose -> matchesAndroidMaestroAgainstCompose(detail, match)
        else -> false
      }
    is DriverNodeMatch.Web ->
      detail is DriverNodeDetail.Web && matchesWeb(detail, match)
    is DriverNodeMatch.Compose ->
      detail is DriverNodeDetail.Compose && matchesCompose(detail, match)
    is DriverNodeMatch.IosMaestro ->
      when (detail) {
        is DriverNodeDetail.IosMaestro -> matchesIosMaestro(detail, match)
        // Cross-dialect bridge: a trail recorded under the legacy Maestro iOS driver still
        // resolves when replayed against the newer AXe driver. See matchesIosMaestroAgainstAxe.
        is DriverNodeDetail.IosAxe -> matchesIosMaestroAgainstAxe(detail, match)
        else -> false
      }
    is DriverNodeMatch.IosAxe ->
      detail is DriverNodeDetail.IosAxe && matchesIosAxe(detail, match)
  }

  private fun matchesAndroidAccessibility(
    detail: DriverNodeDetail.AndroidAccessibility,
    match: DriverNodeMatch.AndroidAccessibility,
  ): Boolean {
    if (!requirePattern(match.classNameRegex, detail.className)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId)) return false
    if (!requireEqual(match.uniqueId, detail.uniqueId)) return false
    if (!requirePattern(match.composeTestTagRegex, detail.composeTestTag)) return false
    // textRegex matches resolveText() (text > hintText > contentDescription)
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText)) return false
    if (!requirePattern(match.labeledByTextRegex, detail.labeledByText)) return false
    if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription)) return false
    if (!requirePattern(match.paneTitleRegex, detail.paneTitle)) return false
    if (!requirePattern(match.roleDescriptionRegex, detail.roleDescription)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isClickable, detail.isClickable)) return false
    if (!requireEqual(match.isCheckable, detail.isCheckable)) return false
    if (!requireEqual(match.isChecked, detail.isChecked)) return false
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isEditable, detail.isEditable)) return false
    if (!requireEqual(match.isScrollable, detail.isScrollable)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    if (!requireEqual(match.isHeading, detail.isHeading)) return false
    if (!requireEqual(match.isMultiLine, detail.isMultiLine)) return false
    if (!requireEqual(match.inputType, detail.inputType)) return false
    match.collectionItemRowIndex?.let { row ->
      if (detail.collectionItemInfo?.rowIndex != row) return false
    }
    match.collectionItemColumnIndex?.let { col ->
      if (detail.collectionItemInfo?.columnIndex != col) return false
    }
    return true
  }

  /**
   * Canonical-selector bridge, View half: evaluates an [DriverNodeMatch.AndroidAccessibility]
   * selector against a [DriverNodeDetail.AndroidView] node from the ANDROID_TEST driver's
   * in-process hybrid tree.
   *
   * The a11y shape is the CANONICAL selector: it is the one shape the out-of-process
   * accessibility driver can always evaluate, so a trail recorded in it replays on either
   * driver — in-process for speed where the signature match allows it, accessibility
   * everywhere else. This bridge is what lets the in-process driver accept that shape without
   * trails carrying a per-backend selector (the `androidView`/`compose` keys leak which
   * toolkit drew a widget — an app refactor from Views to Compose breaks such a trail on a
   * screen that reads identically).
   *
   * Field mapping is strict, mirroring [matchesIosMaestroAgainstAxe]'s rule: a predicate the
   * View tree cannot answer FAILS the constraint (and falls to the recorded-coordinate
   * fallback at replay) rather than being silently ignored — a selector must never match more
   * loosely here than it would on the tree it was authored against. Unanswerable on a View
   * node: [DriverNodeMatch.AndroidAccessibility.uniqueId], `composeTestTagRegex`,
   * `labeledByTextRegex`, `paneTitleRegex`, `roleDescriptionRegex`, `isHeading` and
   * `isMultiLine`. The collection row/column predicates ARE answered — the View collector reads
   * the same `CollectionItemInfo` the a11y tree publishes, which is what a grid-position selector
   * needs, since a placeholder tile has no other distinguishing property. `isCheckable` maps to
   * the View convention that a non-`Checkable` view reports `isChecked == null`.
   *
   * Dialect is [MatchDialect.NATIVE] on both sides of the bridge — the a11y shape is strict
   * (regex-or-exact, case-sensitive), and it stays strict here.
   */
  private fun matchesAndroidAccessibilityAgainstView(
    detail: DriverNodeDetail.AndroidView,
    match: DriverNodeMatch.AndroidAccessibility,
  ): Boolean {
    // Predicates the View tree cannot answer: set means no match, never silently ignored.
    if (match.uniqueId != null) return false
    if (match.composeTestTagRegex != null) return false
    if (match.labeledByTextRegex != null) return false
    if (match.paneTitleRegex != null) return false
    if (match.roleDescriptionRegex != null) return false
    if (match.isHeading != null) return false
    if (match.isMultiLine != null) return false

    // Collection position IS answerable here: the collector reads the same CollectionItemInfo the
    // a11y tree publishes. Strict all the same — a view that is not a collection item reports null
    // and so fails a constraint naming a position, rather than matching it loosely.
    match.collectionItemRowIndex?.let { if (detail.collectionItemRowIndex != it) return false }
    match.collectionItemColumnIndex?.let { if (detail.collectionItemColumnIndex != it) return false }

    // The a11y class name, not the runtime one: a canonical selector was recorded against a tree
    // that only ever published `View.getAccessibilityClassName()`, so `android.view.View` there
    // means "a plain view" and matches nothing against `com.example.…SomeRow`. Falls back to the
    // runtime class for a tree whose collector does not report the a11y name.
    if (!requirePattern(match.classNameRegex, detail.accessibilityClassName ?: detail.className)) {
      return false
    }
    if (!requirePattern(match.resourceIdRegex, detail.resourceId)) return false
    // Same text-resolution contract as the a11y tree: text > hintText > contentDescription.
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText)) return false
    if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isClickable, detail.isClickable)) return false
    // Checkability is expressed on the View shape as isChecked's nullability.
    if (!requireEqual(match.isCheckable, detail.isChecked != null)) return false
    match.isChecked?.let { if (detail.isChecked != it) return false }
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isEditable, detail.isEditable)) return false
    if (!requireEqual(match.isScrollable, detail.isScrollable)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    if (!requireEqual(match.inputType, detail.inputType)) return false
    return true
  }

  /**
   * Canonical-selector bridge, Compose half: evaluates an
   * [DriverNodeMatch.AndroidAccessibility] selector against a [DriverNodeDetail.Compose] node.
   * See [matchesAndroidAccessibilityAgainstView] for why the bridge exists and the strictness
   * rule; the notes here are the Compose-specific mappings.
   *
   * - `resourceIdRegex` matches [DriverNodeDetail.Compose.testTag]: with
   *   `testTagsAsResourceId` enabled the a11y tree reports a Compose node's testTag verbatim
   *   as its `viewIdResourceName`, so a canonical selector recorded from the a11y tree names
   *   the same string this tree calls the testTag.
   * - `isClickable`/`isScrollable`/`isEditable` map to the semantics actions
   *   (`hasClickAction`/`hasScrollAction`/`hasSetTextAction`) — the same sources Compose's
   *   own accessibility delegate projects those a11y booleans from.
   * - Checkability maps to [DriverNodeDetail.Compose.toggleableState]: non-null means
   *   checkable, and `"On"` means checked.
   * - The a11y tree is MERGED and this tree is UNMERGED, so a text selector that matches a
   *   merged container on the a11y tree matches the descendant Text node here. That is the
   *   intended outcome: interactions already ascend from a matched node to its action
   *   ancestor, so both drivers act on the same widget.
   *
   * - `classNameRegex` matches [DriverNodeDetail.Compose.accessibilityClassName], the class the
   *   a11y projection fabricates for a semantics node — which is the only class name a recording
   *   made against that tree can be naming. A collector that projects none leaves the field null
   *   and the predicate declines, as it did for every Compose node before the projection existed.
   *
   * Unanswerable on a Compose node (set means no match): `uniqueId`, `hintTextRegex`,
   * `labeledByTextRegex`, `roleDescriptionRegex`, `isMultiLine`, and `inputType`.
   */
  private fun matchesAndroidAccessibilityAgainstCompose(
    detail: DriverNodeDetail.Compose,
    match: DriverNodeMatch.AndroidAccessibility,
  ): Boolean {
    // Predicates the Compose semantics tree cannot answer: set means no match.
    if (match.uniqueId != null) return false
    if (match.hintTextRegex != null) return false
    if (match.labeledByTextRegex != null) return false
    if (match.roleDescriptionRegex != null) return false
    if (match.isMultiLine != null) return false
    if (match.inputType != null) return false

    // Declines rather than matches when the collector projected nothing: a null here means "this
    // tree cannot answer the question", and treating that as a match would let a className
    // selector select every Compose node on the screen.
    match.classNameRegex?.let { pattern ->
      if (!requirePattern(pattern, detail.accessibilityClassName ?: return false)) return false
    }
    if (!requirePattern(match.resourceIdRegex, detail.testTag)) return false
    if (!requirePattern(match.composeTestTagRegex, detail.testTag)) return false
    // Compose resolveText: editableText > text > contentDescription.
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription)) return false
    if (!requirePattern(match.paneTitleRegex, detail.paneTitle)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isClickable, detail.hasClickAction)) return false
    if (!requireEqual(match.isCheckable, detail.toggleableState != null)) return false
    match.isChecked?.let { if ((detail.toggleableState == "On") != it) return false }
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isEditable, detail.hasSetTextAction)) return false
    if (!requireEqual(match.isScrollable, detail.hasScrollAction)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    if (!requireEqual(match.isHeading, detail.isHeading)) return false
    match.collectionItemRowIndex?.let { if (detail.collectionItemRowIndex != it) return false }
    match.collectionItemColumnIndex?.let { if (detail.collectionItemColumnIndex != it) return false }
    return true
  }

  /**
   * Matches a native View-tree selector. Deliberately uses the default [MatchDialect.NATIVE]:
   * `androidView` selectors are authored against a tree captured in-process from the real view
   * objects, never round-tripped through Maestro, so they get strict case-sensitive semantics
   * rather than the lenient dialect [matchesAndroidMaestro] must preserve.
   */
  private fun matchesAndroidView(
    detail: DriverNodeDetail.AndroidView,
    match: DriverNodeMatch.AndroidView,
  ): Boolean {
    if (!requirePattern(match.classNameRegex, detail.className)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId)) return false
    if (!requirePattern(match.tagRegex, detail.tag)) return false
    // textRegex matches resolveText() (text > hintText > contentDescription)
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText)) return false
    if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription)) return false
    if (!requirePattern(match.errorTextRegex, detail.errorText)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isClickable, detail.isClickable)) return false
    // A non-Checkable view has isChecked == null, so `isChecked: false` matches only views that
    // are checkable and currently unchecked — not every view on the screen.
    if (!requireEqual(match.isChecked, detail.isChecked)) return false
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isEditable, detail.isEditable)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    if (!requireEqual(match.inputType, detail.inputType)) return false
    return true
  }

  private fun matchesAndroidMaestro(
    detail: DriverNodeDetail.AndroidMaestro,
    match: DriverNodeMatch.AndroidMaestro,
  ): Boolean {
    // Maestro-shape selectors keep the semantics Maestro's Orchestra evaluated them with.
    val dialect = MatchDialect.MAESTRO
    if (!requirePattern(match.textRegex, detail.resolveText(), dialect)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId, dialect)) return false
    if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText, dialect)) return false
    if (!requirePattern(match.classNameRegex, detail.className, dialect)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText, dialect)) return false
    if (!requireEqual(match.clickable, detail.clickable)) return false
    if (!requireEqual(match.enabled, detail.enabled)) return false
    if (!requireEqual(match.focused, detail.focused)) return false
    if (!requireEqual(match.checked, detail.checked)) return false
    if (!requireEqual(match.selected, detail.selected)) return false
    return true
  }

  /**
   * Estate bridge, View half: evaluates a [DriverNodeMatch.AndroidMaestro] selector — as recorded
   * under the UiAutomator-backed instrumentation driver — against a [DriverNodeDetail.AndroidView]
   * node from the ANDROID_TEST driver's in-process hybrid tree.
   *
   * The field mapping is derived from what Maestro's own Android tree reports, not by analogy to
   * any other bridge: UiAutomator's `text` attribute is the accessibility projection of the same
   * View this tree holds directly, `resourceId` is `viewIdResourceName`, and `accessibilityText`
   * is the content description. Maestro's `text` filter matches the text OR the accessibility
   * text (its `TreeNode` cluster folds them — `resolveText()` on the Maestro shape is
   * `text ?: hintText ?: accessibilityText`), so `textRegex` here accepts any of the three text
   * carriers rather than only the first non-null one. [MatchDialect.MAESTRO] throughout: the
   * selector was authored under Orchestra's lenient semantics (case-insensitive, dotAll, invalid
   * pattern degrades to a literal), and that is what it must keep meaning here.
   *
   * `classNameRegex` reads `accessibilityClassName ?: className`, the same pair the canonical
   * bridge uses, because Maestro's tree takes its class from `AccessibilityNodeInfo.className` —
   * so the recorded value is the ACCESSIBILITY class. A custom subclass that reports
   * `android.widget.TextView` to accessibility while its runtime class is
   * `com.example.…SomethingView` was recorded as the former, and comparing the latter resolves
   * to no element at all.
   *
   * State predicates map directly onto the View properties the accessibility projection reads
   * them from. `checked` treats a non-checkable View (`isChecked == null`) as unchecked, because
   * that is what UiAutomator reports for it.
   */
  private fun matchesAndroidMaestroAgainstView(
    detail: DriverNodeDetail.AndroidView,
    match: DriverNodeMatch.AndroidMaestro,
  ): Boolean {
    val dialect = MatchDialect.MAESTRO
    match.textRegex?.let { pattern ->
      if (!matchesAnyPattern(pattern, dialect, detail.text, detail.hintText, detail.contentDescription)) {
        return false
      }
    }
    if (!requirePattern(match.resourceIdRegex, detail.resourceId, dialect)) return false
    if (!requirePattern(match.accessibilityTextRegex, detail.contentDescription, dialect)) return false
    if (!requirePattern(
        match.classNameRegex,
        detail.accessibilityClassName ?: detail.className,
        dialect,
      )
    ) {
      return false
    }
    if (!requirePattern(match.hintTextRegex, detail.hintText, dialect)) return false
    if (!requireEqual(match.clickable, detail.isClickable)) return false
    if (!requireEqual(match.enabled, detail.isEnabled)) return false
    if (!requireEqual(match.focused, detail.isFocused)) return false
    match.checked?.let { if ((detail.isChecked ?: false) != it) return false }
    if (!requireEqual(match.selected, detail.isSelected)) return false
    return true
  }

  /**
   * Estate bridge, Compose half. See [matchesAndroidMaestroAgainstView] for the dialect rule and
   * where the mapping comes from; the notes here are Compose-specific.
   *
   * - `resourceIdRegex` matches [DriverNodeDetail.Compose.testTag]: `testTagsAsResourceId` is how
   *   UiAutomator saw a Compose node's tag when the selector was recorded, so the recorded
   *   "resource id" IS the testTag.
   * - `textRegex` accepts the editable text, the static text, or the content description — the
   *   cluster Maestro's `text` filter folds on its own tree.
   * - State predicates map to the semantics the accessibility projection derives them from:
   *   `clickable` → `hasClickAction`, `checked` → `toggleableState == "On"`.
   *
   * `classNameRegex` and `hintTextRegex` FAIL the constraint: a semantics node has no runtime
   * class (UiAutomator fabricates one for it) and no hint, so a selector pinning either cannot be
   * faithfully evaluated here — matching nothing is louder and safer than silently dropping a
   * recorded constraint.
   */
  private fun matchesAndroidMaestroAgainstCompose(
    detail: DriverNodeDetail.Compose,
    match: DriverNodeMatch.AndroidMaestro,
  ): Boolean {
    if (match.classNameRegex != null) return false
    if (match.hintTextRegex != null) return false

    val dialect = MatchDialect.MAESTRO
    match.textRegex?.let { pattern ->
      if (!matchesAnyPattern(pattern, dialect, detail.editableText, detail.text, detail.contentDescription)) {
        return false
      }
    }
    if (!requirePattern(match.resourceIdRegex, detail.testTag, dialect)) return false
    if (!requirePattern(match.accessibilityTextRegex, detail.contentDescription, dialect)) return false
    if (!requireEqual(match.clickable, detail.hasClickAction)) return false
    if (!requireEqual(match.enabled, detail.isEnabled)) return false
    if (!requireEqual(match.focused, detail.isFocused)) return false
    match.checked?.let { if ((detail.toggleableState == "On") != it) return false }
    if (!requireEqual(match.selected, detail.isSelected)) return false
    return true
  }

  private fun matchesWeb(
    detail: DriverNodeDetail.Web,
    match: DriverNodeMatch.Web,
  ): Boolean {
    if (!requireEqual(match.ariaRole, detail.ariaRole)) return false
    if (!requirePattern(match.ariaNameRegex, detail.ariaName)) return false
    if (!requirePattern(match.ariaDescriptorRegex, detail.ariaDescriptor)) return false
    if (!requireEqual(match.headingLevel, detail.headingLevel)) return false
    if (!requireEqual(match.cssSelector, detail.cssSelector)) return false
    if (!requireEqual(match.dataTestId, detail.dataTestId)) return false
    if (!requireEqual(match.nthIndex, detail.nthIndex)) return false
    return true
  }

  private fun matchesCompose(
    detail: DriverNodeDetail.Compose,
    match: DriverNodeMatch.Compose,
  ): Boolean {
    if (!requireEqual(match.testTag, detail.testTag)) return false
    if (!requireEqual(match.role, detail.role)) return false
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.editableTextRegex, detail.editableText)) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requireEqual(match.toggleableState, detail.toggleableState)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    if (!requireEqual(match.collectionItemRowIndex, detail.collectionItemRowIndex)) return false
    if (!requireEqual(match.collectionItemColumnIndex, detail.collectionItemColumnIndex)) return false
    if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription)) return false
    if (!requireEqual(match.isHeading, detail.isHeading)) return false
    if (!requirePattern(match.paneTitleRegex, detail.paneTitle)) return false
    if (!requireEqual(match.isDialog, detail.isDialog)) return false
    if (!requireEqual(match.isPopup, detail.isPopup)) return false
    if (!requirePattern(match.errorTextRegex, detail.errorText)) return false
    if (!requireEqual(match.hasSetTextAction, detail.hasSetTextAction)) return false
    return true
  }

  private fun matchesIosMaestro(
    detail: DriverNodeDetail.IosMaestro,
    match: DriverNodeMatch.IosMaestro,
  ): Boolean {
    // Maestro-shape selectors keep the semantics Maestro's Orchestra evaluated them with.
    val dialect = MatchDialect.MAESTRO
    if (!requirePattern(match.textRegex, detail.resolveText(), dialect)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId, dialect)) return false
    if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText, dialect)) return false
    if (!requirePattern(match.classNameRegex, detail.className, dialect)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText, dialect)) return false
    if (!requireEqual(match.focused, detail.focused)) return false
    if (!requireEqual(match.selected, detail.selected)) return false
    return true
  }

  /**
   * True for the screen-sized container chrome on an AXe tree — the `AXApplication` root and
   * its `AXWindow`s. Their AXLabel is the app name (the Settings app's root is labeled
   * "Settings"), so a text-driven selector must never resolve to them: the match would be
   * technically correct yet tap the container's center — the middle of the screen — instead
   * of the intended element, and sometimes still "pass". Mirrored in the TS resolver
   * (`isIosAxeContainerChrome` in `sdks/typescript/src/matcher/resolver.ts`).
   */
  private fun isIosAxeContainerChrome(detail: DriverNodeDetail.IosAxe): Boolean =
    detail.type == "Application" || detail.type == "Window" ||
      detail.role == "AXApplication" || detail.role == "AXWindow"

  /**
   * True when [node] is AXe container chrome that a text-driven [selector] must not resolve
   * to. Text constraints are collected from the whole selector tree (the candidate's own
   * driver match plus nested containsChild/containsDescendants), so a bare
   * `containsChild: {textRegex: …}` — which carries no driver match on the candidate — still
   * skips the chrome. A selector whose driver match pins the container explicitly
   * (roleRegex/typeRegex/uniqueId, or the bridged classNameRegex/resourceIdRegex) still
   * matches it.
   */
  private fun isExcludedAsContainerChrome(
    node: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Boolean {
    val detail = node.driverDetail as? DriverNodeDetail.IosAxe ?: return false
    if (!isIosAxeContainerChrome(detail)) return false
    if (pinsIosContainer(selector.driverMatch)) return false
    return hasIosTextConstraint(selector)
  }

  private fun pinsIosContainer(match: DriverNodeMatch?): Boolean = when (match) {
    is DriverNodeMatch.IosAxe ->
      match.roleRegex != null || match.typeRegex != null || match.uniqueId != null
    is DriverNodeMatch.IosMaestro ->
      match.classNameRegex != null || match.resourceIdRegex != null
    else -> false
  }

  private fun hasIosTextConstraint(selector: TrailblazeNodeSelector): Boolean {
    selector.driverMatch?.let { if (hasIosTextConstraint(it)) return true }
    selector.containsChild?.let { if (hasIosTextConstraint(it)) return true }
    selector.containsDescendants?.let { list -> if (list.any { hasIosTextConstraint(it) }) return true }
    return false
  }

  private fun hasIosTextConstraint(match: DriverNodeMatch): Boolean = when (match) {
    is DriverNodeMatch.IosAxe ->
      match.labelRegex != null || match.valueRegex != null || match.titleRegex != null
    is DriverNodeMatch.IosMaestro ->
      match.textRegex != null || match.accessibilityTextRegex != null || match.hintTextRegex != null
    else -> false
  }

  private fun matchesIosAxe(
    detail: DriverNodeDetail.IosAxe,
    match: DriverNodeMatch.IosAxe,
  ): Boolean {
    if (!requirePattern(match.roleRegex, detail.role)) return false
    if (!requirePattern(match.subroleRegex, detail.subrole)) return false
    if (!requirePattern(match.labelRegex, detail.label)) return false
    if (!requirePattern(match.valueRegex, detail.value)) return false
    if (!requireEqual(match.uniqueId, detail.uniqueId)) return false
    if (!requirePattern(match.typeRegex, detail.type)) return false
    if (!requirePattern(match.titleRegex, detail.title)) return false
    match.customAction?.let { needed ->
      if (needed !in detail.customActions) return false
    }
    if (!requireEqual(match.enabled, detail.enabled)) return false
    return true
  }

  /**
   * Cross-dialect compatibility bridge: matches a [DriverNodeMatch.IosMaestro] selector — as
   * recorded under the legacy Maestro iOS driver — against a [DriverNodeDetail.IosAxe] node
   * captured by the newer AXe driver. Without this bridge, a trail recorded on Maestro can never
   * match on AXe (the dispatch in [matchesDriverDetail] otherwise requires shape equality), so
   * every existing `iosMaestro:` selector would break on driver migration.
   *
   * Maps Maestro's inferred vocabulary onto AXe's native fields:
   * - `text`/`textRegex` matches [DriverNodeDetail.IosAxe.label], `.value`, or `.title` — Maestro's
   *   iOS "text" is itself derived from this AX label/value/title cluster.
   * - `accessibilityText` matches `.label`.
   * - `resourceId`/`id` matches `.uniqueId` (`accessibilityIdentifier`).
   * - `className` matches `.type` or `.role` — AXe's `type` (e.g. "Button") is the closest analog
   *   to Maestro's class notion — plus the Maestro-era UIKit class names recorded selectors
   *   actually carry (see [MAESTRO_IOS_CLASS_ALIASES]): Maestro's iOS tree reports the *label
   *   view* (`LabelView`, `UILabel`, `UIButtonLabel`, `UITextFieldLabel`) where AXe reports the
   *   semantic element (`StaticText`, `Button`, `TextField`).
   * - `hintText` matches `.help`, or — on text-input types only — `.label`/`.value`, the
   *   properties iOS actually mirrors a placeholder onto (version-dependent; see the
   *   `hintTextRegex` branch below).
   *
   * Uses [MatchDialect.MAESTRO] throughout: the selector was authored under Maestro's lenient
   * semantics, and that's what it should still mean here.
   *
   * `focused` and `selected` **fail closed** — AXe exposes no equivalent signal, so a selector
   * carrying either can never match on an AXe tree. Silently dropping the constraint instead
   * would change what the selector means: a waypoint requiring `focused: true` would
   * false-match its non-focused sibling screen, and a forbidden selector keyed on
   * `focused: false` would match the always-present element and never let its waypoint match.
   * Selectors that need these constraints must be migrated to AXe-expressible fields before
   * the AXe driver replays them (they keep working unchanged on Maestro trees).
   *
   * The same applies to a selector with no bridgeable field at all: it matches nothing.
   */
  private fun matchesIosMaestroAgainstAxe(
    detail: DriverNodeDetail.IosAxe,
    match: DriverNodeMatch.IosMaestro,
  ): Boolean {
    val dialect = MatchDialect.MAESTRO
    // Fail closed: unbridgeable constraints (focused/selected) mean this selector cannot be
    // faithfully evaluated against an AXe tree — matching nothing is louder and safer than
    // silently weakening a recorded constraint.
    if (match.focused != null || match.selected != null) return false
    // Fail closed: a selector carrying only unbridgeable fields must match nothing.
    val bridgeable =
      match.textRegex != null ||
        match.accessibilityTextRegex != null ||
        match.resourceIdRegex != null ||
        match.classNameRegex != null ||
        match.hintTextRegex != null
    if (!bridgeable) return false

    match.textRegex?.let { pattern ->
      if (!matchesAnyPattern(pattern, dialect, detail.label, detail.value, detail.title)) {
        return false
      }
    }
    match.accessibilityTextRegex?.let { pattern ->
      if (!requirePattern(pattern, detail.label, dialect)) return false
    }
    match.resourceIdRegex?.let { pattern ->
      if (!requirePattern(pattern, detail.uniqueId, dialect)) return false
    }
    match.classNameRegex?.let { pattern ->
      val aliases = detail.type?.let { MAESTRO_IOS_CLASS_ALIASES[it] }.orEmpty()
      val matchesClass =
        matchesAnyPattern(pattern, dialect, detail.type, detail.role) ||
          aliases.any { matchesPattern(pattern, it, dialect) }
      if (!matchesClass) return false
    }
    match.hintTextRegex?.let { pattern ->
      // iOS surfaces a text input's placeholder (Maestro's hintText / XCUITest's
      // placeholderValue) on the input element itself, not on AXHelp — but WHICH
      // property carries it varies by AXe/iOS version: newer runtimes expose the
      // placeholder on AXLabel, while older ones leave AXLabel null and surface it
      // only as the empty field's AXValue (the Contacts search field: label=null,
      // value="Search"). Accept help, plus label OR value — the latter two only on
      // text-input types so a decorative node whose label happens to equal the hint
      // (e.g. a magnifying-glass Image labeled "Search") can't
      // false-match. Empty fields only: once the field has text, AXValue is the typed
      // text and AXLabel stays null on older runtimes, so a hint~ lookup of that same
      // field won't match there. Nothing in the AXe tree still carries the placeholder.
      val matchesHelp = requirePattern(pattern, detail.help, dialect)
      val matchesPlaceholder =
        detail.type in IOS_TEXT_INPUT_TYPES &&
          matchesAnyPattern(pattern, dialect, detail.label, detail.value)
      if (!matchesHelp && !matchesPlaceholder) return false
    }

    return true
  }

  // --- Match helpers ---

  /** Returns true if [expected] is null (no constraint) or equals [actual]. */
  private fun <T> requireEqual(expected: T?, actual: T): Boolean =
    expected == null || expected == actual

  /**
   * The matching semantics a selector shape carries. A selector means what it meant under the
   * driver dialect it was authored for, everywhere it is evaluated — so the Maestro-shape
   * branches ([DriverNodeMatch.AndroidMaestro], [DriverNodeMatch.IosMaestro]) keep the lenient
   * semantics Maestro's Orchestra compiled them with, while native shapes stay strict.
   *
   * Deliberately no runtime kill-switch: the MAESTRO dialect is strictly loosening (it can only
   * add matches, never remove one), a per-selector escape exists (leading `(?-i)` / `(?-s)`),
   * and this common code also targets Wasm where env vars don't exist. A match that succeeds
   * only via the lenient dialect is not separately logged; to debug a surprising match, re-test
   * the pattern with a `(?-i)` prefix.
   */
  private enum class MatchDialect {
    /** Strict: no implicit regex options; case-sensitive; `.` does not cross newlines. */
    NATIVE,

    /**
     * Maestro-compatible: `IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE` (Orchestra's
     * `REGEX_OPTIONS`), and an invalid pattern degrades to an escaped literal compiled with the
     * same options (Maestro's `toRegexSafe`) — i.e. a case-insensitive literal.
     */
    MAESTRO,
  }

  /**
   * Returns true if [pattern] is null (no constraint) or [text] matches it.
   * When pattern is set but text is null, the match fails (element lacks the property).
   */
  private fun requirePattern(pattern: String?, text: String?, dialect: MatchDialect = MatchDialect.NATIVE): Boolean {
    if (pattern == null) return true
    if (text == null) return false
    return matchesPattern(pattern, text, dialect)
  }

  /**
   * Returns true if [pattern] matches any non-null value in [texts]. Used by cross-dialect
   * bridges (e.g. [matchesIosMaestroAgainstAxe]) where one selector field is derived from a
   * cluster of several node properties on the target dialect.
   */
  private fun matchesAnyPattern(pattern: String, dialect: MatchDialect, vararg texts: String?): Boolean =
    texts.any { it != null && matchesPattern(pattern, it, dialect) }

  /**
   * Matches a regex pattern against the full text, then falls back to literal string equality
   * when the pattern doesn't match as a regex. The fallback covers both an unmatchable-but-valid
   * pattern (e.g. "$3.00", where a bare `$` is an end-of-input anchor so nothing can follow it —
   * it compiles fine but can never regex-match) and a pattern that fails to compile at all. This
   * mirrors Maestro's `Filters.textMatches` (`regex.matches(value) || regex.pattern == value`),
   * so a natural-language value like a price matches identically on the Maestro and accessibility
   * drivers without hand-escaping the metacharacters.
   *
   * Uses full-string matching (not substring) to prevent false positives when element text
   * contains the pattern as a substring (e.g., pattern "ok" should not match "book").
   *
   * [MatchDialect.MAESTRO] additionally compiles with Orchestra's `REGEX_OPTIONS` and degrades an
   * invalid pattern to an escaped literal with the same options (`toRegexSafe`), so a Maestro-shape
   * selector matches here exactly as it did under Maestro. Case-sensitivity escape hatch inside a
   * Maestro-shape pattern: a leading `(?-i)`. [MatchDialect.NATIVE] is strict; opt into
   * case-insensitivity with a leading `(?i)`.
   *
   * The regex leg (compile + full match, dialect options, `toRegexSafe` degrade) lives behind the
   * [selectorPatternRegexMatches] expect/actual so the Kotlin/JS compile of this resolver can
   * translate JVM-regex constructs (`\Q...\E`, leading inline flags, dotAll) that the native
   * ECMAScript `RegExp` doesn't support — see that declaration for the platform story. The
   * literal-equality fallback stays here, shared by every platform.
   *
   * The behavioral contract is locked by the shared cross-language fixture
   * `sdks/typescript/src/matcher/matcher-parity-fixtures.json`, consumed by this
   * implementation's [MatcherParityFixturesTest], the TS mirror's `matcher-parity.test.ts`, and
   * the Kotlin/JS selector engine's `engine-parity.test.ts`. Semantics changes must update the
   * fixture and all implementations together.
   */
  private fun matchesPattern(pattern: String, text: String, dialect: MatchDialect = MatchDialect.NATIVE): Boolean {
    if (selectorPatternRegexMatches(pattern, text, maestroDialect = dialect == MatchDialect.MAESTRO)) {
      return true
    }
    return text == pattern
  }
}
