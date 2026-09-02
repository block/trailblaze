package xyz.block.trailblaze.android.test.tools

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * The summary attached to a "matched no element" failure.
 *
 * It is written into CI logs and the persisted step log, so what it is allowed to say about a
 * password field is a contract rather than a formatting preference.
 */
class OnScreenSummaryTest {

  @Test
  fun `a password field's value never reaches the summary`() {
    val summary = onScreenSummary(
      root(
        node(
          id = 2,
          bounds = bounds(0, 0, 200, 60),
          detail = DriverNodeDetail.AndroidView(
            className = "android.widget.EditText",
            text = "hunter2",
            hintText = "Password",
            isPassword = true,
          ),
        ),
      ),
    )

    assertFalse(summary.contains("hunter2"), "Summary leaked the password value: $summary")
    // The field itself still has to be listed, or a trail author cannot tell a wrong selector from
    // a wrong screen — which is the whole reason this summary exists.
    assertContains(summary, "hintText=\"Password\"")
    assertContains(summary, "text=\"<redacted>\"")
  }

  @Test
  fun `a compose password field is redacted through its editable text too`() {
    val summary = onScreenSummary(
      root(
        node(
          id = 2,
          bounds = bounds(0, 0, 200, 60),
          detail = DriverNodeDetail.Compose(
            editableText = "hunter2",
            testTag = "password-field",
            isPassword = true,
          ),
        ),
      ),
    )

    assertFalse(summary.contains("hunter2"), "Summary leaked the password value: $summary")
    assertContains(summary, "testTag=\"password-field\"")
  }

  /** Redaction is scoped to password fields — an ordinary field still reports what it holds. */
  @Test
  fun `an ordinary text field still reports its value`() {
    val summary = onScreenSummary(
      root(
        node(
          id = 2,
          bounds = bounds(0, 0, 200, 60),
          detail = DriverNodeDetail.AndroidView(
            className = "android.widget.EditText",
            text = "rodion@example.com",
          ),
        ),
      ),
    )

    assertContains(summary, "text=\"rodion@example.com\"")
  }

  /**
   * A node with no on-screen area is not something a selector can be pointed at, so listing it
   * would pad the report with rows a trail author cannot act on.
   */
  @Test
  fun `an element with no bounds is left out`() {
    val summary = onScreenSummary(
      root(
        node(
          id = 2,
          bounds = bounds(0, 0, 0, 0),
          detail = DriverNodeDetail.Compose(text = "Not laid out"),
        ),
        node(
          id = 3,
          bounds = bounds(0, 0, 100, 40),
          detail = DriverNodeDetail.Compose(text = "Laid out"),
        ),
      ),
    )

    assertFalse(summary.contains("Not laid out"), "Summary listed an unplaced node: $summary")
    assertContains(summary, "Laid out")
    assertTrue(summary.startsWith("On screen (1 identifiable elements)"), summary)
  }

  private fun root(vararg children: TrailblazeNode) = TrailblazeNode(
    1,
    children = children.toList(),
    bounds = bounds(0, 0, 400, 800),
    driverDetail = DriverNodeDetail.AndroidView(className = "android.widget.FrameLayout"),
  )

  private fun node(id: Long, bounds: TrailblazeNode.Bounds, detail: DriverNodeDetail) =
    TrailblazeNode(id, children = emptyList(), bounds = bounds, driverDetail = detail)

  private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
    TrailblazeNode.Bounds(left, top, right, bottom)
}
