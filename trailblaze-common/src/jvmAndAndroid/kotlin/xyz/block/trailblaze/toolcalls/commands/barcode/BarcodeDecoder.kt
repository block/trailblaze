package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * Finds and decodes a barcode in a **device screenshot**, which is the hard case ZXing is not
 * tuned for out of the box: the barcode is usually a small part of a large image whose UI chrome
 * (status-bar icons, favicons, app iconography) is full of high-contrast squares that ZXing's
 * finder-pattern detector locks onto before it ever reaches the real code.
 *
 * Symbology is auto-detected within an allow-list, never across every format ZXing supports —
 * see [TWO_DIMENSIONAL_FORMATS].
 */
object BarcodeDecoder {

  /**
   * The default allow-list: matrix symbologies only.
   *
   * ZXing's linear (1D) readers scan for runs of light and dark bars, and a screenshot is full of
   * accidental ones — a row of list separators, a progress bar, or the ringing around a
   * compression artifact. Left to auto-detect across all formats, they do not merely add noise:
   * they **win**, and `decode` returns a confident wrong answer. `BarcodeDecoderTest` pins the
   * case that proved it, where scanning a QR screenshot at the resolution and quality the drivers
   * actually deliver returned an 8-digit number instead of the QR code's URL.
   *
   * A wrong value is worse than no value here, because the caller stores it in memory and asserts
   * on it. So linear formats are opt-in via [LINEAR_FORMATS], for callers who know which
   * symbology they are looking at.
   */
  val TWO_DIMENSIONAL_FORMATS: List<BarcodeFormat> = listOf(
    BarcodeFormat.QR_CODE,
    BarcodeFormat.DATA_MATRIX,
    BarcodeFormat.AZTEC,
    BarcodeFormat.PDF_417,
  )

  /** Retail/label symbologies. Opt-in — see [TWO_DIMENSIONAL_FORMATS] for why. */
  val LINEAR_FORMATS: List<BarcodeFormat> = listOf(
    BarcodeFormat.UPC_A,
    BarcodeFormat.UPC_E,
    BarcodeFormat.EAN_8,
    BarcodeFormat.EAN_13,
    BarcodeFormat.CODE_128,
    BarcodeFormat.CODE_39,
    BarcodeFormat.CODE_93,
    BarcodeFormat.ITF,
    BarcodeFormat.CODABAR,
  )

  /** How far each tile reaches into its neighbours, as a fraction of tile size. */
  private const val TILE_OVERLAP_FRACTION = 0.25

  /**
   * Tile grid used by the fallback scan. Portrait-ish because screenshots are: 2 across, 3 down
   * lands each tile in roughly barcode-friendly proportions on a phone or tablet screen.
   */
  private const val TILE_COLUMNS = 2
  private const val TILE_ROWS = 3

  /** What [decode] tried, for a failure message that says more than "not found". */
  data class Attempt(val regionsScanned: Int, val tiled: Boolean)

  /**
   * Decodes the barcode in [source], escalating until something reads:
   *
   * 1. the whole image, and
   * 2. an overlapping [TILE_COLUMNS]×[TILE_ROWS] grid of tiles — isolating the barcode from
   *    distant decoy patterns, and incidentally magnifying it relative to its own frame. The
   *    overlap is what keeps a barcode straddling a tile boundary decodable.
   *
   * Each attempt is tried under two binarizers (see [decodeSingle]).
   *
   * @param formats symbologies to consider; defaults to [TWO_DIMENSIONAL_FORMATS].
   * @param onAttempt receives what was scanned, whether or not a barcode was found.
   * @return the decoded text content.
   * @throws NotFoundException if no barcode is found anywhere in the image.
   */
  fun decode(
    source: LuminanceSource,
    formats: List<BarcodeFormat> = TWO_DIMENSIONAL_FORMATS,
    onAttempt: (Attempt) -> Unit = {},
  ): String {
    require(formats.isNotEmpty()) { "At least one barcode format must be allowed." }
    val hints = mapOf<DecodeHintType, Any>(
      DecodeHintType.TRY_HARDER to true,
      // Without this, every ZXing reader participates and the linear ones hallucinate.
      DecodeHintType.POSSIBLE_FORMATS to formats,
    )

    decodeSingle(source, hints)?.let {
      onAttempt(Attempt(regionsScanned = 1, tiled = false))
      return it
    }

    // A LuminanceSource is only croppable if its implementation says so; the base class throws.
    if (!source.isCropSupported) {
      onAttempt(Attempt(regionsScanned = 1, tiled = false))
      throw NotFoundException.getNotFoundInstance()
    }

    var regionsScanned = 1
    for (region in tiles(source)) {
      regionsScanned++
      decodeSingle(source.crop(region.x, region.y, region.width, region.height), hints)?.let {
        onAttempt(Attempt(regionsScanned = regionsScanned, tiled = true))
        return it
      }
    }
    onAttempt(Attempt(regionsScanned = regionsScanned, tiled = true))
    throw NotFoundException.getNotFoundInstance()
  }

  /**
   * One decode attempt over exactly the pixels given, under both ZXing binarizers, returning null
   * rather than throwing when nothing reads.
   *
   * [HybridBinarizer] thresholds locally and handles the uneven brightness of a real screen well;
   * [GlobalHistogramBinarizer] uses a single global threshold and does better on the flat,
   * synthetic contrast of a barcode rendered on a solid background. Neither dominates the other
   * on screenshots, so both are tried.
   */
  private fun decodeSingle(source: LuminanceSource, hints: Map<DecodeHintType, Any>): String? {
    for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
      val text = try {
        MultiFormatReader().decode(BinaryBitmap(binarizer), hints).text
      } catch (_: ReaderException) {
        // No barcode under this binarizer.
        null
      } catch (_: ArrayIndexOutOfBoundsException) {
        // ZXing's readers index into the bit matrix from candidate patterns and occasionally run
        // off the end on UI noise. Treated as "nothing here" so one bad region cannot abort a
        // scan that would have succeeded on a later one.
        null
      }
      if (text != null) return text
    }
    return null
  }

  private data class Tile(val x: Int, val y: Int, val width: Int, val height: Int)

  private fun tiles(source: LuminanceSource): List<Tile> {
    val tileWidth = source.width / TILE_COLUMNS
    val tileHeight = source.height / TILE_ROWS
    if (tileWidth <= 0 || tileHeight <= 0) return emptyList()
    val overlapX = (tileWidth * TILE_OVERLAP_FRACTION).toInt()
    val overlapY = (tileHeight * TILE_OVERLAP_FRACTION).toInt()

    return buildList {
      for (row in 0 until TILE_ROWS) {
        for (column in 0 until TILE_COLUMNS) {
          val x = maxOf(0, column * tileWidth - overlapX)
          val y = maxOf(0, row * tileHeight - overlapY)
          // The last row/column runs to the edge so integer division cannot leave an unscanned
          // strip of up to TILE_ROWS-1 pixels along the bottom or right.
          val right =
            if (column == TILE_COLUMNS - 1) source.width else minOf(source.width, x + tileWidth + overlapX)
          val bottom =
            if (row == TILE_ROWS - 1) source.height else minOf(source.height, y + tileHeight + overlapY)
          if (right > x && bottom > y) add(Tile(x, y, right - x, bottom - y))
        }
      }
    }
  }
}
