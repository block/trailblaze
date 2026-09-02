package xyz.block.trailblaze.inprocess.apk

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest rules behind the two launcher disqualifiers, stated over manifests this test writes.
 *
 * [AndroidBinaryXmlTest] covers reading a real binary manifest. What it cannot cover is a shape no
 * fixture in this repository has: a disabled launcher, an INFO-only front door, a launcher declared
 * into another process, a disabled `<application>`. Both facts those shapes change are enforced by
 * the farm gate — `NO_LAUNCHER_ACTIVITY` and `LAUNCHER_IN_OTHER_PROCESS` — so getting either wrong
 * fails a build over an app that is fine.
 */
class ManifestFactsTest {

  @Test
  fun `an enabled launcher activity is found`() {
    val facts = ManifestFacts.of(manifest(launcher(name = ".MainActivity")), "test")
    assertEquals("com.example.MainActivity", facts.launcherActivity)
  }

  @Test
  fun `a launcher activity disabled in the manifest is not a launcher`() {
    // `getLaunchIntentForPackage` — what actually starts the app under test — returns nothing for a
    // disabled launcher, so reporting one would clear an app the harness cannot start.
    val facts = ManifestFacts.of(manifest(launcher(name = ".MainActivity", enabled = "false")), "test")
    assertNull(facts.launcherActivity)
  }

  @Test
  fun `android colon enabled true is still a launcher`() {
    // Negative control: only an explicit "false" disables. Treating any `enabled` attribute as
    // disabling would drop the launcher of every app that states the default out loud.
    val facts = ManifestFacts.of(manifest(launcher(name = ".MainActivity", enabled = "true")), "test")
    assertEquals("com.example.MainActivity", facts.launcherActivity)
  }

  @Test
  fun `an app whose only front door is MAIN and INFO has a launcher`() {
    // `getLaunchIntentForPackage` resolves MAIN/INFO too, so an INFO-only app starts fine. Missing
    // this reports the ENFORCED NO_LAUNCHER_ACTIVITY and fails a build over a front door it has.
    val facts = ManifestFacts.of(manifest(launcher(name = ".InfoActivity", category = INFO)), "test")
    assertEquals("com.example.InfoActivity", facts.launcherActivity)
  }

  @Test
  fun `an app declaring both categories reports the INFO activity`() {
    // PackageManager checks MAIN/INFO across the whole package BEFORE MAIN/LAUNCHER, so the INFO
    // one is what actually starts. Reporting the other would fingerprint an Activity — and read a
    // process off it — that the harness never launches.
    val facts = ManifestFacts.of(
      manifest(
        launcher(name = ".HomeActivity"),
        launcher(name = ".InfoActivity", category = INFO),
      ),
      "test",
    )
    assertEquals("com.example.InfoActivity", facts.launcherActivity)
  }

  @Test
  fun `a MAIN activity in no launching category is not a launcher`() {
    // Negative control: MAIN alone is how a manifest declares an entry point the home screen does
    // not show, and treating it as one would clear an app the harness cannot start.
    val facts = ManifestFacts.of(manifest(launcher(name = ".MainActivity", category = null)), "test")
    assertNull(facts.launcherActivity)
  }

  @Test
  fun `a launcher in the default process reports no process`() {
    assertNull(ManifestFacts.of(manifest(launcher(name = ".MainActivity")), "test").launcherProcess)
  }

  @Test
  fun `a launcher declared into another process reports that process`() {
    val facts = ManifestFacts.of(manifest(launcher(name = ".MainActivity", process = ":ui")), "test")
    assertEquals(":ui", facts.launcherProcess)
    // Still the launcher — the disqualifier is about where it starts, not whether it exists, and
    // reporting NO_LAUNCHER_ACTIVITY here would send the reader looking for a missing intent filter.
    assertEquals("com.example.MainActivity", facts.launcherActivity)
  }

  @Test
  fun `an application-wide process rename does not put the launcher elsewhere`() {
    // `android:process` on <application> renames the default process for everything in it, the
    // instrumentation included, so the launcher is still where the harness is watching.
    val facts = ManifestFacts.of(
      manifest(launcher(name = ".MainActivity", process = "com.example.main"), applicationProcess = "com.example.main"),
      "test",
    )
    assertNull(facts.launcherProcess)
  }

  @Test
  fun `an alias takes the process of the activity it targets`() {
    // An `activity-alias` declares no process of its own; what starts is `targetActivity`.
    val root = manifest(
      alias(name = ".Alias", targetActivity = ".RealActivity"),
      activity(name = ".RealActivity", process = ":ui"),
    )
    val facts = ManifestFacts.of(root, "test")
    assertEquals("com.example.RealActivity", facts.launcherActivity)
    assertEquals(":ui", facts.launcherProcess)
  }

  @Test
  fun `an alias pointing at an activity the manifest does not declare is not a launcher`() {
    // Nothing starts, so reporting one clears an app the harness cannot launch — and puts a class
    // name in the fingerprint that does not exist in the APK.
    val facts = ManifestFacts.of(manifest(alias(name = ".Alias", targetActivity = ".Missing")), "test")
    assertNull(facts.launcherActivity)
  }

  @Test
  fun `an alias pointing at a disabled activity is not a launcher`() {
    val root = manifest(
      alias(name = ".Alias", targetActivity = ".RealActivity"),
      activity(name = ".RealActivity", enabled = "false"),
    )
    assertNull(ManifestFacts.of(root, "test").launcherActivity)
  }

  @Test
  fun `a disabled application has no launcher and no providers`() {
    val facts = ManifestFacts.of(
      manifest(launcher(name = ".MainActivity"), provider(".WorkProvider"), applicationEnabled = "false"),
      "test",
    )
    assertNull(facts.launcherActivity)
    assertEquals(emptyList(), facts.providers)
  }

  @Test
  fun `an enabled provider is reported and a disabled one is not`() {
    val facts = ManifestFacts.of(
      manifest(provider(".RunsProvider"), provider(".DisabledProvider", enabled = "false")),
      "test",
    )
    assertEquals(listOf("com.example.RunsProvider"), facts.providers.map { it.className })
  }

  @Test
  fun `androidx startup's provider is flagged as the one initializer with a seam`() {
    val facts = ManifestFacts.of(manifest(provider("androidx.startup.InitializationProvider")), "test")
    assertTrue(facts.providers.single().androidxStartup)
  }

  @Test
  fun `a manifest with no package is refused`() {
    val error = runCatching {
      ManifestFacts.of(BinaryXmlElement("manifest", emptyList(), mutableListOf()), "some.apk")
    }.exceptionOrNull()
    assertTrue(error is ApkReadException, "expected ApkReadException, got $error")
    assertTrue(error.message!!.contains("some.apk"), error.message!!)
  }

  private fun manifest(
    vararg applicationChildren: BinaryXmlElement,
    applicationEnabled: String? = null,
    applicationProcess: String? = null,
  ) = BinaryXmlElement(
    name = "manifest",
    attributes = listOf(BinaryXmlAttribute(namespace = null, name = "package", value = "com.example")),
    children = mutableListOf(
      BinaryXmlElement(
        name = "application",
        attributes = androidAttributes("enabled" to applicationEnabled, "process" to applicationProcess),
        children = applicationChildren.toMutableList(),
      ),
    ),
  )

  private fun launcher(
    name: String,
    enabled: String? = null,
    process: String? = null,
    category: String? = LAUNCHER,
  ) = BinaryXmlElement(
    name = "activity",
    attributes = androidAttributes("name" to name, "enabled" to enabled, "process" to process),
    children = mutableListOf(launcherIntentFilter(category)),
  )

  /** An `activity-alias` whose LAUNCHER filter points at [targetActivity]. */
  private fun alias(name: String, targetActivity: String) = BinaryXmlElement(
    name = "activity-alias",
    attributes = androidAttributes("name" to name, "targetActivity" to targetActivity),
    children = mutableListOf(launcherIntentFilter(LAUNCHER)),
  )

  /** A plain activity with no intent filter — what an alias targets. */
  private fun activity(name: String, process: String? = null, enabled: String? = null) = BinaryXmlElement(
    name = "activity",
    attributes = androidAttributes("name" to name, "process" to process, "enabled" to enabled),
    children = mutableListOf(),
  )

  /** MAIN, plus [category] when there is one — a null category is MAIN and nothing else. */
  private fun launcherIntentFilter(category: String?) = BinaryXmlElement(
    name = "intent-filter",
    attributes = emptyList(),
    children = mutableListOf(
      BinaryXmlElement("action", androidAttributes("name" to "android.intent.action.MAIN"), mutableListOf()),
    ).apply {
      category?.let { add(BinaryXmlElement("category", androidAttributes("name" to it), mutableListOf())) }
    },
  )

  private fun provider(name: String, enabled: String? = null) = BinaryXmlElement(
    name = "provider",
    attributes = androidAttributes("name" to name, "enabled" to enabled),
    children = mutableListOf(),
  )

  private fun androidAttributes(vararg pairs: Pair<String, String?>) = pairs.mapNotNull { (name, value) ->
    value?.let { BinaryXmlAttribute(namespace = ANDROID_NAMESPACE, name = name, value = it) }
  }

  private companion object {
    const val LAUNCHER = "android.intent.category.LAUNCHER"
    const val INFO = "android.intent.category.INFO"
  }
}
