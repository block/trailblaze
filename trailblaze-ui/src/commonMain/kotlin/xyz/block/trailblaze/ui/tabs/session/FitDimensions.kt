package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.ui.unit.Dp

/**
 * Compute the rendered dimensions for an image fitted into a container, preserving aspect ratio.
 * Returns (renderedWidth, renderedHeight).
 */
internal fun computeFitDimensions(
  imageAspect: Float,
  containerWidth: Dp,
  containerHeight: Dp,
): Pair<Dp, Dp> {
  val containerAspect = containerWidth / containerHeight
  val renderedWidth =
    if (imageAspect > containerAspect) containerWidth else containerHeight * imageAspect
  val renderedHeight =
    if (imageAspect > containerAspect) containerWidth / imageAspect else containerHeight
  return renderedWidth to renderedHeight
}
