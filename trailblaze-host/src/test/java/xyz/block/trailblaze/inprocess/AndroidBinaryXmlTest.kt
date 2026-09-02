package xyz.block.trailblaze.inprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * The AXML stamp, against REAL compiled manifests — `AndroidManifest.xml` lifted verbatim out of a
 * built `:inprocess-shell` APK and a built `:examples:android-sample-app` APK.
 *
 * Synthesizing a manifest would test the writer against the reader's own assumptions. These bytes
 * came out of AGP, so a mistake about the format is a failing test rather than a device-only
 * surprise.
 */
class AndroidBinaryXmlTest {

  private val shellManifest: ByteArray = fixture("shell-AndroidManifest.xml")
  private val appManifest: ByteArray = fixture("app-debug-AndroidManifest.xml")

  private val placeholder = "xyz.block.trailblaze.inprocess.unstamped.placeholder.package"

  @Test
  fun `reads what the shell manifest says before anything is stamped`() {
    assertThat(AndroidBinaryXml.readInstrumentationTargetPackage(shellManifest)).isEqualTo(placeholder)
    assertThat(AndroidBinaryXml.readPackageName(shellManifest))
      .isEqualTo("xyz.block.trailblaze.inprocess.shell")
  }

  @Test
  fun `stamps a package LONGER than the placeholder`() {
    val longer = placeholder + ".with.quite.a.lot.more.on.the.end"
    val stamped = AndroidBinaryXml.stampInstrumentationTargetPackage(shellManifest, longer)
    assertThat(AndroidBinaryXml.readInstrumentationTargetPackage(stamped)).isEqualTo(longer)
  }

  @Test
  fun `stamps a package SHORTER than the placeholder`() {
    val stamped = AndroidBinaryXml.stampInstrumentationTargetPackage(shellManifest, "a.b")
    assertThat(AndroidBinaryXml.readInstrumentationTargetPackage(stamped)).isEqualTo("a.b")
  }

  /**
   * The pool deduplicates by content, so an implementation that edited the referenced entry in place
   * would rewrite every other reference to it. Reading a DIFFERENT attribute after stamping is what
   * catches that: `<manifest package>` lives in the same pool and must come back untouched.
   */
  @Test
  fun `stamping leaves the rest of the string pool intact`() {
    val stamped = AndroidBinaryXml.stampInstrumentationTargetPackage(shellManifest, "com.example.other")
    assertThat(AndroidBinaryXml.readPackageName(stamped))
      .isEqualTo("xyz.block.trailblaze.inprocess.shell")
    assertThat(AndroidBinaryXml.readApplicationDebuggable(stamped))
      .isEqualTo(AndroidBinaryXml.readApplicationDebuggable(shellManifest))
  }

  @Test
  fun `stamping is repeatable, so a mis-stamped APK can be corrected`() {
    val once = AndroidBinaryXml.stampInstrumentationTargetPackage(shellManifest, "com.example.first")
    val twice = AndroidBinaryXml.stampInstrumentationTargetPackage(once, "com.example.second")
    assertThat(AndroidBinaryXml.readInstrumentationTargetPackage(twice)).isEqualTo("com.example.second")
    assertThat(AndroidBinaryXml.readPackageName(twice)).isEqualTo("xyz.block.trailblaze.inprocess.shell")
  }

  @Test
  fun `reads an app manifest's own package and debuggable flag`() {
    assertThat(AndroidBinaryXml.readPackageName(appManifest))
      .isEqualTo("xyz.block.trailblaze.examples.sampleapp")
    assertThat(AndroidBinaryXml.readApplicationDebuggable(appManifest)).isTrue()
    assertThat(AndroidBinaryXml.readInstrumentationTargetPackage(appManifest)).isNull()
  }

  @Test
  fun `refuses to stamp an APK that is not a test APK`() {
    val failure = assertFailsWith<IllegalStateException> {
      AndroidBinaryXml.stampInstrumentationTargetPackage(appManifest, "com.example.other")
    }
    assertThat(failure.message!!).contains("does not have exactly one <instrumentation")
  }

  @Test
  fun `refuses bytes that are not a compiled manifest`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      AndroidBinaryXml.readPackageName("<manifest package=\"com.example\"/>".toByteArray())
    }
    assertThat(failure.message!!).contains("not a binary AndroidManifest.xml")
  }

  /**
   * AGP's default instrumentation label is "Tests for <targetPackage>", which the stamp does not
   * rewrite — so a shell carrying it would tell a `pm list instrumentation` reader the placeholder
   * package while its target says the real one. `:inprocess-shell` overrides the label instead;
   * this is the check that the override is still there. Both pool encodings, since which one AGP
   * emits is not ours to decide.
   */
  @Test
  fun `the shell's instrumentation label does not name the placeholder package`() {
    val asUtf16 = String(shellManifest, Charsets.UTF_16LE)
    val asUtf8 = shellManifest.decodeToString()
    assertThat(asUtf16.contains("Tests for") || asUtf8.contains("Tests for")).isFalse()
    assertThat(asUtf16.contains("Trailblaze in-process shell") ||
      asUtf8.contains("Trailblaze in-process shell")).isTrue()
  }

  private fun fixture(name: String): ByteArray =
    checkNotNull(javaClass.getResourceAsStream("/inprocess/$name")) { "missing fixture $name" }
      .use { it.readBytes() }
}
