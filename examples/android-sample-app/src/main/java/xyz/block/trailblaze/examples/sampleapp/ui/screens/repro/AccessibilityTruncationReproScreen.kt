package xyz.block.trailblaze.examples.sampleapp.ui.screens.repro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A Compose screen that reproduces the accessibility "truncated/partial hierarchy" symptom on a
 * real `AndroidComposeView`, so the completeness gate can be exercised before/after against genuine
 * Compose semantics rather than only a synthetic `View` fixture.
 *
 * - [filled] = false → only a narrow column of content flush against the RIGHT edge, with the left
 *   ~80% of the screen empty. This is the shape a mid-commit Compose capture lands on (mirroring a
 *   chat where only the right-aligned messages were present) — the captured tree the gate must flag.
 * - [filled] = true → the same right-edge column PLUS the rest of the screen's content, filling the
 *   width: the fully-rendered tree the gate must leave alone.
 *
 * The state is driven deterministically by `SampleAppActivity`'s intent extras (no timer), so a
 * before/after capture is reproducible. (The *recovery* the gate performs when a quiet tree fills in
 * mid-capture is proven deterministically by the in-process late-fill on-device test; the gate is
 * geometry-based, so the same detection shown here is what drives that recovery.)
 *
 * Reached via `SampleAppActivity`'s `EXTRA_ACCESSIBILITY_TRUNCATION_REPRO` extra; off the normal nav.
 */
@Composable
fun AccessibilityTruncationReproScreen(filled: Boolean) {
  Box(Modifier.fillMaxSize().padding(top = 48.dp)) {
    // Always present: a narrow column jammed against the right edge. The labels `fillMaxWidth` of
    // the 96dp column so their accessibility bounds sit flush against the right screen edge (a
    // wrapped, left-aligned label would leave a gap and read as merely narrow, not edge-jammed).
    Column(Modifier.align(Alignment.TopEnd).width(96.dp)) {
      RIGHT_LABELS.forEach { Text(it, Modifier.fillMaxWidth()) }
    }
    // Present only when "filled": the rest of the content, spanning the width up to (but not over)
    // the right-edge column — so the two don't overlap and both stay visible, making a complete
    // tree that covers the screen. `padding(end = 96.dp)` reserves the right column's band.
    if (filled) {
      Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(end = 96.dp)) {
        REST_LABELS.forEach { Text(it, Modifier.fillMaxWidth()) }
      }
    }
  }
}

private val RIGHT_LABELS = listOf("Alpha", "Bravo", "Charlie", "Delta", "Foxtrot", "Golf")
private val REST_LABELS = listOf("Hotel", "India", "Juliet", "Kilo", "Lima", "Mike")
