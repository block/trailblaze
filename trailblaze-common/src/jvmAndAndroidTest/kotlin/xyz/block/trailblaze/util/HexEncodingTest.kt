package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test

class HexEncodingTest {

  /**
   * Known-answer test pinning the exact format hash consumers persist (workspace drift hashes,
   * compile-cache keys): lowercase, zero-padded, and — the case that differs across naive
   * implementations — negative bytes as their unsigned two's-complement low 8 bits. The
   * common implementation must match what JVM `"%02x".format(byte)` historically produced.
   */
  @Test
  fun `encodes boundary bytes as unsigned lowercase zero-padded hex`() {
    val bytes = byteArrayOf(0x00, 0x01, 0x0f, 0x10, 0x7f, -0x80, -0x01)
    assertThat(bytes.toLowerHex()).isEqualTo("00010f107f80ff")
  }

  @Test
  fun `matches the JVM format-string implementation it replaced`() {
    val allBytes = ByteArray(256) { it.toByte() }
    assertThat(allBytes.toLowerHex())
      .isEqualTo(allBytes.joinToString("") { "%02x".format(it) })
  }
}
