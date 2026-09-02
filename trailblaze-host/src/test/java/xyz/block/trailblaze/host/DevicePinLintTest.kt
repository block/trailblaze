package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.cli.CheckCommand

/**
 * Pins [DevicePinLint]'s decision contract on how a trail spells its `config.devices:` block.
 *
 * The cases are ported from `scripts/test_migrate_device_form.py`, the repo-local Python ratchet
 * this gate generalizes into the framework. The properties worth pinning are the ones that would
 * let the gate corrupt a trail quietly or fail a legitimate one:
 *
 *  - Both mis-indent depths are caught, and they fail DIFFERENTLY. One level too shallow makes
 *    `driver:` a sibling entry inside `devices:` — an entry literally named `driver`, which reads
 *    as a bare-string pin. Two levels makes it a session-level `config.driver:`, which the decoder
 *    accepts (it is a real v1 field) while the device silently goes unpinned.
 *  - A `devices:` map that is not a trail's `config.devices:` is never touched.
 *  - A null entry (`web:`) is not legacy — it declares a classifier and pins nothing, a shape the
 *    object form has no shorter spelling for.
 *  - `config.driver:` on its own is legal; only its coexistence with `devices:` is the smell.
 */
class DevicePinLintTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /**
   * A minimal trail whose `config.devices:` block is [devices], re-indented to sit under it.
   * Built by concatenation rather than by interpolating into a `trimIndent()` template, because
   * interpolation happens first and a multi-line [devices] would then set the common indent.
   */
  private fun trail(devices: String) = buildString {
    appendLine("config:")
    appendLine("  id: myapp/login")
    appendLine("  target: myapp")
    appendLine("  devices:")
    appendLine(devices.trimIndent().prependIndent("    "))
    appendLine("trail:")
    appendLine("  - step: Open the app")
    appendLine("    recordable: false")
  }

  private fun lint(yamlText: String) = DevicePinLint.lint("fixture/trail.yaml", yamlText)

  // ---- Rule 3: the deprecated bare-string form (advisory) ----

  @Test
  fun `a bare-string entry is reported with its classifier and driver name`() {
    val finding = lint(trail("    android: ANDROID_ONDEVICE_ACCESSIBILITY"))
    assertNotNull(finding)
    val entry = finding.legacyDriverForms.single()
    assertEquals("android", entry.classifier)
    assertEquals("ANDROID_ONDEVICE_ACCESSIBILITY", entry.driverName)
    assertTrue(!finding.isFatal, "the deprecated form warns, it does not fail the build")
  }

  @Test
  fun `every bare-string entry is reported, in file order`() {
    val finding = lint(trail("    android: ANDROID_ONDEVICE_ACCESSIBILITY\n    ios: IOS_HOST"))
    assertNotNull(finding)
    assertEquals(listOf("android", "ios"), finding.legacyDriverForms.map { it.classifier })
    assertEquals(listOf("ANDROID_ONDEVICE_ACCESSIBILITY", "IOS_HOST"), finding.legacyDriverForms.map { it.driverName })
  }

  @Test
  fun `the object form is clean`() {
    assertNull(lint(trail("    android:\n      driver: ANDROID_ONDEVICE_ACCESSIBILITY")))
  }

  @Test
  fun `a null entry is not a legacy pin`() {
    // `web:` declares the classifier and pins nothing. There is no object form to rewrite it to,
    // and treating it as legacy would invent a driver.
    assertNull(lint(trail("    web:\n    ios: {}")))
  }

  @Test
  fun `a multi-device configuration entry is clean`() {
    // A configuration's value is a map, not a scalar — and its inner named devices are addressed
    // by name, never by a driver scalar.
    assertNull(
      lint(trail("    pos-pair:\n      devices:\n        seller:\n          classifier: lab-a")),
    )
  }

  @Test
  fun `a devices map outside config is untouched`() {
    // Other schemas have their own top-level `devices:` blocks — TrailblazeCiConfig's
    // `deviceDriverTypes:` is a bare scalar map by design and must never be flagged.
    assertNull(lint("devices:\n  android: ANDROID_ONDEVICE_ACCESSIBILITY\n"))
    assertNull(lint("deviceDriverTypes:\n  android: ANDROID_ONDEVICE_ACCESSIBILITY\n"))
  }

  @Test
  fun `a quoted driver reports the unquoted name`() {
    val finding = lint(trail("""    android: "ANDROID_ONDEVICE_ACCESSIBILITY""""))
    assertNotNull(finding)
    assertEquals("ANDROID_ONDEVICE_ACCESSIBILITY", finding.legacyDriverForms.single().driverName)
  }

  @Test
  fun `a four-space file is read the same as a two-space one`() {
    val finding = lint(
      """
      config:
          id: myapp/login
          devices:
              android: ANDROID_ONDEVICE_ACCESSIBILITY
      """.trimIndent(),
    )
    assertNotNull(finding)
    assertEquals("android", finding.legacyDriverForms.single().classifier)
  }

  // ---- Rule 2: a `driver`-keyed entry of `devices:` — a pin ONE level too shallow (fatal) ----

  @Test
  fun `one level too shallow declares a device classifier named driver and is fatal`() {
    // `driver:` lands as a sibling of `android:` INSIDE `devices:`. This is the shape NOTHING else
    // catches: it decodes cleanly at every strictness (a valid classifier key holding a valid
    // driver name), so `android` silently goes unpinned and the pin names a device that does not
    // exist.
    val finding = lint(trail("    android:\n    driver: ANDROID_ONDEVICE_ACCESSIBILITY"))
    assertNotNull(finding)
    assertTrue(finding.isFatal)
    assertEquals(listOf("android"), finding.driverKeyedDeviceEntry?.siblingClassifiers)
    assertNull(finding.sessionDriverBesideDevices)
  }

  @Test
  fun `a null-valued driver entry is still a mis-indent`() {
    // No value to migrate, but an entry named `driver` is never what the author meant — so the
    // rule keys on the KEY, at any value type. A value-shaped rule would miss this one entirely.
    val finding = lint(trail("    android:\n    driver:"))
    assertNotNull(finding)
    assertTrue(finding.isFatal)
    assertEquals(listOf("android"), finding.driverKeyedDeviceEntry?.siblingClassifiers)
  }

  @Test
  fun `a driver field correctly nested under a classifier is not a driver-keyed entry`() {
    // Here `driver` is a field of `android`, not an entry of `devices`.
    assertNull(lint(trail("    android:\n      driver: ANDROID_ONDEVICE_ACCESSIBILITY")))
  }

  @Test
  fun `a multi-device configuration named driver is legal, not a mis-indent`() {
    // Configuration names are unrestricted, and an entry carrying its own inner `devices:` cast is
    // a configuration — a shape no mis-indent can produce, since a mis-indented pin's value is a
    // scalar or nothing. Failing this would block a valid trail with only the kill-switch as
    // escape.
    assertNull(lint(trail("    driver:\n      devices:\n        seller:\n          classifier: lab-a")))
  }

  @Test
  fun `a driver entry with an empty inner devices key is still a mis-indent`() {
    // The exemption requires a MAPPED inner cast. A bare `devices:` with nothing under it declares
    // no cast, so this is still the mis-indent — exempting on the key alone would open a spelling
    // that walks straight through the gate.
    val finding = lint(trail("    android:\n    driver:\n      devices:"))
    assertNotNull(finding)
    assertTrue(finding.isFatal)
  }

  @Test
  fun `a driver-keyed entry is not double-reported as a deprecated pin`() {
    // It is a scalar-valued entry, so rule 3 would match it too — but the same line under two
    // different fixes just makes the failure harder to read.
    val finding = lint(trail("    android:\n    driver: ANDROID_ONDEVICE_ACCESSIBILITY"))
    assertNotNull(finding)
    assertTrue(finding.legacyDriverForms.isEmpty())
  }

  // ---- Rule 1: `config.driver:` beside `config.devices:` — TWO levels too shallow (fatal) ----

  @Test
  fun `two levels too shallow becomes a session driver and is fatal`() {
    // `config.driver` is a real v1 field, so nothing else rejects this — the device is silently
    // unpinned and the session runs whatever `driver:` names.
    val finding = lint("config:\n  devices:\n    android:\n  driver: ANDROID_ONDEVICE_ACCESSIBILITY\n")
    assertNotNull(finding)
    assertTrue(finding.isFatal)
    assertEquals(listOf("android"), finding.sessionDriverBesideDevices?.declaredClassifiers)
    assertTrue(finding.legacyDriverForms.isEmpty())
  }

  @Test
  fun `a session driver without a devices block is allowed`() {
    assertNull(lint("config:\n  driver: ANDROID_ONDEVICE_ACCESSIBILITY\n"))
  }

  @Test
  fun `a correctly nested pin is not flagged`() {
    assertNull(lint(trail("    android:\n      driver: IOS_HOST")))
  }

  @Test
  fun `the reported line points at the offending driver key`() {
    val finding = lint("config:\n  devices:\n    android:\n  driver: IOS_HOST\n")
    assertNotNull(finding)
    assertEquals(4, finding.sessionDriverBesideDevices?.line)
  }

  // ---- Key spelling ----
  // A `config` or `devices` key the YAML parser accepts must be found however it is spelled.
  // A gate that matched raw text would miss these while the parser still reads the key, which
  // is a silent hole in a rule whose whole job is to be absolute. Reading the parsed node
  // rather than the line is what closes it — these pin that it stays closed.

  @Test
  fun `a double-quoted devices key is still scanned`() {
    val finding = lint("config:\n  \"devices\":\n    android: ANDROID_ONDEVICE_ACCESSIBILITY\n")
    assertNotNull(finding)
    assertEquals(listOf("android"), finding.legacyDriverForms.map { it.classifier })
  }

  @Test
  fun `a single-quoted devices key is still scanned`() {
    val finding = lint("config:\n  'devices':\n    android: ANDROID_ONDEVICE_ACCESSIBILITY\n")
    assertNotNull(finding)
    assertEquals(listOf("android"), finding.legacyDriverForms.map { it.classifier })
  }

  @Test
  fun `a space before the devices colon is still scanned`() {
    val finding = lint("config:\n  devices :\n    android: ANDROID_ONDEVICE_ACCESSIBILITY\n")
    assertNotNull(finding)
    assertEquals(listOf("android"), finding.legacyDriverForms.map { it.classifier })
  }

  @Test
  fun `a quoted config key is still scanned`() {
    val finding = lint("\"config\":\n  devices:\n    android:\n  driver: IOS_HOST\n")
    assertNotNull(finding)
    assertTrue(finding.isFatal)
  }

  @Test
  fun `a quoted driver key is still a mis-indent`() {
    val finding = lint(trail("    android:\n    \"driver\": ANDROID_ONDEVICE_ACCESSIBILITY"))
    assertNotNull(finding)
    assertTrue(finding.isFatal)
  }

  // ---- Non-trails and unparseable input ----

  @Test
  fun `unparseable yaml yields no finding`() {
    // The parse-level validators own that error; double-reporting one broken file as two failures
    // only makes triage worse.
    assertNull(lint("config:\n  devices:\n   - [unbalanced\n"))
  }

  @Test
  fun `a trail with no devices block yields no finding`() {
    assertNull(lint("config:\n  id: myapp/login\ntrail:\n  - step: Open the app\n"))
  }

  // ---- Phase-level exit-code contract ----

  private fun workspaceWith(fileName: String, body: String): java.io.File {
    val workspaceRoot = tmp.newFolder(fileName.substringBefore('.'))
    workspaceRoot.resolve("trails").apply { mkdirs() }.resolve(fileName).writeText(body)
    return workspaceRoot
  }

  @Test
  fun `check phase fails the build on a session driver beside devices`() {
    val workspaceRoot = workspaceWith(
      "misindented.trail.yaml",
      """
      config:
        id: test/misindented
        devices:
          android-phone:
        driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `check phase passes a trail using the deprecated form`() {
    // The bare-string branch is still live and unmigrated consumer repos run this same CLI, so the
    // deprecated form must warn rather than fail. Flip this when that decode branch is deleted.
    val workspaceRoot = workspaceWith(
      "legacy.trail.yaml",
      """
      config:
        id: test/legacy
        devices:
          android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_OK, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `check phase passes a trail written in the object form`() {
    val workspaceRoot = workspaceWith(
      "clean.trail.yaml",
      """
      config:
        id: test/clean
        devices:
          android-phone:
            driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_OK, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `check phase gates a trail written as an NL-definition filename`() {
    // `blaze.yaml` is a supported runnable trail filename — `TrailIndexBuilder` and the runner both
    // resolve it. A gate that only matched `*.trail.yaml` would let a mis-indented pin ship in a
    // trail the CLI happily runs, which is exactly the silent unpinning this gate exists to stop.
    val workspaceRoot = workspaceWith(
      "blaze.yaml",
      """
      config:
        id: test/nl-definition
        devices:
          android-phone:
          driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `the device-pin kill-switch spares the build`() {
    val workspaceRoot = workspaceWith(
      "switched-off.trail.yaml",
      """
      config:
        id: test/misindented
        devices:
          android-phone:
        driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(
      CheckCommand.EXIT_OK,
      CheckCommand().runTrailLintPhase(workspaceRoot, devicePinGateEnabled = false),
    )
  }

  @Test
  fun `disabling the selector-dialect gate leaves the device-pin gate armed`() {
    // The two kill-switches are separate on purpose: a migration that needs the dialect gate off
    // must not silently lose the device-pin ratchet as well.
    val workspaceRoot = workspaceWith(
      "still-armed.trail.yaml",
      """
      config:
        id: test/misindented
        devices:
          android-phone:
        driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(
      CheckCommand.EXIT_TYPE_ERROR,
      CheckCommand().runTrailLintPhase(workspaceRoot, selectorDialectGateEnabled = false),
    )
  }

  @Test
  fun `check phase fails the build on a driver-keyed device entry`() {
    val workspaceRoot = workspaceWith(
      "shallow.trail.yaml",
      """
      config:
        id: test/shallow
        devices:
          android-phone:
          driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recordable: false
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `a trail that fails to decode is still device-pin linted`() {
    // `recording:` naming a tool that doesn't exist makes decodeTrailDocument throw, which skips
    // the selector-dialect lint. The device-pin lint reads the source, so it still fires — a
    // mis-indented pin must not ride in on an unrelated decode failure.
    val workspaceRoot = workspaceWith(
      "undecodable.trail.yaml",
      """
      config:
        id: test/undecodable
        devices:
          android-phone:
        driver: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Open the app
          recording:
            android-phone:
              - thisToolDoesNotExist:
                  someArg: 1
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
  }
}
