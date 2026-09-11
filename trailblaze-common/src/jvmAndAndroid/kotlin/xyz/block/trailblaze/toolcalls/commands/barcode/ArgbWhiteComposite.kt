package xyz.block.trailblaze.toolcalls.commands.barcode

/**
 * Flattens semi-transparent ARGB pixels onto an opaque white background, in place.
 *
 * A QR code drawn on a transparent background is common in app UI (an overlay, or an icon asset
 * reused as a payment code). ZXing reads only luminance, and an un-composited transparent pixel
 * carries whatever colour sits under the alpha — usually zero, i.e. **black** — so a code that is
 * perfectly legible on screen becomes a black rectangle to the decoder and stops being found.
 * White is the right background because it is what a barcode's own quiet zone is.
 *
 * Shared rather than living in the Android actual because it is pure [IntArray] arithmetic with no
 * platform dependency, and because it is the only branching logic on that path: the JVM actual
 * reaches the same result through `Graphics2D`, so keeping this testable is what makes the
 * cross-platform parity claim checkable instead of asserted. `ArgbWhiteCompositeTest` runs on both
 * the JVM and Android, and `ScreenshotLuminanceSourceTest` pins it against the JVM's
 * `Graphics2D` composite.
 *
 * Pixels that are already fully opaque are left untouched, so a screenshot with no alpha (the
 * common case) costs one comparison per pixel and no writes.
 */
internal fun compositeOntoWhite(pixels: IntArray) {
  for (i in pixels.indices) {
    val pixel = pixels[i]
    val alpha = pixel ushr 24
    if (alpha == OPAQUE) continue
    val inverse = OPAQUE - alpha
    val red = ((pixel shr 16 and 0xFF) * alpha + OPAQUE * inverse) / OPAQUE
    val green = ((pixel shr 8 and 0xFF) * alpha + OPAQUE * inverse) / OPAQUE
    val blue = ((pixel and 0xFF) * alpha + OPAQUE * inverse) / OPAQUE
    pixels[i] = (OPAQUE shl 24) or (red shl 16) or (green shl 8) or blue
  }
}

private const val OPAQUE = 0xFF
