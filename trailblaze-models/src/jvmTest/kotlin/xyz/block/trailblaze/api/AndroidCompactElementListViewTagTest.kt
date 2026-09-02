package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The compact hierarchy is what a selector gets authored from, so every identifier it prints has to
 * be one the resolver can actually match. `resourceIdRegex` and `tagRegex` are separate fields:
 * printing a `View` tag as `[id=…]` reads as a resource id and yields a selector that matches
 * nothing.
 */
class AndroidCompactElementListViewTagTest {

  private var nextId = 0L

  private fun node(
    detail: DriverNodeDetail.AndroidView,
    children: List<TrailblazeNode> = emptyList(),
  ): TrailblazeNode = TrailblazeNode(
    nodeId = nextId++,
    driverDetail = detail,
    bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
    children = children,
  )

  private fun button(label: String) = node(
    DriverNodeDetail.AndroidView(
      className = "android.widget.Button",
      text = label,
      isClickable = true,
    ),
  )

  @Test
  fun `a tag renders in its own slot, not as a resource id`() {
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout"),
      children = listOf(
        node(
          DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout", tag = "row_alpha"),
          children = listOf(button("Pick Me")),
        ),
      ),
    )

    val text = AndroidCompactElementList.build(root).text

    assertContains(text, "[tag=row_alpha]")
    assertFalse("[id=row_alpha]" in text, "A tag must not be reported as a resource id:\n$text")
  }

  @Test
  fun `a resource id still renders as an id`() {
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout"),
      children = listOf(
        node(
          DriverNodeDetail.AndroidView(
            className = "android.widget.Button",
            resourceId = "com.example:id/submit",
            text = "Submit",
            isClickable = true,
          ),
        ),
      ),
    )

    assertContains(AndroidCompactElementList.build(root).text, "[id=com.example:id/submit]")
  }

  @Test
  fun `tagged wrappers survive so identical siblings stay distinguishable`() {
    // Two buttons with the same label. Nothing about the buttons tells them apart — only the tag
    // on the row each one sits in. If the rows collapse as structural, the hierarchy offers no
    // way to author a selector for either button.
    val root = node(
      DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout"),
      children = listOf(
        node(
          DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout", tag = "row_alpha"),
          children = listOf(button("Pick Me")),
        ),
        node(
          DriverNodeDetail.AndroidView(className = "android.widget.LinearLayout", tag = "row_beta"),
          children = listOf(button("Pick Me")),
        ),
      ),
    )

    val text = AndroidCompactElementList.build(root).text

    assertContains(text, "[tag=row_alpha]")
    assertContains(text, "[tag=row_beta]")
    assertEquals(2, text.lines().count { "Pick Me" in it }, "Both buttons should still be listed:\n$text")
  }
}
