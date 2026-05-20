package xyz.block.trailblaze.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxArtifactSizeTest {

  @Test
  fun `parseSize accepts plain bytes`() {
    assertEquals(1L, MaxArtifactSize.parseSize("1"))
    assertEquals(1024L, MaxArtifactSize.parseSize("1024"))
    assertEquals(1024000L, MaxArtifactSize.parseSize("1024000"))
  }

  @Test
  fun `parseSize accepts SI-style and IEC-style unit suffixes interchangeably`() {
    // Binary (1024-based) multipliers throughout, regardless of suffix spelling.
    val mb = 1024L * 1024L
    assertEquals(10L * mb, MaxArtifactSize.parseSize("10M"))
    assertEquals(10L * mb, MaxArtifactSize.parseSize("10MB"))
    assertEquals(10L * mb, MaxArtifactSize.parseSize("10MiB"))
    assertEquals(10L * mb, MaxArtifactSize.parseSize("10mb"))
    assertEquals(5L * 1024L, MaxArtifactSize.parseSize("5K"))
    assertEquals(5L * 1024L, MaxArtifactSize.parseSize("5KB"))
    assertEquals(1L * 1024L * 1024L * 1024L, MaxArtifactSize.parseSize("1G"))
  }

  @Test
  fun `parseSize is case-insensitive across all suffixes`() {
    // Lower-case suffixes should resolve identically to upper-case — the parser
    // uppercases internally, but a regression on that path would silently fall through
    // to the "unknown unit" branch and reject otherwise-valid inputs like `5kb`.
    val k = 1024L
    val m = 1024L * 1024L
    val g = 1024L * 1024L * 1024L
    assertEquals(5L * k, MaxArtifactSize.parseSize("5kb"))
    assertEquals(5L * k, MaxArtifactSize.parseSize("5kib"))
    assertEquals(10L * m, MaxArtifactSize.parseSize("10mib"))
    assertEquals(1L * g, MaxArtifactSize.parseSize("1gb"))
  }

  @Test
  fun `parseSize accepts decimal values`() {
    // 1.5 MB = 1.5 * 1024 * 1024 = 1572864
    assertEquals(1572864L, MaxArtifactSize.parseSize("1.5MB"))
  }

  @Test
  fun `parseSize accepts whitespace between number and unit`() {
    val mb = 1024L * 1024L
    assertEquals(10L * mb, MaxArtifactSize.parseSize("10 MB"))
    assertEquals(10L * mb, MaxArtifactSize.parseSize("  10MB  "))
  }

  @Test
  fun `parseSize rejects empty string`() {
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("") }
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("   ") }
  }

  @Test
  fun `parseSize rejects garbage and unknown units`() {
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("ten megabytes") }
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("10TB") }
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("-5M") }
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("MB10") }
  }

  @Test
  fun `parseSize rejects values that overflow Long`() {
    // `Double.toLong()` saturates to `Long.MAX_VALUE` on overflow rather than wrapping
    // — accepting a saturated cap silently would mean a `--max-size` like `1e20` bytes
    // quietly becomes `--max-size=Long.MAX_VALUE`, which is misleading. Catch it as a
    // USAGE error with a clear "size too large" message instead.
    //
    // Long.MAX_VALUE is ~9.22 × 10^18 bytes (~8 EB), so to actually overflow we need a
    // value above that. `99999999999999999999` is 10^20 (clearly over); `99999999999G`
    // is 99 billion × 10^9 = ~10^20 (also clearly over).
    assertFailsWith<IllegalArgumentException> {
      MaxArtifactSize.parseSize("99999999999999999999")
    }
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("99999999999G") }
  }

  @Test
  fun `parseSize rejects sub-byte values`() {
    // 0.5 bytes would round down to zero, which is meaningless as a cap.
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("0.5") }
    assertFailsWith<IllegalArgumentException> { MaxArtifactSize.parseSize("0") }
  }

  @Test
  fun `enforce returns fits=true without invoking rescale when already under cap`() {
    val artifact = tempFile(byteSize = 100)
    var invocations = 0
    val result = MaxArtifactSize.enforce(artifact, maxBytes = 1024) {
      invocations++
    }
    assertTrue(result.fits)
    assertNull(result.widthPx)
    assertEquals(0, invocations, "rescale must not run when the artifact already fits")
  }

  @Test
  fun `enforce stops at the first width that fits`() {
    val artifact = tempFile(byteSize = 10_000)
    val widthsSeen = mutableListOf<Int>()
    val result = MaxArtifactSize.enforce(artifact, maxBytes = 5_000) { w ->
      widthsSeen += w
      // Simulate downscaling: rewrite the file at half its current size each iteration.
      val newSize = (artifact.length() / 2).coerceAtLeast(1)
      artifact.writeBytes(ByteArray(newSize.toInt()))
    }
    assertTrue(result.fits)
    // 10_000 → 5_000 after one rescale; 5_000 <= 5_000 satisfies the cap immediately.
    assertEquals(listOf(1280), widthsSeen)
    assertEquals(1280, result.widthPx)
  }

  @Test
  fun `enforce walks the ladder until it fits`() {
    val artifact = tempFile(byteSize = 100_000)
    var step = 0
    val expectedSizes = listOf(80_000, 50_000, 30_000, 15_000, 4_000)
    val result = MaxArtifactSize.enforce(artifact, maxBytes = 5_000) {
      artifact.writeBytes(ByteArray(expectedSizes[step]))
      step++
    }
    assertTrue(result.fits)
    // Walked through 1280, 1024, 720, 640, 480 — last one (4000B) finally fit.
    assertEquals(MaxArtifactSize.READABILITY_FLOOR_PX, result.widthPx)
    assertEquals(5, step)
  }

  @Test
  fun `enforce returns fits=false at the floor when even smallest size is over the cap`() {
    val artifact = tempFile(byteSize = 100_000)
    var invocations = 0
    val result = MaxArtifactSize.enforce(artifact, maxBytes = 1_000) {
      invocations++
      // Never gets below the cap — caller will surface an actionable error.
      artifact.writeBytes(ByteArray(50_000))
    }
    assertFalse(result.fits)
    assertEquals(MaxArtifactSize.READABILITY_FLOOR_PX, result.widthPx)
    assertEquals(MaxArtifactSize.SCALE_WIDTHS.size, invocations)
  }

  private fun tempFile(byteSize: Int): File {
    val f = Files.createTempFile("max-artifact-size-test-", ".bin").toFile()
    f.writeBytes(ByteArray(byteSize))
    f.deleteOnExit()
    return f
  }
}
