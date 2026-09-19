import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.api.GradleException

/**
 * The pure pieces behind the split-APK host: its asset convention, the manifest stamping against
 * the REAL bundled template, and the `aapt2 dump badging` parse that decides whether a split can
 * be built for a target at all.
 */
class TrailblazeInProcessIdleSplitApkTest {

  private fun resource(path: String): String =
    javaClass.classLoader.getResourceAsStream(path)!!.use { it.readBytes().toString(Charsets.UTF_8) }

  @Test
  fun `split asset path follows the same flavor convention with a split marker`() {
    assertEquals(
      "inprocess-idle-apks/trailblaze-inprocess-idle-split-sampleapp.apk",
      InProcessIdleConventions.splitApkAssetPath("xyz.block.trailblaze.examples.sampleapp"),
    )
  }

  @Test
  fun `split manifest is stamped with the app id as package and authority, versionCode left to link`() {
    val stamped =
      InProcessIdleConventions.stampSplitManifest(
        resource(InProcessIdleConventions.SPLIT_MANIFEST_RESOURCE),
        "com.example.app",
      )
    assertTrue(stamped.contains("package=\"com.example.app\""), stamped)
    assertTrue(
      stamped.contains("android:authorities=\"com.example.app.trailblaze.inprocessidle\""),
      stamped,
    )
    assertTrue(stamped.contains("split=\"${InProcessIdleConventions.SPLIT_NAME}\""), stamped)
    // Injected by `aapt2 link --version-code` from the real app APK: a number in the template would
    // only ever be right for one build.
    assertFalse(stamped.contains("android:versionCode="), stamped)
    // The provider the split declares must be the class the plugin compiles into it.
    assertTrue(
      stamped.contains(
        "android:name=\"${InProcessIdleConventions.IN_PROCESS_IDLE_BASE_PACKAGE}.InProcessIdleProvider\""
      ),
      stamped,
    )
  }

  @Test
  fun `split manifest stamping refuses a drifted template`() {
    assertFailsWith<GradleException> {
      InProcessIdleConventions.stampSplitManifest("<manifest package=\"x\"/>", "com.example.app")
    }
  }

  @Test
  fun `both hosts and the shared server ship in the plugin classpath`() {
    assertTrue(
      resource(InProcessIdleConventions.SERVER_SOURCE_RESOURCE).contains("class InProcessIdleServer")
    )
    assertTrue(
      resource(InProcessIdleConventions.PROVIDER_SOURCE_RESOURCE)
        .contains("class InProcessIdleProvider extends ContentProvider")
    )
    assertTrue(
      resource(InProcessIdleConventions.SOURCE_RESOURCE).contains("InProcessIdleServer.start(")
    )
  }

  @Test
  fun `badging parse reads package and versionCode from the package line`() {
    val badging =
      """
      package: name='com.example.app' versionCode='71500983' versionName='7.15' platformBuildVersionName='15' compileSdkVersion='35'
      sdkVersion:'26'
      application-label:'Example'
      """
        .trimIndent()
    assertEquals(
      InProcessIdleConventions.ApkIdentity("com.example.app", 71500983L),
      InProcessIdleConventions.parseApkBadging(badging),
    )
  }

  @Test
  fun `versionCodeMajor is read from the manifest tree, and is zero when the manifest sets none`() {
    // Badging never prints the major, so the split builder reads it from `aapt2 dump xmltree`.
    // The package manager matches a split on the LONG version code, so a base that sets a major
    // needs the split stamped with the same one.
    val withMajor =
      """
      N: android=http://schemas.android.com/apk/res/android (line=1)
        E: manifest (line=1)
          A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=7
          A: http://schemas.android.com/apk/res/android:versionCodeMajor(0x01010576)=3
          A: package="x.y.z" (Raw: "x.y.z")
      """
        .trimIndent()
    assertEquals(3L, InProcessIdleConventions.parseManifestVersionCodeMajor(withMajor))
    assertEquals(0L, InProcessIdleConventions.parseManifestVersionCodeMajor(withMajor.lines().filterNot { "Major" in it }.joinToString("\n")))
  }

  @Test
  fun `badging parse returns null for output with no package line`() {
    assertNull(InProcessIdleConventions.parseApkBadging("ERROR: dump failed because no AndroidManifest.xml found"))
    assertNull(InProcessIdleConventions.parseApkBadging(""))
  }
}
