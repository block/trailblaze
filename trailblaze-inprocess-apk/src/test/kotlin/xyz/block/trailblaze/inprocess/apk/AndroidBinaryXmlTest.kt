package xyz.block.trailblaze.inprocess.apk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The binary-XML reader, against a real merged manifest.
 *
 * The fixture is the sample app's own `AndroidManifest.xml`, lifted verbatim out of its built APK,
 * because the failure mode this reader has is not an exception — a wrong offset yields attributes
 * that parse into plausible-looking garbage, and every assertion below is on a named attribute for
 * that reason. The expected values were cross-checked against `aapt2 dump xmltree` on the same APK.
 */
class AndroidBinaryXmlTest {

  private val manifest: BinaryXmlElement by lazy {
    val bytes = checkNotNull(javaClass.getResourceAsStream(FIXTURE)) {
      "missing test fixture $FIXTURE"
    }.use { it.readBytes() }
    AndroidBinaryXml.parse(bytes)
  }

  @Test
  fun `reads the package from the root element`() {
    assertEquals("manifest", manifest.name)
    assertEquals("xyz.block.trailblaze.examples.sampleapp", manifest.attr(null, "package"))
  }

  @Test
  fun `reads an android-namespaced attribute on a nested element`() {
    val application = manifest.childrenNamed("application").single()
    assertEquals("true", application.androidAttr("debuggable"))
    // Same element, different attribute, so a reader that happened to land on the right value once
    // by luck does not pass.
    assertEquals("true", application.androidAttr("allowBackup"))
  }

  @Test
  fun `an absent attribute reads as null rather than as a neighbouring value`() {
    val application = manifest.childrenNamed("application").single()
    assertNull(application.androidAttr("thisAttributeDoesNotExist"))
  }

  @Test
  fun `distinguishes namespaced from un-namespaced attributes of the same name`() {
    // `package` is un-namespaced; `android:versionName` is namespaced. Asking for either in the
    // wrong namespace must miss, which is what proves the namespace field is actually read.
    assertNotNull(manifest.attr(null, "package"))
    assertNull(manifest.attr(ANDROID_NAMESPACE, "package"))
    assertNotNull(manifest.androidAttr("versionName"))
    assertNull(manifest.attr(null, "versionName"))
  }

  @Test
  fun `finds descendants several levels down`() {
    val categories = manifest.descendants("category")
    assertTrue(
      categories.any { it.androidAttr("name") == "android.intent.category.LAUNCHER" },
      "expected a LAUNCHER category under activity/intent-filter, got " +
        categories.map { it.androidAttr("name") },
    )
  }

  @Test
  fun `refuses bytes that are not binary XML`() {
    val error = assertFailsWith<ApkReadException> {
      AndroidBinaryXml.parse("<manifest package=\"com.example\"/>".toByteArray())
    }
    // A plain-text manifest is the mistake a human actually makes, so the message says so rather
    // than reporting a byte offset.
    assertTrue(error.message!!.contains("source tree"), error.message!!)
  }

  @Test
  fun `refuses truncated bytes`() {
    assertFailsWith<ApkReadException> { AndroidBinaryXml.parse(byteArrayOf(0x03, 0x00)) }
  }

  private companion object {
    const val FIXTURE = "/fixtures/sample-app-AndroidManifest.bin"
  }
}
