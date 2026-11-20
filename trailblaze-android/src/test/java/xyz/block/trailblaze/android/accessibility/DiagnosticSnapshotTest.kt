package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticSnapshotTest {
  @Test
  fun `an observation can see feedback that expires during diagnostic capture`() {
    var feedbackVisible = true
    val (snapshot, visible) = runWithDiagnosticSnapshot(
      observeFirst = true,
      capture = {
        feedbackVisible = false
        "later screen"
      },
      execute = { feedbackVisible },
    )

    assertTrue(visible)
    assertEquals("later screen", snapshot)
    assertFalse(feedbackVisible)
  }

  @Test
  fun `diagnostic capture cannot turn missing feedback into a passing observation`() {
    val (snapshot, visible) = runWithDiagnosticSnapshot(
      observeFirst = true,
      capture = { "failure screen" },
      execute = { false },
    )

    assertFalse(visible)
    assertEquals("failure screen", snapshot)
  }

  @Test
  fun `an input keeps the screen it acted on instead of its destination`() {
    var screen = "source"
    val (snapshot, outcome) = runWithDiagnosticSnapshot(
      observeFirst = false,
      capture = { screen },
      execute = {
        screen = "destination"
        "input delivered"
      },
    )

    assertEquals("source", snapshot)
    assertEquals("destination", screen)
    assertEquals("input delivered", outcome)
  }
}
