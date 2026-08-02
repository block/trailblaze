package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.cli.CheckCommand
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Pins [SelectorDialectLint]'s pure decision contract: given a parsed trail (its `config.devices:`
 * driver pins + recorded selectors), which trails get a warning finding. Behavior-level assertions
 * on the finding's structured fields — never on warning-message wording — plus one test through the
 * real YAML parse path and the phase-level guarantee that findings never change `check`'s exit code.
 */
class SelectorDialectLintTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun tapBySelector(selectorArgs: kotlinx.serialization.json.JsonObject): TrailblazeToolYamlWrapper =
    TrailblazeToolYamlWrapper(
      name = "tapOnElementBySelector",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "tapOnElementBySelector",
        raw = buildJsonObject { put("nodeSelector", selectorArgs) },
      ),
    )

  private fun androidMaestroSelector(text: String) = buildJsonObject {
    putJsonObject("androidMaestro") { put("textRegex", text) }
  }

  private fun androidAccessibilitySelector(text: String) = buildJsonObject {
    putJsonObject("androidAccessibility") { put("textRegex", text) }
  }

  private fun iosMaestroSelector(text: String) = buildJsonObject {
    putJsonObject("iosMaestro") { put("textRegex", text) }
  }

  private fun trail(
    devices: Map<String, String>?,
    steps: List<UnifiedTrailStep>,
    trailhead: UnifiedTrailStep? = null,
  ) = UnifiedTrail(
    config = UnifiedTrailConfig(id = "test/dialect", devices = devices),
    trailhead = trailhead,
    trail = steps,
  )

  private fun step(vararg recordings: Pair<String, List<TrailblazeToolYamlWrapper>>) =
    UnifiedTrailStep(step = "do the thing", recordings = recordings.toMap())

  @Test
  fun `maestro selector with a native android driver pin is a finding`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf(
          "android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION",
          "kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY",
        ),
        steps = listOf(step("android" to listOf(tapBySelector(androidMaestroSelector("Checkout"))))),
      ),
    )
    assertNotNull(result)
    assertEquals("t/trail.yaml", result.trailRelPath)
    assertEquals(1, result.selectorCount)
    assertEquals(mapOf("kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY"), result.nativeDevicePins)
    val example = result.examples.single()
    assertEquals("androidMaestro", example.dialectKey)
    assertEquals("tapOnElementBySelector", example.toolName)
    assertEquals("android", example.classifier)
    assertEquals(1, example.stepIndex)
  }

  @Test
  fun `maestro selector with only maestro-dialect driver pins is clean`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf(
          "android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION",
          "ios-iphone" to "IOS_HOST",
        ),
        steps = listOf(
          step(
            "android" to listOf(tapBySelector(androidMaestroSelector("Checkout"))),
            "ios" to listOf(tapBySelector(iosMaestroSelector("Checkout"))),
          ),
        ),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `native selectors with a native driver pin are clean`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("kiosk-t3" to listOf(tapBySelector(androidAccessibilitySelector("Checkout"))))),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `ios maestro selector with an ios axe pin is clean (resolver bridges iosMaestro under AXe)`() {
    // TrailblazeNodeSelectorResolver.matchesIosMaestroAgainstAxe keeps Maestro semantics for
    // iosMaestro selectors under the AXe driver, so this pair is not a semantics flip.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("ios-iphone" to "IOS_AXE"),
        steps = listOf(step("ios" to listOf(tapBySelector(iosMaestroSelector("Save"))))),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `native pin on one platform does not flag the other platform's maestro selectors`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf(
          "kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY",
          "ios-iphone" to "IOS_HOST",
        ),
        steps = listOf(step("ios" to listOf(tapBySelector(iosMaestroSelector("Checkout"))))),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `trail with no device pins is skipped`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = null,
        steps = listOf(step("android" to listOf(tapBySelector(androidMaestroSelector("Checkout"))))),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `maestro selector nested under a hierarchy relation is counted`() {
    val nested = buildJsonObject {
      putJsonObject("containsChild") {
        putJsonObject("androidMaestro") { put("textRegex", "Create an item") }
      }
    }
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("android" to listOf(tapBySelector(nested)))),
      ),
    )
    assertNotNull(result)
    assertEquals(1, result.selectorCount)
  }

  @Test
  fun `trailhead recordings are scanned as step zero`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = emptyList(),
        trailhead = step("android" to listOf(tapBySelector(androidMaestroSelector("Launch")))),
      ),
    )
    assertNotNull(result)
    assertEquals(0, result.examples.single().stepIndex)
  }

  @Test
  fun `finding counts every occurrence but caps the example list`() {
    val tools = (1..5).map { tapBySelector(androidMaestroSelector("Item $it")) }
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("kiosk-t3" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("android" to tools)),
      ),
    )
    assertNotNull(result)
    assertEquals(5, result.selectorCount)
    assertTrue(result.examples.size < 5)
  }

  @Test
  fun `lints a trail parsed through the real YAML path`() {
    val yamlText = """
      config:
        id: test/dialect-fixture
        devices:
          android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: "Tap on Checkout"
          recording:
            android:
              - tapOnElementBySelector:
                  nodeSelector:
                    androidMaestro:
                      textRegex: Checkout
    """.trimIndent()
    val doc = createTrailblazeYaml().decodeTrailDocument(yamlText)
    val unified = when (doc) {
      is TrailDocument.Unified -> doc.trail
    }
    val result = SelectorDialectLint.lint("fixture/trail.yaml", unified)
    assertNotNull(result)
    assertEquals(1, result.selectorCount)
    assertEquals(mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"), result.nativeDevicePins)
    assertEquals("androidMaestro", result.examples.single().dialectKey)
  }

  @Test
  fun `check phase exit code never changes because of lint findings`() {
    val workspaceRoot = tmp.newFolder("workspace")
    val trailsDir = workspaceRoot.resolve("trails").apply { mkdirs() }
    trailsDir.resolve("offending.trail.yaml").writeText(
      """
      config:
        id: test/offending
        devices:
          android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: "Tap on Checkout"
          recording:
            android:
              - tapOnElementBySelector:
                  nodeSelector:
                    androidMaestro:
                      textRegex: Checkout
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_OK, CheckCommand().runSelectorDialectLintPhase(workspaceRoot))
  }
}
