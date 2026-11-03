package xyz.block.trailblaze.android.test.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.isIdleTimeout
import xyz.block.trailblaze.android.test.onMainThread
import xyz.block.trailblaze.android.test.perf

/**
 * Acts on a Compose semantics node that has already been chosen, by identity.
 *
 * The app's own Compose rule is asked for the node whose `SemanticsNode.id` matches — not for a node
 * matching a test tag or some text. The selector already picked a node out of the captured tree;
 * asking Compose to find "the node with this test tag" would be a second, differently-specified
 * search, and `hasText` alone routinely matches several nodes.
 *
 * Reads are against the **unmerged** tree throughout, matching what the hierarchy collector
 * captured. Unmerged nodes are finer-grained than a user thinks of them — a Button's label Text is
 * its own node and carries no click action — so [nearestWith] moves the action to the node that
 * owns it.
 */
internal object AndroidComposeActions {

  /**
   * Taps the node with a touch, at the point on it a touch actually reaches.
   *
   * A touch, not the `OnClick` semantics action, because in some apps the action is not equivalent
   * to it. A row can publish `OnClick` and act only on real pointer input, so dispatching the
   * action to one succeeds, changes nothing, and leaves the trail believing it has navigated, its
   * remaining steps spent on a menu it thought it had left. It also matches how the recordings were
   * made: the recording driver tapped such a row with a `GESTURE`, not an `ACTION_CLICK`.
   *
   * The POINT is what moves instead. `performClick` taps the center, which goes wherever the hit
   * test sends it — through an overlay drawn later, onto that overlay — while this tool still
   * reports success because its selector matched. Both drivers scroll a list until the row is on
   * screen and tap it, but each scroll stops somewhere different: one can leave the row well clear
   * of a banner drawn over the list and the other with the row just inside it. [reachablePoint]
   * takes the nearest point on the row the touch still reaches, which is the same tap on the same
   * row.
   *
   * Only a node with no reachable point at all — covered everywhere, so no touch can express the
   * tap — falls back to the action, and says so in its result.
   */
  suspend fun click(target: AndroidTestTarget, semanticsId: Int): String? {
    val rule = target.requireComposeRule()
    // One read for the whole lookup: each [AndroidTestTarget.composeRoots] synchronizes with the
    // app, and finding the site, its node and its reachable point are questions about one frame.
    val roots = perf("click.roots") { target.composeRoots() }
    val (site, relocation) = perf("click.nearestWith") { nearestWith(roots, semanticsId, "clickable", SemanticsActions.OnClick) }
    val node = perf("click.findNode") { findSemanticsNode(roots, site) }
    // The touch below is only delivered once the node has stopped moving; see [awaitStill].
    node?.let { perf("click.awaitStill") { awaitStill { it.boundsInWindow } } }
    val point = node?.let { perf("click.reachablePoint") { reachablePoint(roots, it) } }
    val moved = point != null && point != node.boundsInWindow.center
    val dispatched = runCatching {
      perf("click.dispatchAndSettle") { target.dispatchAndAwaitSettle {
        when {
          point != null ->
            rule.nodeWithId(site).performTouchInput { click(point - node.boundsInWindow.topLeft) }
          node != null && node.offersClickAction() ->
            rule.nodeWithId(site).performSemanticsAction(SemanticsActions.OnClick)
          else -> rule.nodeWithId(site).performClick()
        }
      } }
    }
    // A rule action synchronizes with the app BEFORE it acts, so an idle timeout here means the
    // tap never happened: the screen never went idle. A screen that recomposes
    // without end loses taps that way. The accessibility driver taps
    // there regardless, so this does too, with a raw touch at the same point. (A timeout in the
    // settle AFTER the action is already absorbed by dispatchAndAwaitSettle.)
    val unsynchronized = dispatched.exceptionOrNull()?.let { failure ->
      if (!failure.isIdleTimeout() || node == null || point == null) throw failure
      val windowToScreen = node.positionOnScreen - node.positionInWindow
      AndroidScrollActions.injectTap(target, point + windowToScreen)
      UNSYNCHRONIZED_TAP_NOTE
    }
    return listOfNotNull(
      relocation,
      RELOCATED_TAP_NOTE.takeIf { moved },
      COVERED_NOTE.takeIf { node != null && point == null },
      unsynchronized,
    ).takeIf { it.isNotEmpty() }?.joinToString(" ")
  }

  /** Whether dispatching the click action to this node would reach a handler at all. */
  private fun SemanticsNode.offersClickAction(): Boolean {
    if (!config.contains(SemanticsActions.OnClick)) return false
    if (config.contains(SemanticsProperties.Disabled)) return false
    // The editable exclusion is the gate's: `ACTION_CLICK` focuses a field without honoring the
    // touch offset, so it cannot place a caret where the recording put one.
    return !config.contains(SemanticsActions.SetText)
  }

  /**
   * Whether a touch at [point] would be delivered to [node] (or something inside it).
   *
   * Answered by finding the last node in draw order whose bounds contain that point: Compose draws
   * siblings in composition order, so the deepest-last node containing a point is the one its hit
   * test hands the touch to.
   */
  private fun touchAtReaches(roots: List<SemanticsNode>, node: SemanticsNode, point: Offset): Boolean {
    var topMost: SemanticsNode? = null
    fun visit(current: SemanticsNode) {
      if (current.boundsInWindow.contains(point)) topMost = current
      current.children.forEach(::visit)
    }
    roots.forEach(::visit)
    val hit = topMost ?: return true
    return generateSequence(hit) { it.parent }.any { it.id == node.id }
  }

  /**
   * The point inside [node] a touch should be sent to: its center, or — when something is drawn
   * over the center — the nearest point on the node the hit test still delivers to it.
   *
   * Scans the node's own box, nearest-to-center first, and takes the first point that reaches it.
   * Null means the node is covered everywhere and no touch can reach it.
   *
   * The roots are read ONCE for the whole scan. Every probe answers a question about one tree, so
   * re-reading per probe is not more accurate — and `composeRoots()` synchronizes with the app,
   * which on a never-idle screen means paying the framework's idle timeout before falling back.
   * A covered center puts 64 probes behind that, so this is the difference between one timeout and
   * sixty-five.
   */
  private fun reachablePoint(roots: List<SemanticsNode>, node: SemanticsNode): Offset? {
    val box = node.boundsInWindow
    val center = box.center
    if (touchAtReaches(roots, node, center)) return center
    // An inset keeps every candidate off the node's own boundary, where the hit test is ambiguous
    // and a rounding difference decides the winner.
    val stepsX = 8
    val stepsY = 8
    val insetX = box.width / (stepsX * 2)
    val insetY = box.height / (stepsY * 2)
    return (0 until stepsX * stepsY)
      .map { i ->
        Offset(
          box.left + insetX + (box.width - 2 * insetX) * (i % stepsX) / (stepsX - 1f),
          box.top + insetY + (box.height - 2 * insetY) * (i / stepsX) / (stepsY - 1f),
        )
      }
      .sortedBy { (it - center).getDistanceSquared() }
      .firstOrNull { touchAtReaches(roots, node, it) }
  }

  private const val RELOCATED_TAP_NOTE =
    "Another element was drawn over this one's center, so the tap was moved to the nearest point " +
      "on it the touch still reaches."

  private const val UNSYNCHRONIZED_TAP_NOTE =
    "The screen never went idle, so the tap was sent as a plain touch without waiting for it."

  private const val COVERED_NOTE =
    "Another element was drawn over this one, so the click action was dispatched to it directly " +
      "rather than as a touch that would have landed on the overlay."

  /**
   * A press held past the long-press timeout, dispatched at the resolved node itself.
   *
   * Deliberately NOT relocated through [nearestWith]: a long press is a raw gesture on the driver these
   * recordings were made under — a touch down at the element's center, held — and the node that
   * reacts to it is decided by Compose's own hit test, exactly as it would be for a finger. Moving
   * the gesture to an ancestor carrying `OnLongClick` would send it somewhere the recording never
   * touched, and the case this exists for (a trail long-pressing an EMPTY favorites tile) has no
   * such ancestor to move to.
   */
  suspend fun longClick(target: AndroidTestTarget, semanticsId: Int) {
    val rule = target.requireComposeRule()
    target.dispatchAndAwaitSettle { rule.nodeWithId(semanticsId).performTouchInput { longClick() } }
  }

  suspend fun replaceText(target: AndroidTestTarget, semanticsId: Int, value: String): String? {
    val rule = target.requireComposeRule()
    val roots = target.composeRoots()
    val (site, relocation) = nearestWith(roots, semanticsId, "a text input", SemanticsActions.SetText)
    val dispatched = runCatching {
      target.dispatchAndAwaitSettle { rule.nodeWithId(site).performTextReplacement(value) }
    }
    // The rule waits for idle before it acts, as in [click], so a timeout means no text was set.
    // On a dialog over a screen that never settles, clearing a field fails that way with the
    // field focused. Set the text through the node's own action, which
    // is what the rule would have invoked.
    val unsynchronized = dispatched.exceptionOrNull()?.let { failure ->
      if (!failure.isIdleTimeout()) throw failure
      val setText = findSemanticsNode(roots, site)?.config?.getOrNull(SemanticsActions.SetText)?.action
        ?: throw failure
      target.dispatchAndAwaitSettle { onMainThread { setText(AnnotatedString(value)) } }
      UNSYNCHRONIZED_TEXT_NOTE
    }
    return listOfNotNull(relocation, unsynchronized).takeIf { it.isNotEmpty() }?.joinToString(" ")
  }

  /**
   * Appends [text] at the end of a text input without rewriting what it holds: the cursor moves to
   * the end and the text is inserted there. The field's semantics expose its text as displayed, so a
   * password or formatted field reads back as its mask or formatting, and replacing the field with
   * that plus [text] would write the mask into the app's state.
   *
   * False when the input has no insert action, so the caller falls back to a replacement.
   */
  suspend fun appendText(target: AndroidTestTarget, semanticsId: Int, text: String): Boolean {
    val rule = target.requireComposeRule()
    val node = findSemanticsNode(target.composeRoots(), semanticsId) ?: return false
    val insert = node.config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action ?: return false
    val setSelection = node.config.getOrNull(SemanticsActions.SetSelection)?.action ?: return false
    // The displayed end maps to the underlying end through the field's own offset mapping.
    val end = node.config.getOrNull(SemanticsProperties.EditableText)?.length ?: 0
    val dispatched = runCatching {
      target.dispatchAndAwaitSettle {
        rule.nodeWithId(semanticsId).performSemanticsAction(SemanticsActions.SetSelection) { it(end, end, false) }
        rule.nodeWithId(semanticsId).performSemanticsAction(SemanticsActions.InsertTextAtCursor) { it(AnnotatedString(text)) }
      }
    }
    // As in [replaceText]: a screen that never settles gets the node's own actions without the wait.
    dispatched.exceptionOrNull()?.let { failure ->
      if (!failure.isIdleTimeout()) throw failure
      target.dispatchAndAwaitSettle {
        onMainThread {
          setSelection(end, end, false)
          insert(AnnotatedString(text))
        }
      }
    }
    return true
  }

  private const val UNSYNCHRONIZED_TEXT_NOTE =
    "The screen never went idle, so the text was set through the field's own action without " +
      "waiting for it."

  /**
   * Scrolls this node's own scrollable ancestor until the node is in view.
   *
   * Preferred over scrolling whichever container looks like the main one whenever the node is
   * already in the tree: on a screen with an overlay, both the overlay's list and the screen
   * underneath it are attached and scrollable, and only the node itself knows which one it rides.
   */
  suspend fun scrollTo(target: AndroidTestTarget, semanticsId: Int) {
    target.dispatchAndAwaitSettle {
      target.requireComposeRule().nodeWithId(semanticsId).performScrollTo()
    }
  }

  /**
   * The rule's own check synchronizes with the app first, so on a screen that never goes idle it
   * can only time out, as on a screen that animates without end. There the node is asked directly
   * instead: still attached, placed, and with on-screen area, which is what `assertIsDisplayed`
   * checks.
   */
  fun assertDisplayed(target: AndroidTestTarget, semanticsId: Int) {
    val checked = runCatching {
      target.requireComposeRule().nodeWithId(semanticsId).assertExists().assertIsDisplayed()
    }
    val failure = checked.exceptionOrNull() ?: return
    if (!failure.isIdleTimeout()) throw failure
    val node = findSemanticsNode(target.composeRoots(), semanticsId) ?: throw failure
    val displayed = onMainThread {
      node.layoutInfo.isPlaced && node.layoutInfo.isAttached && !node.boundsInWindow.isEmpty
    }
    if (!displayed) throw AssertionError("The node is in the tree but not displayed.", failure)
  }

  /**
   * Finds the semantics node that can actually take the action, starting from the one the selector
   * resolved: self, then ancestors, then descendants.
   *
   * Both directions are ordinary Compose structure. Ascending covers the label inside a `Button`;
   * descending covers a `TextField` whose test tag sits on the wrapper while the set-text action
   * lives on the inner text node. The relocation is returned so the tool reports it — a silent jump
   * to a neighbouring node is how a test ends up clicking something nobody asked for.
   *
   * Descending stops at the shallowest depth that has any candidate and fails when that depth has
   * more than one, for the same reason [AndroidViewActions] does: the resolver refuses an ambiguous
   * selector, and taking the first of several equally close descendants would reinstate that guess.
   */
  private fun nearestWith(
    roots: List<SemanticsNode>,
    semanticsId: Int,
    capability: String,
    action: SemanticsPropertyKey<*>,
  ): Pair<Int, String?> {
    val resolved =
      findSemanticsNode(roots, semanticsId)
        ?: error(
          "Compose node $semanticsId is no longer in the semantics tree; the screen changed " +
            "between observation and action.",
        )
    if (resolved.config.contains(action)) return semanticsId to null

    var ancestor = resolved.parent
    while (ancestor != null) {
      if (ancestor.config.contains(action)) {
        return ancestor.id to resolved.relocationNote(capability, "ancestor", ancestor)
      }
      ancestor = ancestor.parent
    }

    val nearest = resolved.shallowestDescendantsWith { it.config.contains(action) }
    return when (nearest.size) {
      // Nothing in the chain owns the action. Act on the resolved node and let Compose's own error
      // name the missing action rather than paraphrasing it here.
      0 -> semanticsId to null
      1 -> nearest[0].id to resolved.relocationNote(capability, "descendant", nearest[0])
      else -> error(
        "Compose node $semanticsId is not $capability and ${nearest.size} of its descendants " +
          "are, all equally close: ${nearest.joinToString { it.id.toString() }}. Refusing to " +
          "pick one — narrow the selector to the node the action should land on.",
      )
    }
  }

  /**
   * Every descendant satisfying [predicate] at the shallowest depth where any does, so a capable
   * child that itself contains a capable grandchild still resolves to the child.
   */
  private fun SemanticsNode.shallowestDescendantsWith(
    predicate: (SemanticsNode) -> Boolean,
  ): List<SemanticsNode> {
    var level = children
    while (level.isNotEmpty()) {
      level.filter(predicate).takeIf { it.isNotEmpty() }?.let { return it }
      level = level.flatMap { it.children }
    }
    return emptyList()
  }

  private fun findSemanticsNode(roots: List<SemanticsNode>, semanticsId: Int): SemanticsNode? {
    fun walk(node: SemanticsNode): SemanticsNode? {
      if (node.id == semanticsId) return node
      node.children.forEach { child -> walk(child)?.let { return it } }
      return null
    }
    roots.forEach { root -> walk(root)?.let { return it } }
    return null
  }

  private fun SemanticsNode.relocationNote(
    capability: String,
    direction: String,
    site: SemanticsNode,
  ) = "Compose node $id is not $capability, so the action ran on its $direction ${site.id}."

  private fun AndroidComposeTestRule<*, *>.nodeWithId(semanticsId: Int): SemanticsNodeInteraction =
    onNode(
      SemanticsMatcher("semantics id is $semanticsId") { it.id == semanticsId },
      useUnmergedTree = true,
    )

  private fun AndroidTestTarget.requireComposeRule() =
    requireNotNull(composeTestRule) {
      "A Compose node was resolved but no AndroidComposeTestRule was supplied. Pass the rule " +
        "already installed by the app's test harness."
    }
}
