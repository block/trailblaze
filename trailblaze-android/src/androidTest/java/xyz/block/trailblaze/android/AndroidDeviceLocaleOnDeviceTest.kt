package xyz.block.trailblaze.android

import android.content.res.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.AndroidDeviceLocale

/**
 * Changes the device locale from inside instrumentation, the way a device-farm run applies a
 * trail's `locale`, and restores the original afterwards. Covers both the Android 16+ shell
 * command and the in-process configuration update older images need.
 */
class AndroidDeviceLocaleOnDeviceTest {

  @Test
  fun appliesAndVerifiesADifferentLocaleThenRestoresTheOriginal() {
    val original = systemLocale()
    val target = if (original.startsWith("es")) "fr" else "es"
    try {
      assertTrue("$target should be applied over $original", AndroidDeviceLocale.apply(target))
      assertFalse("a second apply must read $target back", AndroidDeviceLocale.apply(target))
      assertEquals(target, awaitSystemLanguage(target))
    } finally {
      AndroidDeviceLocale.apply(original)
    }
  }

  @Test
  fun aTrailWithNoLocaleRunsInTheDefaultAndOnlyAChangeCounts() {
    val original = systemLocale()
    try {
      // Whatever language the last trail left, a trail that declares none ends up in en-US.
      AndroidDeviceLocale.applyForTrail("es")
      assertTrue("the default must replace es", AndroidDeviceLocale.applyForTrail(null))
      assertEquals("en", awaitSystemLanguage("en"))
      assertFalse("the default already set is no change", AndroidDeviceLocale.applyForTrail(null))
      assertFalse("a declared locale already set is no change", AndroidDeviceLocale.applyForTrail("en-US"))
    } finally {
      AndroidDeviceLocale.apply(original)
    }
  }

  private fun systemLocale(): String = Resources.getSystem().configuration.locales[0].toLanguageTag()

  /** The configuration change reaches this process asynchronously, after the readback proved it. */
  private fun awaitSystemLanguage(expected: String): String {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
      val language = Resources.getSystem().configuration.locales[0].language
      if (language == expected) return language
      Thread.sleep(100)
    }
    return Resources.getSystem().configuration.locales[0].language
  }
}
