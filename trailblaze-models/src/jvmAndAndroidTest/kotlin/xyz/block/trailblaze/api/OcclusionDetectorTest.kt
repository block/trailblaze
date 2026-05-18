package xyz.block.trailblaze.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-Kotlin unit tests for [OcclusionDetector] — synthetic inputs, no
 * platform-specific dependencies. The tests express the algorithm contract:
 * given a candidate + the paint stack at its center, what's the verdict?
 *
 * The supplier responsibility (producing the paint stack from native
 * primitives — `elementsFromPoint`, `UIView.hitTest`, etc.) is tested
 * end-to-end in each platform's integration suite.
 */
class OcclusionDetectorTest {

  private val viewport = TrailblazeNode.Bounds(left = 0, top = 0, right = 1000, bottom = 800)

  private fun rect(x: Int, y: Int, w: Int, h: Int) =
    TrailblazeNode.Bounds(left = x, top = y, right = x + w, bottom = y + h)

  private fun input(
    id: String,
    bounds: TrailblazeNode.Bounds,
    ancestorIds: List<String> = emptyList(),
    stack: List<OpaqueRegion> = emptyList(),
  ) = VisibilityInput(
    candidate = VisibilityCandidate(id = id, bounds = bounds, ancestorIds = ancestorIds),
    paintStackAtCenter = stack,
  )

  @Test
  fun `empty stack means visible (unknown verdict, fail-open)`() {
    // The supplier couldn't determine paint order (e.g., canvas-rendered page
    // or hit-test returned only html/body). Conservative default: don't filter.
    val result = OcclusionDetector.detect(
      inputs = listOf(input(id = "btn", bounds = rect(100, 100, 80, 30))),
      viewport = viewport,
    )

    assertEquals(emptySet(), result.offscreen)
    assertEquals(emptySet(), result.occluded)
  }

  @Test
  fun `topmost-is-self means visible`() {
    // The candidate's own opaque background is the topmost thing at its center
    // — that's the user looking at the candidate. Visible.
    val result = OcclusionDetector.detect(
      inputs = listOf(
        input(
          id = "btn",
          bounds = rect(100, 100, 80, 30),
          stack = listOf(OpaqueRegion(id = "btn")),
        ),
      ),
      viewport = viewport,
    )

    assertEquals(emptySet(), result.occluded)
  }

  @Test
  fun `topmost-is-unrelated means occluded`() {
    // The topmost element at the candidate's center is something other than
    // the candidate, its ancestor, or its descendant. Modal overlay over a
    // button — occluded.
    val result = OcclusionDetector.detect(
      inputs = listOf(
        input(
          id = "btn",
          bounds = rect(100, 100, 200, 100),
          stack = listOf(
            OpaqueRegion(id = "modal"),
            OpaqueRegion(id = "btn"), // own bg, painted underneath the modal
          ),
        ),
      ),
      viewport = viewport,
    )

    assertEquals(setOf("btn"), result.occluded)
  }

  @Test
  fun `topmost-is-ancestor means visible (painted on top of own background card)`() {
    // The supplier filtered the hit-test stack and the topmost opaque element
    // is the candidate's parent — i.e., the candidate is sitting on top of
    // its own opaque card background. Visible.
    val result = OcclusionDetector.detect(
      inputs = listOf(
        input(
          id = "link",
          bounds = rect(120, 140, 80, 20),
          ancestorIds = listOf("card"),
          stack = listOf(OpaqueRegion(id = "card", ancestorIds = emptyList())),
        ),
      ),
      viewport = viewport,
    )

    assertEquals(emptySet(), result.occluded)
  }

  @Test
  fun `topmost-is-descendant means visible (own icon paints on top)`() {
    // A button containing an opaque icon span: the span paints on top of the
    // button's center, but it's part of the button's own subtree, not an
    // occluder. Visible.
    val result = OcclusionDetector.detect(
      inputs = listOf(
        input(
          id = "btn",
          bounds = rect(50, 50, 100, 100),
          stack = listOf(OpaqueRegion(id = "span", ancestorIds = listOf("btn"))),
        ),
      ),
      viewport = viewport,
    )

    assertEquals(emptySet(), result.occluded)
  }

  @Test
  fun `viewport check fires before stack inspection`() {
    // Even with a giant opaque overlay covering everything, an offscreen
    // candidate (center outside the viewport) is classified offscreen, not
    // occluded. Mirrors Playwright's "offscreen elements bypass the occlusion
    // check entirely" integration test.
    val result = OcclusionDetector.detect(
      inputs = listOf(
        input(
          id = "below",
          bounds = rect(100, 900, 80, 30),
          stack = listOf(OpaqueRegion(id = "overlay")),
        ),
      ),
      viewport = viewport,
    )

    assertEquals(setOf("below"), result.offscreen)
    assertEquals(emptySet(), result.occluded)
  }

  @Test
  fun `zero-area candidate inside viewport is classified offscreen`() {
    // Regression guard: a (x, y, 0, 0) candidate's center is just (x, y),
    // which can be inside the viewport. Without an explicit zero-area
    // short-circuit it would fall through both offscreen and occluded checks
    // and stay visible to the LLM despite having no paintable surface.
    val degenerate = input(
      id = "deg",
      bounds = TrailblazeNode.Bounds(left = 100, top = 100, right = 100, bottom = 100),
    )
    val flatHorizontal = input(
      id = "flatX",
      bounds = TrailblazeNode.Bounds(left = 100, top = 100, right = 100, bottom = 200),
    )
    val flatVertical = input(
      id = "flatY",
      bounds = TrailblazeNode.Bounds(left = 100, top = 100, right = 200, bottom = 100),
    )

    val result = OcclusionDetector.detect(
      inputs = listOf(degenerate, flatHorizontal, flatVertical),
      viewport = viewport,
    )

    assertEquals(setOf("deg", "flatX", "flatY"), result.offscreen)
    assertEquals(emptySet(), result.occluded)
  }

  @Test
  fun `empty input list returns empty result and does not crash`() {
    val result = OcclusionDetector.detect(inputs = emptyList(), viewport = viewport)
    assertTrue(result.offscreen.isEmpty())
    assertTrue(result.occluded.isEmpty())
  }

  @Test
  fun `algorithm only inspects topmost — entries past the first are ignored`() {
    // If the supplier hands us a multi-entry stack, the algorithm picks the
    // first (topmost) and ignores the rest. The supplier is responsible for
    // ordering the stack correctly (topmost = visually on top at the center).
    val result = OcclusionDetector.detect(
      inputs = listOf(
        input(
          id = "btn",
          bounds = rect(100, 100, 80, 30),
          stack = listOf(
            // Topmost: unrelated modal → btn is occluded regardless of what's underneath.
            OpaqueRegion(id = "modal"),
            // Underneath, btn itself. The algorithm ignores this — only the
            // first entry matters.
            OpaqueRegion(id = "btn"),
          ),
        ),
      ),
      viewport = viewport,
    )

    assertEquals(setOf("btn"), result.occluded)
  }

  @Test
  fun `multi-candidate popup case — covered items occluded, popup contents visible`() {
    // Synthetic reproduction of the Square Dashboard Managerbot case: a popup
    // card covers part of a sidebar. Nav items above the popup get a paint
    // stack whose topmost is themselves; nav items behind the popup get the
    // popup card as topmost; popup-interior elements get their own ancestor
    // (the card) as topmost; an unstyled link inside the popup sees the card
    // (its ancestor) as topmost.
    val abovePopup = input(
      id = "nav-home",
      bounds = rect(143, 266, 1, 1),
      stack = listOf(OpaqueRegion(id = "nav-home")),
    )
    val behindPopup = (0 until 8).map { i ->
      input(
        id = "nav-buried-$i",
        bounds = rect(143, 380 + i * 48, 1, 1),
        stack = listOf(
          OpaqueRegion(id = "card"), // popup card paints on top
          OpaqueRegion(id = "nav-buried-$i"), // nav's own bg, underneath
        ),
      )
    }
    val popupButton = input(
      id = "get-started",
      bounds = rect(90, 575, 1, 1),
      ancestorIds = listOf("card"),
      stack = listOf(
        OpaqueRegion(id = "get-started", ancestorIds = listOf("card")),
        OpaqueRegion(id = "card"),
      ),
    )
    val termsLink = input(
      id = "terms",
      bounds = rect(40, 640, 100, 16),
      ancestorIds = listOf("card"),
      stack = listOf(OpaqueRegion(id = "card")),
    )

    val result = OcclusionDetector.detect(
      inputs = listOf(abovePopup) + behindPopup + listOf(popupButton, termsLink),
      viewport = viewport,
    )

    assertTrue("nav-home" !in result.occluded, "Home is above the popup, must stay visible")
    for (i in 0 until 8) {
      assertTrue(
        "nav-buried-$i" in result.occluded,
        "buried nav $i should be occluded by the popup card, but result=$result",
      )
    }
    assertTrue("get-started" !in result.occluded, "Get started should be visible: $result")
    assertTrue("terms" !in result.occluded, "Terms link should be visible (card is ancestor): $result")
  }
}
