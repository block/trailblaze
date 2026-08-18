package xyz.block.trailblaze.android.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure-function helpers in [AccessibilityNodeExt].
 *
 * The full [AccessibilityNodeInfo]-to-[AccessibilityNode] conversion needs the
 * Android framework to instantiate an [AccessibilityNodeInfo], so we only test
 * the decision logic that was extracted into pure helpers.
 */
class AccessibilityNodeExtTest {

  @Test
  fun `isTextAcceptingNode returns true when isEditable`() {
    val result = isTextAcceptingNode(
      isEditable = true,
      className = "android.view.View",
      actionIds = emptyList(),
    )
    assertTrue(result)
  }

  @Test
  fun `isTextAcceptingNode returns true for EditText even without isEditable`() {
    val result = isTextAcceptingNode(
      isEditable = false,
      className = "android.widget.EditText",
      actionIds = emptyList(),
    )
    assertTrue(result)
  }

  @Test
  fun `isTextAcceptingNode returns true for ACTION_SET_TEXT alone`() {
    // The Google Contacts case: Compose text field exposed as android.view.View
    // with only ACTION_SET_TEXT in its action list.
    val result = isTextAcceptingNode(
      isEditable = false,
      className = "android.view.View",
      actionIds = listOf(AccessibilityNodeInfo.ACTION_SET_TEXT),
    )
    assertTrue(result)
  }

  @Test
  fun `isTextAcceptingNode returns true when ACTION_SET_TEXT is mixed with other actions`() {
    val result = isTextAcceptingNode(
      isEditable = false,
      className = "android.view.View",
      actionIds = listOf(
        AccessibilityNodeInfo.ACTION_FOCUS,
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        AccessibilityNodeInfo.ACTION_CLICK,
      ),
    )
    assertTrue(result)
  }

  @Test
  fun `isTextAcceptingNode returns false for plain View with no text-input signals`() {
    val result = isTextAcceptingNode(
      isEditable = false,
      className = "android.view.View",
      actionIds = listOf(AccessibilityNodeInfo.ACTION_CLICK),
    )
    assertFalse(result)
  }

  @Test
  fun `isTextAcceptingNode returns false for TextView with no text-input signals`() {
    // Guards against accidentally treating a plain TextView as editable.
    val result = isTextAcceptingNode(
      isEditable = false,
      className = "android.widget.TextView",
      actionIds = emptyList(),
    )
    assertFalse(result)
  }

  @Test
  fun `isTextAcceptingNode returns false for null className and empty actions`() {
    val result = isTextAcceptingNode(
      isEditable = false,
      className = null,
      actionIds = emptyList(),
    )
    assertFalse(result)
  }

  // --- standardActionName ---
  // These guard the stable-action-name contract. For every known action ID,
  // `standardActionName` must return the Android constant name (ACTION_CLICK,
  // ACTION_SET_TEXT, etc.) rather than null. Callers fall back to the action's
  // user-facing label only for IDs that are NOT in the known set — otherwise a
  // Compose app overriding the semantic label via
  // `Modifier.semantics { onClick(label = "Add to cart") }` would make the same
  // logical click action serialize under different names on different screens,
  // producing diff-noisy snapshots and breaking any downstream consumer
  // (inspector UI, selector generator, log analysis) that looks for a fixed
  // constant name.

  @Test
  fun `standardActionName returns ACTION_CLICK for ACTION_CLICK id`() {
    assertEquals("ACTION_CLICK", standardActionName(AccessibilityNodeInfo.ACTION_CLICK))
  }

  @Test
  fun `standardActionName returns ACTION_SET_TEXT for ACTION_SET_TEXT id`() {
    assertEquals("ACTION_SET_TEXT", standardActionName(AccessibilityNodeInfo.ACTION_SET_TEXT))
  }

  // --- largestLineRunBounds ---
  // Per-character location entries can be null for characters the platform reports no
  // visible location for (scrolled-out, ellipsized); a run must skip them and only
  // fail when nothing in the range is visible.

  @Test
  fun `largestLineRunBounds unions the visible character rects of a single-line range`() {
    val bounds = largestLineRunBounds(
      listOf(
        AccessibilityNode.Bounds(left = 239, top = 728, right = 250, bottom = 748),
        null,
        AccessibilityNode.Bounds(left = 250, top = 727, right = 262, bottom = 747),
        AccessibilityNode.Bounds(left = 330, top = 728, right = 341, bottom = 748),
      ),
    )
    assertEquals(AccessibilityNode.Bounds(left = 239, top = 727, right = 341, bottom = 748), bounds)
  }

  @Test
  fun `largestLineRunBounds keeps only the largest fragment of a wrapped range`() {
    // A wrapped link's whole-range union would cover plain text and whitespace between the
    // line fragments; the child's clickable rect must contain only link characters.
    val bounds = largestLineRunBounds(
      listOf(
        AccessibilityNode.Bounds(left = 500, top = 100, right = 540, bottom = 120),
        AccessibilityNode.Bounds(left = 540, top = 100, right = 560, bottom = 120),
        AccessibilityNode.Bounds(left = 40, top = 130, right = 80, bottom = 150),
        AccessibilityNode.Bounds(left = 80, top = 130, right = 120, bottom = 150),
        AccessibilityNode.Bounds(left = 120, top = 130, right = 180, bottom = 150),
      ),
    )
    assertEquals(AccessibilityNode.Bounds(left = 40, top = 130, right = 180, bottom = 150), bounds)
  }

  @Test
  fun `largestLineRunBounds returns null when no character has a visible location`() {
    assertEquals(null, largestLineRunBounds(listOf(null, null)))
    assertEquals(null, largestLineRunBounds(emptyList()))
  }

  // --- pickTextLinkSpanIndex ---
  // Disambiguates same-text spans within one node: the tap must activate the span whose
  // on-screen range the resolved child's center falls in, not blindly the first in span order.

  @Test
  fun `pickTextLinkSpanIndex picks the candidate whose bounds contain the tap point`() {
    val index = pickTextLinkSpanIndex(
      candidateBounds = listOf(
        AccessibilityNode.Bounds(left = 106, top = 689, right = 194, bottom = 705),
        AccessibilityNode.Bounds(left = 106, top = 920, right = 194, bottom = 936),
      ),
      targetX = 150,
      targetY = 928,
    )
    assertEquals(1, index)
  }

  @Test
  fun `pickTextLinkSpanIndex returns null when no bounds contain the point`() {
    // Click-time bounds re-derivation failed for every candidate: position can't tell the
    // duplicates apart, and clicking an arbitrary one risks activating the wrong link.
    assertNull(pickTextLinkSpanIndex(listOf(null, null), targetX = 150, targetY = 928))
    // Bounds known but the point sits between them (e.g. stale capture): still ambiguous,
    // so the caller must miss and gesture-fall-back at the resolved child's coordinates.
    assertNull(
      pickTextLinkSpanIndex(
        candidateBounds = listOf(
          AccessibilityNode.Bounds(left = 106, top = 689, right = 194, bottom = 705),
          AccessibilityNode.Bounds(left = 106, top = 920, right = 194, bottom = 936),
        ),
        targetX = 150,
        targetY = 800,
      ),
    )
  }

  // --- textLinkChildNodes ---

  @Test
  fun `textLinkChildNodes builds an addressable clickable child per span`() {
    val counter = NodeIdCounter()
    counter.next() // an id another node in the tree already consumed (at the real call site,
    // children draw from the counter before their parent does)

    val children = textLinkChildNodes(
      specs = listOf(
        TextLinkSpec(text = "Privacy Notice", bounds = AccessibilityNode.Bounds(106, 689, 194, 705)),
        TextLinkSpec(text = "Terms of Service", bounds = AccessibilityNode.Bounds(207, 689, 308, 705)),
      ),
      parentClassName = "android.widget.TextView",
      parentPackageName = "com.example.app",
      parentIsVisibleToUser = true,
      parentIsEnabled = true,
      nodeIdCounter = counter,
    )

    assertEquals(2, children.size)
    val tos = children[1]
    assertEquals("Terms of Service", tos.text)
    assertEquals(AccessibilityNode.Bounds(207, 689, 308, 705), tos.boundsInScreen)
    assertEquals("android.widget.TextView", tos.className)
    assertTrue(tos.isClickable, "link child must be clickable so it gets its own element ref")
    assertTrue(tos.isTextLink, "link child must carry the flag that drives the span-click tap route")
    assertTrue(tos.actions.isEmpty(), "no ACTION_CLICK: the child is synthetic, so the ACTION_CLICK route must decline it")
    assertEquals(listOf(2L, 3L), children.map { it.nodeId }, "ids continue the parent capture's counter")
  }

  @Test
  fun `textLinkChildNodes emits no child when character locations were unavailable`() {
    // A clickable child would need fabricated (whole-paragraph) bounds, which poisons
    // recorded-tap hit-testing: it either shadows the paragraph or loses the equal-bounds
    // tie to it, depending on whether the parent is interactive.
    val children = textLinkChildNodes(
      specs = listOf(
        TextLinkSpec(text = "Open Source Software", bounds = null),
        TextLinkSpec(text = "Privacy Notice", bounds = AccessibilityNode.Bounds(106, 689, 194, 705)),
      ),
      parentClassName = "android.widget.TextView",
      parentPackageName = "com.example.app",
      parentIsVisibleToUser = true,
      parentIsEnabled = true,
      nodeIdCounter = NodeIdCounter(),
    )
    assertEquals(listOf("Privacy Notice"), children.map { it.text })
  }

  @Test
  fun `textLinkChildNodes carries the parent's disabled state onto each child`() {
    // A span inside a disabled TextView must not present as an enabled clickable child:
    // the span-click tap route gates on isEnabled, matching how ACTION_CLICK routing
    // treats disabled controls.
    val children = textLinkChildNodes(
      specs = listOf(
        TextLinkSpec(text = "Privacy Notice", bounds = AccessibilityNode.Bounds(106, 689, 194, 705)),
      ),
      parentClassName = "android.widget.TextView",
      parentPackageName = "com.example.app",
      parentIsVisibleToUser = true,
      parentIsEnabled = false,
      nodeIdCounter = NodeIdCounter(),
    )
    assertEquals(listOf(false), children.map { it.isEnabled })
  }
}
