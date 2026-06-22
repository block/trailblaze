package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TreeSignatureTest {

  private data class Node(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val className: CharSequence? = "android.view.View",
    val text: CharSequence? = null,
  )

  private val seed = 1125899906842597L

  private fun fold(nodes: List<Node>): Long {
    var acc = seed
    for (n in nodes) {
      acc = mixNodeIntoSignature(acc, n.left, n.top, n.right, n.bottom, n.className, n.text)
    }
    return acc
  }

  @Test
  fun deterministic_sameInputsSameHash() {
    val a = mixNodeIntoSignature(seed, 0, 0, 100, 50, "Button", "Charge")
    val b = mixNodeIntoSignature(seed, 0, 0, 100, 50, "Button", "Charge")
    assertEquals(a, b)
  }

  @Test
  fun boundsSensitive_eachEdgeChangesHash() {
    val base = mixNodeIntoSignature(seed, 0, 0, 100, 50, "Button", "Charge")
    assertNotEquals(base, mixNodeIntoSignature(seed, 1, 0, 100, 50, "Button", "Charge"))
    assertNotEquals(base, mixNodeIntoSignature(seed, 0, 1, 100, 50, "Button", "Charge"))
    assertNotEquals(base, mixNodeIntoSignature(seed, 0, 0, 101, 50, "Button", "Charge"))
    assertNotEquals(base, mixNodeIntoSignature(seed, 0, 0, 100, 51, "Button", "Charge"))
  }

  @Test
  fun oneFramePixelShiftChangesSignature() {
    val settled = fold(listOf(Node(0, 0, 100, 50), Node(0, 200, 300, 260, text = "Sheet")))
    val animating = fold(listOf(Node(0, 0, 100, 50), Node(0, 199, 300, 259, text = "Sheet")))
    assertNotEquals(settled, animating)
  }

  @Test
  fun stableWhenUnchanged() {
    val frame = listOf(Node(0, 0, 100, 50, text = "A"), Node(0, 60, 100, 110, text = "B"))
    assertEquals(fold(frame), fold(frame.map { it.copy() }))
  }

  @Test
  fun classNameSensitive() {
    val a = mixNodeIntoSignature(seed, 0, 0, 10, 10, "android.widget.Button", null)
    val b = mixNodeIntoSignature(seed, 0, 0, 10, 10, "android.widget.TextView", null)
    assertNotEquals(a, b)
  }

  @Test
  fun textLengthSensitive_andNullSafe() {
    val nullText = mixNodeIntoSignature(seed, 0, 0, 10, 10, "View", null)
    val emptyText = mixNodeIntoSignature(seed, 0, 0, 10, 10, "View", "")
    val someText = mixNodeIntoSignature(seed, 0, 0, 10, 10, "View", "hi")
    assertEquals(nullText, emptyText)
    assertNotEquals(nullText, someText)
  }

  @Test
  fun orderSensitive_nodeReorderChangesSignature() {
    val forward = fold(listOf(Node(0, 0, 10, 10, text = "A"), Node(0, 20, 10, 30, text = "B")))
    val reversed = fold(listOf(Node(0, 20, 10, 30, text = "B"), Node(0, 0, 10, 10, text = "A")))
    assertNotEquals(forward, reversed)
  }

  @Test
  fun nodeCountSensitive_droppedNodeChangesSignature() {
    val full = fold(listOf(Node(0, 0, 10, 10), Node(0, 20, 10, 30), Node(0, 40, 10, 50)))
    val dropped = fold(listOf(Node(0, 0, 10, 10), Node(0, 40, 10, 50)))
    assertNotEquals(full, dropped)
  }

  @Test
  fun classNameNormalizedAcrossCharSequenceImpls() {
    // AccessibilityNodeInfo.className is a CharSequence; non-String impls use identity hashCode,
    // so equal-content instances must still hash identically (regression guard for .toString()).
    val a: CharSequence = StringBuilder("android.widget.Button")
    val b: CharSequence = StringBuilder("android.widget.Button")
    assertNotEquals(a.hashCode(), b.hashCode())
    assertEquals(
      mixNodeIntoSignature(seed, 0, 0, 10, 10, a, null),
      mixNodeIntoSignature(seed, 0, 0, 10, 10, b, null),
    )
  }
}
