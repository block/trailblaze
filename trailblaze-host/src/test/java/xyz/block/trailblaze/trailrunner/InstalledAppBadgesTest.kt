package xyz.block.trailblaze.trailrunner

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * Coverage for [InstalledAppBadges]' pure parsing/selection steps — the `aapt2 dump badging`
 * interpretation behind the Create Target picker's Android labels and icons. The pull/extract
 * plumbing is thin delegation to [xyz.block.trailblaze.util.AndroidHostAdbUtils] / `ZipFile`
 * and isn't re-tested here.
 */
class InstalledAppBadgesTest {

  @Test
  fun `parseAaptBadging reads the unqualified label and every density icon`() {
    val parsed = InstalledAppBadges.parseAaptBadging(
      listOf(
        "package: name='com.example.app' versionCode='42' versionName='1.2'",
        "application-label:'My App'",
        "application-label-en:'My App (en)'",
        "application-icon-160:'res/mipmap-mdpi/ic_launcher.png'",
        "application-icon-640:'res/mipmap-xxxhdpi/ic_launcher.png'",
        "launchable-activity: name='com.example.app.Main'  label='My App' icon=''",
      ),
    )
    assertEquals("My App", parsed.label)
    assertEquals(
      mapOf(160 to "res/mipmap-mdpi/ic_launcher.png", 640 to "res/mipmap-xxxhdpi/ic_launcher.png"),
      parsed.iconByDensity,
    )
  }

  @Test
  fun `parseAaptBadging yields null label when absent`() {
    assertNull(InstalledAppBadges.parseAaptBadging(listOf("package: name='x'")).label)
  }

  @Test
  fun `pickBestIconEntry prefers the densest raster and skips adaptive xml`() {
    assertEquals(
      "res/mipmap-xxhdpi/ic_launcher.png",
      InstalledAppBadges.pickBestIconEntry(
        mapOf(
          160 to "res/mipmap-mdpi/ic_launcher.png",
          480 to "res/mipmap-xxhdpi/ic_launcher.png",
          65534 to "res/mipmap-anydpi-v26/ic_launcher.xml",
        ),
      ),
    )
  }

  @Test
  fun `pickBestIconEntry is null when only adaptive xml icons exist`() {
    assertNull(InstalledAppBadges.pickBestIconEntry(mapOf(65534 to "res/mipmap-anydpi-v26/ic_launcher.xml")))
  }

  @Test
  fun `pickRasterMipmapFallback prefers the adaptive stem at the highest density over ic_launcher and round variants`() {
    val entries = listOf(
      "res/mipmap-hdpi-v4/icon.webp",
      "res/mipmap-xxxhdpi-v4/icon.webp",
      "res/mipmap-xxxhdpi-v4/icon_round.webp",
      "res/mipmap-xxxhdpi-v4/ic_launcher.webp",
      "res/mipmap-anydpi-v26/icon.xml",
      "res/drawable-hdpi-v4/unrelated.png",
    )
    assertEquals(
      "res/mipmap-xxxhdpi-v4/icon.webp",
      InstalledAppBadges.pickRasterMipmapFallback(entries, adaptiveStem = "icon"),
    )
  }

  @Test
  fun `pickRasterMipmapFallback falls back to ic_launcher when the adaptive stem has no raster`() {
    val entries = listOf(
      "res/mipmap-mdpi-v4/ic_launcher.png",
      "res/mipmap-xhdpi-v4/ic_launcher.png",
    )
    assertEquals(
      "res/mipmap-xhdpi-v4/ic_launcher.png",
      InstalledAppBadges.pickRasterMipmapFallback(entries, adaptiveStem = "icon"),
    )
  }

  @Test
  fun `pickRasterMipmapFallback is null when no launcher-shaped raster exists`() {
    assertNull(InstalledAppBadges.pickRasterMipmapFallback(listOf("res/drawable/foo.png"), adaptiveStem = null))
  }
}
