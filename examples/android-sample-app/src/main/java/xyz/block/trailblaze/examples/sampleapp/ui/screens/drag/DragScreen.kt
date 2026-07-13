package xyz.block.trailblaze.examples.sampleapp.ui.screens.drag

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Height of the draggable chip, as a fraction of its drag container: large enough that a recorded
// touch-down anywhere in its top-docked span reliably lands on it without needing to know the
// exact on-device pixel bounds ahead of time.
private const val CHIP_HEIGHT_FRACTION = 0.4f

/**
 * A drag-and-drop surface: a chip docked at the top (Zone A) that the user drags down to Zone B.
 *
 * Deterministic coverage for the `dragByPoints` primitive (the recordable form `dragTo` delegates
 * to — #4764): `dragTo` is widely used across real-app trails but had no sample-app eval. Tracks
 * position as [dragFraction] — 0f (docked at top) to 1f (docked at bottom) — rather than raw
 * pixels, so the top/bottom-half zone decision this screen reports is correct regardless of the
 * actual on-device drag container height; only the recorded trail's absolute start/end pixel
 * coordinates need to match the target device's screen size (see the eval trail's header comment).
 * The "Current Zone" label lives in a header above the drag container (not inside it) so the
 * traveling chip can never visually cover — and so hide from the accessibility tree — the label a
 * verify step asserts against.
 */
@Composable
fun DragScreen() {
  var containerHeightPx by remember { mutableFloatStateOf(0f) }
  var dragFraction by remember { mutableFloatStateOf(0f) }
  var currentZone by remember { mutableStateOf("A") }

  Column(modifier = Modifier.fillMaxSize()) {
    Text(
      text = "Current Zone: $currentZone",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(16.dp).testTag("tv_current_zone"),
    )

    Box(
      modifier =
        Modifier.fillMaxSize().onGloballyPositioned {
          containerHeightPx = it.size.height.toFloat()
        }
    ) {
      Column(modifier = Modifier.fillMaxSize()) {
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFBBDEFB)),
          contentAlignment = Alignment.Center,
        ) {
          Text("Zone A", style = MaterialTheme.typography.headlineSmall)
        }
        Box(
          modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFFC8E6C9)),
          contentAlignment = Alignment.Center,
        ) {
          Text("Zone B", style = MaterialTheme.typography.headlineSmall)
        }
      }

      val chipHeightPx = containerHeightPx * CHIP_HEIGHT_FRACTION
      val travelPx = (containerHeightPx - chipHeightPx).coerceAtLeast(0f)
      // detectDragGestures' block runs once for the lifetime of this pointerInput(Unit) — its
      // onDrag/onDragEnd closures don't see later recompositions, so travelPx (which starts at 0f
      // before the first onGloballyPositioned) must be read through rememberUpdatedState rather
      // than captured directly, or the drag would be stuck using the pre-layout value forever.
      val latestTravelPx = rememberUpdatedState(travelPx)
      Box(
        modifier =
          Modifier.align(Alignment.TopCenter)
            .fillMaxWidth(0.8f)
            .height(with(LocalDensity.current) { chipHeightPx.toDp() })
            .offset { IntOffset(0, (dragFraction * travelPx).roundToInt()) }
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
              detectDragGestures(
                onDragEnd = { currentZone = if (dragFraction > 0.5f) "B" else "A" },
                onDragCancel = { currentZone = if (dragFraction > 0.5f) "B" else "A" },
              ) { change, dragAmount ->
                change.consume()
                val travel = latestTravelPx.value
                val fractionDelta = if (travel > 0f) dragAmount.y / travel else 0f
                dragFraction = (dragFraction + fractionDelta).coerceIn(0f, 1f)
              }
            },
        contentAlignment = Alignment.Center,
      ) {
        Text(
          "Drag Me",
          color = MaterialTheme.colorScheme.onPrimary,
          style = MaterialTheme.typography.titleMedium,
        )
      }
    }
  }
}
