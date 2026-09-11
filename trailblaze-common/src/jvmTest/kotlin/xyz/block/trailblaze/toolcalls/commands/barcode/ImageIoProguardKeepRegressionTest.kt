package xyz.block.trailblaze.toolcalls.commands.barcode

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the released JAR's ImageIO service providers against ProGuard shrinking.
 *
 * The WebP reader is loaded through ImageIO's service registry. A release build without these
 * keeps retains `META-INF/services/javax.imageio.spi.*` while deleting the named TwelveMonkeys
 * classes. ImageIO then throws `ServiceConfigurationError` on its first read, before it can decode
 * any screenshot format. Unit tests run with intact dependencies, so the release-only contract is
 * the ProGuard ruleset itself, following the same pattern as the existing Coil and QuickJS guards.
 */
class ImageIoProguardKeepRegressionTest {

  @Test
  fun `ProGuard rules keep ImageIO service providers and their implementations`() {
    val rules = activeRules(locateProguardRules().readText())

    assertTrue(
      TWELVE_MONKEYS_KEEP.containsMatchIn(rules),
      "Missing `-keep class com.twelvemonkeys.imageio.** { *; }`. Without it, the released " +
        "JAR can retain ImageIO service descriptors that name provider classes ProGuard deleted, " +
        "and the first screenshot decode throws ServiceConfigurationError.",
    )
    assertTrue(
      IMAGE_IO_PROVIDER_KEEP.containsMatchIn(rules),
      "Missing `-keep class * extends javax.imageio.spi.IIOServiceProvider { *; }`. A codec " +
        "outside the TwelveMonkeys package must not be allowed to poison ImageIO's service registry.",
    )
  }

  @Test
  fun `keep detector ignores commented and incomplete rules`() {
    val packageKeep = "-keep class com.twelvemonkeys.imageio.** { *; }"
    val providerKeep = "-keep class * extends javax.imageio.spi.IIOServiceProvider { *; }"

    assertTrue(TWELVE_MONKEYS_KEEP.containsMatchIn(activeRules(packageKeep)))
    assertTrue(IMAGE_IO_PROVIDER_KEEP.containsMatchIn(activeRules(providerKeep)))
    assertFalse(TWELVE_MONKEYS_KEEP.containsMatchIn(activeRules("# $packageKeep")))
    assertFalse(IMAGE_IO_PROVIDER_KEEP.containsMatchIn(activeRules("  # $providerKeep")))
    assertFalse(TWELVE_MONKEYS_KEEP.containsMatchIn(activeRules("-keep class com.twelvemonkeys.imageio.**")))
  }

  private fun locateProguardRules(): File {
    val repoRelativePath = "trailblaze-desktop/proguard-rules.pro"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }

  private companion object {
    val TWELVE_MONKEYS_KEEP =
      Regex("""-keep\s+class\s+com\.twelvemonkeys\.imageio\.\*\*\s*\{\s*\*;\s*}""")
    val IMAGE_IO_PROVIDER_KEEP =
      Regex("""-keep\s+class\s+\*\s+extends\s+javax\.imageio\.spi\.IIOServiceProvider\s*\{\s*\*;\s*}""")

    fun activeRules(rulesText: String): String =
      rulesText.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
  }
}
