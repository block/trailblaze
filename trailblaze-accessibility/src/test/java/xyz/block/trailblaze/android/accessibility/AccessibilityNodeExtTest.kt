package xyz.block.trailblaze.android.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
