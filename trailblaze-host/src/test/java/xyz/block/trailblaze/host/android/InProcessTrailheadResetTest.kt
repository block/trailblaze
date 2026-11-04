package xyz.block.trailblaze.host.android

import kotlin.test.assertEquals
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.createTrailblazeYaml

class InProcessTrailheadResetTest {

  private val app = "com.example.app"

  /** App ids cleared for a trail whose trailhead records [tool] (YAML under `android-phone:`). */
  private fun appIdsToClear(
    tool: String,
    clearOnEveryTrailhead: Boolean = false,
    configMemory: Map<String, String> = emptyMap(),
    memory: Map<String, String> = emptyMap(),
    installedTargetAppIds: Set<String> = setOf(app),
  ): Set<String> {
    val yaml = buildString {
      appendLine("config:")
      appendLine("  id: example/trail")
      if (configMemory.isNotEmpty()) {
        appendLine("  memory:")
        configMemory.forEach { (key, value) -> appendLine("    $key: $value") }
      }
      appendLine("trailhead:")
      appendLine("  step: Launch the app")
      appendLine("  recording:")
      appendLine("    android-phone:")
      tool.lines().forEach { appendLine("      $it") }
      appendLine("trail:")
      appendLine("  - step: Tap the button")
    }
    return InProcessTrailheadReset.appIdsToClear(decode(yaml), installedTargetAppIds, clearOnEveryTrailhead, memory)
  }

  private fun decode(yaml: String) = createTrailblazeYaml().decodeTrail(
    yaml,
    deviceClassifiers = listOf(TrailblazeDeviceClassifier("android-phone")),
  )

  @Test
  fun `a templated appId is resolved from the trail's memory and the run's`() {
    val templated = "launchApp:\n  appId: '{{appId}}'"
    assertEquals(setOf(app), appIdsToClear(templated, configMemory = mapOf("appId" to app)))
    assertEquals(setOf(app), appIdsToClear(templated, memory = mapOf("appId" to app)))
    assertEquals(emptySet(), appIdsToClear(templated, memory = mapOf("appId" to "com.other.app")))
  }

  @Test
  fun `a templated appId the host cannot resolve clears the installed target`() {
    assertEquals(setOf(app), appIdsToClear("launchApp:\n  appId: '{{appId}}'"))
  }

  @Test
  fun `a templated appId the host cannot resolve clears nothing when several target ids are installed`() {
    val installed = setOf(app, "$app.debug")
    assertEquals(emptySet(), appIdsToClear("launchApp:\n  appId: '{{appId}}'", installedTargetAppIds = installed))
  }

  @Test
  fun `a trailhead that opens with a REINSTALL of the target clears it`() {
    assertEquals(setOf(app), appIdsToClear("launchApp:\n  appId: $app"))
  }

  @Test
  fun `a launch that keeps data does not clear`() {
    assertEquals(emptySet(), appIdsToClear("launchApp:\n  appId: $app\n  launchMode: FORCE_RESTART"))
    assertEquals(emptySet(), appIdsToClear("launchApp:\n  appId: $app\n  launchMode: RESUME"))
  }

  @Test
  fun `another app does not clear the target`() {
    assertEquals(emptySet(), appIdsToClear("launchApp:\n  appId: com.other.app"))
  }

  @Test
  fun `a scripted trailhead clears only when the target asks for it`() {
    val signIn = "myapp_launchAppSignedIn:\n  persona: owner"
    assertEquals(emptySet(), appIdsToClear(signIn))
    assertEquals(setOf(app), appIdsToClear(signIn, clearOnEveryTrailhead = true))
  }

  @Test
  fun `an empty trailhead recording still clears when the target asks for it`() {
    assertEquals(setOf(app), appIdsToClear("{}", clearOnEveryTrailhead = true))
    assertEquals(emptySet(), appIdsToClear("{}"))
  }

  @Test
  fun `a trail with no trailhead never clears`() {
    val items = createTrailblazeYaml().decodeTrail(
      "config:\n  id: example/trail\ntrail:\n  - step: Tap the button\n",
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android-phone")),
    )
    assertEquals(emptySet(), InProcessTrailheadReset.appIdsToClear(items, setOf(app), clearOnEveryTrailhead = true))
  }
}
