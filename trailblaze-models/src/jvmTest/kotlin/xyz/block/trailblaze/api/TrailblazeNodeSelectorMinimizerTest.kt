package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for [TrailblazeNodeSelectorMinimizer]. The minimizer's contract is:
 *
 *   1. **Correctness** — the returned selector still uniquely matches the same target.
 *   2. **Tightness** — every field/relationship that *can* be dropped while preserving
 *      uniqueness *is* dropped.
 *   3. **Stability** — when multiple equally-precise selectors exist, the more stable
 *      identifier (uniqueId > resourceId > testTag > text > className) survives.
 *
 * Each test below picks one of those three contracts and exercises it directly. The
 * cross-cutting concern is fragility: extra `classNameRegex` on a node that's already
 * uniquely identified by text breaks the recording for no semantic reason the moment
 * that node's underlying widget is reimplemented (e.g. `android.widget.TextView` →
 * Compose `Text`). The bug report that motivated this minimizer was exactly that case.
 */
class TrailblazeNodeSelectorMinimizerTest : TrailblazeNodeSelectorGeneratorTestBase() {

  private fun node(
    detail: DriverNodeDetail.AndroidAccessibility = DriverNodeDetail.AndroidAccessibility(),
    bounds: TrailblazeNode.Bounds? = TrailblazeNode.Bounds(0, 0, 100, 50),
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = nodeOf(detail, bounds, children)

  // ---------------------------------------------------------------------------
  // The reported bug: containsChild over a node that's uniquely identified by text
  // should not carry its host widget's className.
  // ---------------------------------------------------------------------------

  @Test
  fun `containsChild over uniquely-texted descendant drops className from child match`() {
    // Mirrors the bug report — an outer 'android.view.View' wrapper identified by
    // a unique 'Estimates' text descendant. The TextView's className is fragile
    // (any Compose rewrite of the same label would change it) and not needed for
    // disambiguation, so it should drop.
    //
    // The outer View match is also redundant here: `containsChild` is direct-child-only
    // (see TrailblazeNodeSelectorResolver — uses `node.children.any { ... }`), so a
    // child uniquely identifies exactly one direct parent. Once the inner predicate
    // resolves uniquely, the outer match contributes nothing and the minimizer drops
    // it entirely.
    nextId = 1L
    val estimatesText = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Estimates",
        className = "android.widget.TextView",
      ),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(estimatesText),
    )
    // A sibling wrapper without the unique text — keeps things realistic but doesn't
    // change the minimizer's conclusion since `containsChild`'s direct-child semantics
    // already pin the parent uniquely.
    val siblingTextView = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Subscriptions",
        className = "android.widget.TextView",
      ),
    )
    val sibling = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(siblingTextView),
    )
    val root = node(children = listOf(target, sibling))

    val selector = assertUniqueMatch(root, target)
    val nested = selector.containsChild
    assertNotNull(nested, "containsChild strategy expected to fire for this shape")
    val nestedMatch = nested.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("Estimates", nestedMatch.textRegex)
    assertNull(
      nestedMatch.classNameRegex,
      "classNameRegex on the containsChild inner match is redundant when text alone disambiguates",
    )
    // The outer View match should also drop — `containsChild: { text=Estimates }` alone
    // resolves uniquely because no other node in the tree has 'Estimates' as a *direct*
    // child. Keeping `classNameRegex: View` on the outer would be pure noise.
    assertNull(
      selector.driverMatch,
      "outer match should drop entirely when containsChild alone uniquely identifies the parent",
    )
  }

  // ---------------------------------------------------------------------------
  // Top-level driver-match minimization.
  // ---------------------------------------------------------------------------

  @Test
  fun `text-plus-className collapses to text alone when text is unique`() {
    // Hand-built kitchen-sink selector — simulates what compound strategies like
    // 'Text + class' emit before the minimizer runs.
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Submit",
        className = "android.widget.Button",
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"))
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "Submit",
        classNameRegex = "android\\.widget\\.Button",
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    val match = minimized.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("Submit", match.textRegex)
    assertNull(match.classNameRegex, "className was redundant once text disambiguated alone")
  }

  @Test
  fun `text-plus-className keeps both when each is independently load-bearing`() {
    // Three nodes:
    //   target  : text=Fries, class=EditText  ← the one we want
    //   sharesText: text=Fries, class=TextView (would survive a text-only selector)
    //   sharesClass: text=Lemonade, class=EditText (would survive a class-only selector)
    // Neither text nor className alone is unique, so both must survive minimization.
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Fries",
        className = "android.widget.EditText",
      ),
    )
    val sharesText = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Fries",
        className = "android.widget.TextView",
      ),
    )
    val sharesClass = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Lemonade",
        className = "android.widget.EditText",
      ),
    )
    val root = node(children = listOf(target, sharesText, sharesClass))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "Fries",
        classNameRegex = "android\\.widget\\.EditText",
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    val match = minimized.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("Fries", match.textRegex, "text is load-bearing — sharesClass would otherwise match")
    assertEquals(
      "android\\.widget\\.EditText",
      match.classNameRegex,
      "className is load-bearing — sharesText would otherwise match",
    )
  }

  // ---------------------------------------------------------------------------
  // Stability ordering: prefer the more stable identifier when there's a choice.
  // ---------------------------------------------------------------------------

  @Test
  fun `uniqueId wins over text and className when all are present and individually unique`() {
    // The cascade may produce a kitchen-sink containing uniqueId + text + class.
    // After minimization only uniqueId should remain — it's the most stable.
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        uniqueId = "uid-checkout",
        text = "Checkout",
        className = "android.widget.Button",
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Browse"))
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        uniqueId = "uid-checkout",
        textRegex = "Checkout",
        classNameRegex = "android\\.widget\\.Button",
      ),
    )

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    val match = minimized.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("uid-checkout", match.uniqueId)
    assertNull(match.textRegex, "uniqueId is stabler — text should drop")
    assertNull(match.classNameRegex, "uniqueId is stabler — className should drop")
  }

  @Test
  fun `text wins over className when both are individually unique`() {
    // Two nodes — target has unique text AND unique class; other has neither.
    // Text is more stable (className depends on widget implementation), so text wins.
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Greetings",
        className = "android.widget.SeekBar",
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Other",
        className = "android.widget.TextView",
      ),
    )
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "Greetings",
        classNameRegex = "android\\.widget\\.SeekBar",
      ),
    )

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    val match = minimized.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("Greetings", match.textRegex)
    assertNull(match.classNameRegex, "text is stabler — className should drop")
  }

  // ---------------------------------------------------------------------------
  // Whole-relationship drops.
  // ---------------------------------------------------------------------------

  @Test
  fun `redundant containsChild is dropped when target match alone is unique`() {
    nextId = 1L
    val childA = node(detail = DriverNodeDetail.AndroidAccessibility(text = "A"))
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(uniqueId = "uid-1"),
      children = listOf(childA),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other"))
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(uniqueId = "uid-1"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "A"),
      ),
    )
    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    assertNull(minimized.containsChild, "uniqueId alone suffices — containsChild should drop")
    val match = minimized.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("uid-1", match.uniqueId)
  }

  @Test
  fun `redundant index is dropped when content fields disambiguate`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Only one"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other"))
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Only one"),
      index = 0,
    )
    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    assertNull(minimized.index, "text alone disambiguates — index should drop")
  }

  // ---------------------------------------------------------------------------
  // containsDescendants list pruning.
  // ---------------------------------------------------------------------------

  @Test
  fun `containsDescendants drops list elements that are not load-bearing`() {
    nextId = 1L
    val child1 = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Subscriptions"))
    val child2 = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Free plan available"))
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(child1, child2),
    )
    val sibling = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(
        node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other text")),
      ),
    )
    val root = node(children = listOf(target, sibling))

    // "Subscriptions" alone is unique within the tree; the second descriptor predicate
    // is along for the ride and should drop.
    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.view\\.View"),
      containsDescendants = listOf(
        TrailblazeNodeSelector(androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Subscriptions")),
        TrailblazeNodeSelector(androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Free plan available")),
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    // Either the redundant list element dropped, or the whole list dropped because
    // some other field alone is unique. Both shrink the selector — assert at least
    // one descendant predicate is gone.
    val remainingCount = minimized.containsDescendants?.size ?: 0
    assertTrue(remainingCount < 2, "expected at least one redundant descendant predicate to drop")
  }

  // ---------------------------------------------------------------------------
  // Spatial-anchor uniqueness invariant (PR #3050 review — Copilot).
  // ---------------------------------------------------------------------------

  @Test
  fun `spatial anchor minimization keeps fields needed for anchor to resolve uniquely`() {
    // The resolver uses `resolveFirstBounds` on spatial anchors (above/below/leftOf/
    // rightOf), so an anchor that matches multiple nodes silently picks whichever
    // the tree enumerates first. The minimizer must not drop a field whose removal
    // would widen the anchor's match set, even if the target *happens* to still be
    // unique under the current tree's enumeration order — sibling-order shifts at
    // playback would silently bind the spatial check to a different node and break.
    //
    // Setup:
    //   decoyTap : text=Tap at (-100..-50)        — kills text-only uniqueness, NOT rightOf banner
    //   banner   : text=X, class=Banner at (0..100)
    //   target   : text=Tap at (200..300)         — rightOf banner, NOT rightOf footer
    //   footer   : text=X, class=Footer at (400..500)
    //
    // `{text=Tap, rightOf: {text=X, class=Banner}}` is unique — anchor=banner only,
    // and target is to the right of it; decoyTap is to the left.
    //
    // If we drop `class=Banner` from the anchor:
    //   - banner.right = 100, target.left = 200 → 200 ≥ 100 ✓
    //   - footer.right = 500, target.left = 200 → 200 ≥ 500 ✗
    //   - resolveFirstBounds picks whichever {text=X} enumerates first.
    //   - With banner first (current tree), spatial check still picks target → outer
    //     uniqueness is preserved. The drop *looks* safe under outer-uniqueness alone.
    //   - But the anchor itself is now ambiguous (banner AND footer both match).
    //     Sibling-order shift at playback would bind the check to footer's bounds,
    //     spatial predicate fails, target no longer resolves.
    //
    // The `requireUniqueAnchor` hook must reject the class drop so the recorded
    // selector survives a tree reordering.
    nextId = 1L
    val decoyTap = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Tap"),
      bounds = TrailblazeNode.Bounds(-100, 0, -50, 50),
    )
    val banner = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "X", className = "Banner"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "Tap"),
      bounds = TrailblazeNode.Bounds(200, 0, 300, 50),
    )
    val footer = node(
      detail = DriverNodeDetail.AndroidAccessibility(text = "X", className = "Footer"),
      bounds = TrailblazeNode.Bounds(400, 0, 500, 50),
    )
    val root = node(
      bounds = TrailblazeNode.Bounds(-100, 0, 500, 50),
      children = listOf(decoyTap, banner, target, footer),
    )

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Tap"),
      rightOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          textRegex = "X",
          classNameRegex = "Banner",
        ),
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    val anchor = minimized.rightOf
    assertNotNull(anchor, "rightOf anchor slot must survive — text=Tap alone matches both target and decoyTap")

    // The invariant the requireUniqueAnchor hook enforces: the minimized anchor
    // must still resolve to exactly one node anywhere in the tree. WITHOUT the
    // hook, the minimizer would drop class=Banner first (it's earlier in the
    // stability order) — outer uniqueness is preserved under resolveFirstBounds's
    // "pick whichever match enumerates first" behavior, but the anchor becomes
    // text=X which matches both banner and footer. With the hook, that drop is
    // rejected; the minimizer drops text instead, leaving the class-only anchor
    // which is genuinely unique. Either way, the surviving anchor must resolve
    // to a single node — that's the contract.
    val anchorResult = TrailblazeNodeSelectorResolver.resolve(root, anchor)
    assertTrue(
      anchorResult is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch,
      "minimized spatial anchor must resolve to exactly one node; got $anchorResult for ${anchor.description()}",
    )

    // Whichever field stayed, the resolved node must be banner (the original
    // anchor), not footer — that's the guarantee that sibling reorders won't
    // silently bind the spatial check to a different node.
    assertEquals(
      banner.nodeId,
      (anchorResult as TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch).node.nodeId,
      "anchor must resolve to the original anchor node (banner), not its widened alternative (footer)",
    )
  }

  // ---------------------------------------------------------------------------
  // Cumulative-state correctness (PR #3050 review — Copilot + Codex).
  // ---------------------------------------------------------------------------

  @Test
  fun `containsDescendants minimization stays unique across interdependent drops`() {
    // Two-descendant conjunction where each className is only redundant when the
    // OTHER descendant still carries its className. Both look individually-safe
    // to strip if evaluated against the original list; stripping both produces
    // a non-unique selector. Regression for the bug both bot reviewers flagged
    // on PR #3050: the earlier version minimized each element against the
    // original `workingList` and combined the parallel reductions blindly.
    //
    // Tree:
    //   target = view containing:
    //     - { text=Alpha, class=TextView }
    //     - { text=Beta,  class=Button }
    //   confuser = view containing:
    //     - { text=Alpha, class=Button }     ← matches if we drop class from elem 1
    //     - { text=Beta,  class=TextView }   ← matches if we drop class from elem 2
    //
    // For the conjunction `{ Alpha, class=TextView }, { Beta, class=Button }`:
    //   - Drop class from elem 1 alone: confuser still rejected (its Beta child
    //     is a TextView, not a Button). Unique.
    //   - Drop class from elem 2 alone: confuser still rejected (its Alpha
    //     child is a Button, not a TextView). Unique.
    //   - Drop class from BOTH: confuser now matches (Alpha and Beta both exist
    //     as direct children, classes don't matter). Non-unique — must be
    //     rejected by the minimizer.
    nextId = 1L
    val targetAlpha = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Alpha", className = "android.widget.TextView"))
    val targetBeta = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Beta", className = "android.widget.Button"))
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(targetAlpha, targetBeta),
    )
    val confuserAlpha = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Alpha", className = "android.widget.Button"))
    val confuserBeta = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Beta", className = "android.widget.TextView"))
    val confuser = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(confuserAlpha, confuserBeta),
    )
    val root = node(children = listOf(target, confuser))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.view\\.View"),
      containsDescendants = listOf(
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(
            textRegex = "Alpha",
            classNameRegex = "android\\.widget\\.TextView",
          ),
        ),
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(
            textRegex = "Beta",
            classNameRegex = "android\\.widget\\.Button",
          ),
        ),
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)

    // Whatever the minimizer chose to drop, the final selector MUST still
    // uniquely resolve to target. This is the contract Codex's bot review
    // flagged: the cumulative widening of two "safe-in-isolation" drops can
    // break uniqueness, and the minimizer must catch that.
    assertTrue(
      isUniqueMatch(root, target, minimized),
      "minimizer must preserve uniqueness across interdependent containsDescendants drops, got: ${minimized.description()}",
    )
  }

  // ---------------------------------------------------------------------------
  // Index-carrying selectors must keep an anchor (never collapse to bare index).
  // ---------------------------------------------------------------------------

  @Test
  fun `index-carrying selector retains the most-stable anchor instead of collapsing to bare index`() {
    // Every node — root included — shares className=View, so the class predicate
    // is a no-op: the class-filtered index equals the global index. Dropping
    // className keeps the target unique under `{index}` alone, so the pre-fix
    // minimizer stripped className and emitted a purely-positional `{index: 2}`.
    // Post-fix, because the selector carries an index, the most-stable surviving
    // match field (className here) must be retained as an anchor.
    //
    // Distinct stacked bounds make the resolver's top-then-left sort
    // deterministic. The root sorts first (top=0), so the target (the 2nd child,
    // top=100) lands at global index 2.
    nextId = 1L
    val node1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 10, 100, 50),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val node3 = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 200, 100, 250),
    )
    val root = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 300),
      children = listOf(node1, target, node3),
    )

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.view\\.View"),
      index = 2,
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)

    assertEquals(2, minimized.index, "index must survive — it's the disambiguator")
    val match = minimized.driverMatch as? DriverNodeMatch.AndroidAccessibility
    assertNotNull(
      match,
      "an index-carrying selector must keep a content/structural anchor, never collapse to bare index",
    )
    assertEquals(
      "android\\.view\\.View",
      match.classNameRegex,
      "the most-stable surviving match field must be retained alongside the index",
    )
    assertTrue(
      isUniqueMatch(root, target, minimized),
      "anchor + index must still uniquely resolve to target",
    )
  }

  @Test
  fun `attribute-less node still collapses to bare index as the legitimate last resort`() {
    // A genuinely attribute-less node — no text/contentDescription/className/
    // resourceId/uniqueId. There is no anchor to retain, so a bare `{index}` is
    // the only thing the minimizer can emit. This proves the keep-an-anchor
    // guard does not over-fix the legitimate attribute-less fallback.
    //
    // Distinct stacked bounds make the resolver's sort deterministic; the root
    // (top=0) sorts first, so the target (2nd child, top=100) is global index 2.
    nextId = 1L
    val node1 = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 10, 100, 50),
    )
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 100, 100, 150),
    )
    val node3 = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 200, 100, 250),
    )
    val root = node(
      detail = DriverNodeDetail.AndroidAccessibility(),
      bounds = TrailblazeNode.Bounds(0, 0, 100, 300),
      children = listOf(node1, target, node3),
    )

    val raw = TrailblazeNodeSelector(index = 2)
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)

    assertEquals(2, minimized.index, "index must survive")
    assertNull(
      minimized.driverMatch,
      "an attribute-less node has no anchor to retain — bare index is the legitimate last resort",
    )
  }

  @Test
  fun `selector with no index still clears an empty match to a pure content selector`() {
    // No index on the selector — when the match minimizes to empty (the
    // containsChild predicate alone disambiguates), clearing the empty shell is
    // correct: a pure content/relationship selector is not positional, so the
    // keep-an-anchor guard must not fire here.
    nextId = 1L
    val childA = node(detail = DriverNodeDetail.AndroidAccessibility(text = "UniqueChild"))
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(childA),
    )
    val sibling = node(
      detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
      children = listOf(node(detail = DriverNodeDetail.AndroidAccessibility(text = "Other"))),
    )
    val root = node(children = listOf(target, sibling))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.view\\.View"),
      containsChild = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "UniqueChild"),
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)

    assertNull(minimized.index, "no index was present — none should appear")
    assertNull(
      minimized.driverMatch,
      "with no index, an empty match clears to a pure content/relationship selector",
    )
    assertNotNull(minimized.containsChild, "containsChild is the load-bearing disambiguator")
  }

  // ---------------------------------------------------------------------------
  // Idempotence.
  // ---------------------------------------------------------------------------

  @Test
  fun `minimize is idempotent`() {
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Submit",
        className = "android.widget.Button",
        contentDescription = "Submit button",
      ),
    )
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Cancel"))
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "Submit",
        classNameRegex = "android\\.widget\\.Button",
        contentDescriptionRegex = "Submit button",
      ),
    )
    val once = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    val twice = TrailblazeNodeSelectorMinimizer.minimize(root, target, once)
    assertEquals(once, twice, "minimize should be a no-op after the first run")
  }

  // ---------------------------------------------------------------------------
  // Non-unique input: minimizer must not corrupt a non-unique selector.
  // ---------------------------------------------------------------------------

  @Test
  fun `non-unique input is returned unchanged`() {
    nextId = 1L
    val target = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Same"))
    val other = node(detail = DriverNodeDetail.AndroidAccessibility(text = "Same"))
    val root = node(children = listOf(target, other))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Same"),
    )
    assertTrue(!isUniqueMatch(root, target, raw))

    val out = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
    assertEquals(raw, out)
  }

  // ---------------------------------------------------------------------------
  // List-with-repeated-children: parent disambiguator IS load-bearing.
  // ---------------------------------------------------------------------------

  @Test
  fun `list scenario - minimizer keeps both target text and parent disambiguator when each is load-bearing`() {
    // Classic list scenario, asserted at the minimizer boundary: each row has a
    // per-item title plus a "Buy" button. The Buy button's own text isn't
    // unique (every row has one), and the row container is identifiable only
    // through its sibling title — not its own driverDetail. So the precise
    // shape that disambiguates the target Buy button is:
    //
    //   { text=Buy, childOf={ containsChild={ text=<this row's title> } } }
    //
    // This test feeds exactly that hand-built selector into the minimizer (the
    // cascade today doesn't produce this composite shape for shared-container
    // lists — it falls through to `index`; that's a separate cascade-coverage
    // follow-up). The minimizer must:
    //
    //   1. NOT drop `text=Buy` — without it the childOf predicate matches every
    //      direct child of row1, two nodes (title and Buy).
    //   2. NOT drop the `childOf` slot — without it, "Buy" matches all three
    //      buttons.
    //   3. NOT drop the inner `containsChild` — its disappearance reduces the
    //      parent predicate to "any node," which doesn't disambiguate rows.
    //   4. NOT drop the inner title text — same reason.
    //
    // i.e. every component of the composite is independently load-bearing and
    // must survive minimization unchanged.
    nextId = 1L

    fun row(itemTitle: String): Pair<TrailblazeNode, TrailblazeNode> {
      val title = node(detail = DriverNodeDetail.AndroidAccessibility(text = itemTitle))
      val buy = node(
        detail = DriverNodeDetail.AndroidAccessibility(
          text = "Buy",
          className = "android.widget.Button",
        ),
      )
      val container = node(
        detail = DriverNodeDetail.AndroidAccessibility(className = "android.view.View"),
        children = listOf(title, buy),
      )
      return container to buy
    }

    val (row1, target) = row("iPhone 15")
    val (row2, _) = row("Galaxy S25")
    val (row3, _) = row("Pixel 10")
    val root = node(children = listOf(row1, row2, row3))

    val raw = TrailblazeNodeSelector(
      androidAccessibility = DriverNodeMatch.AndroidAccessibility(
        textRegex = "Buy",
        classNameRegex = "android\\.widget\\.Button",
      ),
      childOf = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(classNameRegex = "android\\.view\\.View"),
        containsChild = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "iPhone 15"),
        ),
      ),
    )
    assertTrue(isUniqueMatch(root, target, raw))

    val minimized = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)

    // text=Buy survives.
    val outerMatch = minimized.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("Buy", outerMatch.textRegex)
    // classNameRegex on the outer is redundant — the childOf predicate already
    // restricts to direct children of a row, which limits the matches to
    // (title, Buy) within that row. `text=Buy` picks Buy among those two; the
    // Button class adds nothing on top. Minimizer correctly drops it.
    assertNull(outerMatch.classNameRegex, "outer className is redundant once text+childOf pin the Buy button")

    // childOf slot stays — it's the parent disambiguator.
    val childOf = minimized.childOf
    assertNotNull(childOf, "childOf must survive — without it 'Buy' alone matches three buttons")

    // The childOf's *own* driver match (classNameRegex=View) can drop — the row
    // container's class is just along for the ride, like in the Estimates case.
    assertNull(
      childOf.driverMatch,
      "row container's classNameRegex is not load-bearing; containsChild already pins the parent",
    )

    // The inner containsChild stays.
    val innerContains = childOf.containsChild
    assertNotNull(innerContains, "inner containsChild must survive — it carries the row's title disambiguator")

    // And the title text inside survives unchanged.
    val innerMatch = innerContains.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("iPhone 15", innerMatch.textRegex)
  }

  // ---------------------------------------------------------------------------
  // End-to-end via the generator: confirms the wiring in findBestSelector.
  // ---------------------------------------------------------------------------

  @Test
  fun `findBestSelector returns minimized selector for text-plus-class case`() {
    // Regression for the user-reported pattern: the generator's cascade lands on a
    // compound 'text + class' strategy, but text alone is unique. The wired-in
    // minimizer should strip className before the selector escapes.
    nextId = 1L
    val target = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Estimates",
        className = "android.widget.TextView",
      ),
    )
    val other = node(
      detail = DriverNodeDetail.AndroidAccessibility(
        text = "Subscriptions",
        className = "android.widget.TextView",
      ),
    )
    val root = node(children = listOf(target, other))

    val selector = TrailblazeNodeSelectorGenerator.findBestSelector(root, target)
    val match = selector.driverMatch as DriverNodeMatch.AndroidAccessibility
    assertEquals("Estimates", match.textRegex)
    assertNull(match.classNameRegex)
  }
}
