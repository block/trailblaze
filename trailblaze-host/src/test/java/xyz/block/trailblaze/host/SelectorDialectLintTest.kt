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
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.cli.CheckCommand
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Pins [SelectorDialectLint]'s pure decision contract: given a parsed trail, which devices resolve a
 * recording leg whose dialect their driver cannot match. Behavior-level assertions on the finding's
 * structured fields — never on message wording — plus one test through the real YAML parse path and
 * the phase-level exit-code contract.
 *
 * The two anchor cases are the split that motivated making this leg-aware: a shared `android:` leg
 * resolved by an accessibility device is a finding, while the same trail with that leg properly
 * split per device is clean.
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

  private fun assertNotVisibleBySelector(selectorArgs: kotlinx.serialization.json.JsonObject): TrailblazeToolYamlWrapper =
    TrailblazeToolYamlWrapper(
      name = "assertNotVisibleBySelector",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "assertNotVisibleBySelector",
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

  private fun webSelector(name: String) = buildJsonObject {
    putJsonObject("web") { put("ariaNameRegex", name) }
  }

  private fun trail(
    devices: Map<String, String>?,
    steps: List<UnifiedTrailStep>,
    trailhead: UnifiedTrailStep? = null,
  ) = UnifiedTrail(
    config = UnifiedTrailConfig(
      id = "test/dialect",
      // Fixtures declare pins as driver-name strings; lift them into the typed device model here
      // so every case reads as the classifier→driver table it is testing.
      devices = devices?.mapValues { (_, driverName) ->
        TrailblazeDeviceDefinition(driver = TrailblazeDriverType.fromString(driverName)!!)
      },
    ),
    trailhead = trailhead,
    trail = steps,
  )

  private fun step(vararg recordings: Pair<String, List<TrailblazeToolYamlWrapper>>) =
    UnifiedTrailStep(step = "do the thing", recordings = recordings.toMap())

  @Test
  fun `accessibility device resolving a shared maestro leg is a finding`() {
    // The migration hazard: android-tablet flipped to accessibility, but the step still shares one
    // `android:` leg carrying Maestro selectors, which the tablet's chain resolves.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf(
          "android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION",
          "android-tablet" to "ANDROID_ONDEVICE_ACCESSIBILITY",
        ),
        steps = listOf(step("android" to listOf(tapBySelector(androidMaestroSelector("Checkout"))))),
      ),
    )
    assertNotNull(result)
    assertEquals("t/trail.yaml", result.trailRelPath)
    assertEquals(1, result.selectorCount)
    assertEquals(mapOf("android-tablet" to "ANDROID_ONDEVICE_ACCESSIBILITY"), result.affectedDevices)
    val occurrence = result.occurrences.single()
    assertEquals("androidMaestro", occurrence.dialectKey)
    assertEquals("tapOnElementBySelector", occurrence.toolName)
    assertEquals("android", occurrence.resolvedClassifier)
    assertEquals("android-tablet", occurrence.deviceClassifier)
    assertEquals(0, occurrence.stepIndex)
    // The unmatchable-selector render is the gate's primary output, so its label is what an
    // operator actually reads: 1-based, though the field above stays 0-based.
    val rendered = SelectorDialectLint.renderFailures(listOf(result))
    assertTrue(rendered.contains("step 1 resolves leg 'android'"), rendered)
  }

  @Test
  fun `assertNotVisibleBySelector is not a finding because it falls back to maestro lowering`() {
    // Same broken topology as the anchor case, but the only selector-bearing tool is the one whose
    // dispatch survives the wrong dialect: AccessibilityTrailblazeAgent returns null for a
    // non-accessibility branch and the tool re-dispatches through Maestro lowering against the live
    // UI. Nothing fails at run time, so nothing may fail the build.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-tablet" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(
          step("android" to listOf(assertNotVisibleBySelector(androidMaestroSelector("Gone")))),
        ),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `a step mixing safe-fallback and hard-failing tools reports only the hard-failing one`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-tablet" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(
          step(
            "android" to listOf(
              assertNotVisibleBySelector(androidMaestroSelector("Gone")),
              tapBySelector(androidMaestroSelector("Checkout")),
            ),
          ),
        ),
      ),
    )
    assertNotNull(result)
    assertEquals(1, result.selectorCount)
    assertEquals("tapOnElementBySelector", result.occurrences.single().toolName)
  }

  @Test
  fun `accessibility selectors under the instrumentation driver are out of scope`() {
    // The inverse pairing. Not gated: the instrumentation agent resolves no nodeSelector natively,
    // so these lower to Maestro and match the live UiAutomator hierarchy — not the silent every-run
    // NoMatch this gate exists for.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-tablet" to "ANDROID_ONDEVICE_INSTRUMENTATION"),
        steps = listOf(
          step("android" to listOf(tapBySelector(androidAccessibilitySelector("Checkout")))),
        ),
      ),
    )
    assertNull(result)
  }

  /**
   * A wrapper tool's recording, in the verbatim shape `block_runIf` records: inner tool-calls live
   * inside the wrapper's own args as `{ <toolName>: <args> }`, under `condition.tool` and `then:`.
   */
  private fun runIfWrapper(
    conditionTool: Pair<String, kotlinx.serialization.json.JsonObject>? = null,
    conditionSelector: kotlinx.serialization.json.JsonObject? = null,
    then: List<TrailblazeToolYamlWrapper> = emptyList(),
  ): TrailblazeToolYamlWrapper = TrailblazeToolYamlWrapper(
    name = "block_runIf",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "block_runIf",
      raw = buildJsonObject {
        putJsonObject("condition") {
          conditionSelector?.let { put("selector", it) }
          conditionTool?.let { (name, args) -> putJsonObject("tool") { put(name, args) } }
        }
        put(
          "then",
          kotlinx.serialization.json.buildJsonArray {
            then.forEach { inner -> add(buildJsonObject { put(inner.name, inner.toJsonArgs()) }) }
          },
        )
      },
    ),
  )

  @Test
  fun `assertNotVisibleBySelector nested in a wrapper tool is not a finding`() {
    // `block_runIf` records verbatim, so its inner calls sit in its own args. Nested dispatch
    // reaches the same accessibility override as a top-level call and falls back the same way —
    // excluding only the TOP-LEVEL tool name would have made this a fatal finding on a step that
    // passes, which is the one failure class a fatal gate cannot have.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-tablet" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(
          step(
            "android" to listOf(
              runIfWrapper(
                conditionTool = "assertNotVisibleBySelector" to
                  buildJsonObject { put("nodeSelector", androidMaestroSelector("Gone")) },
                then = listOf(assertNotVisibleBySelector(androidMaestroSelector("AlsoGone"))),
              ),
            ),
          ),
        ),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `hard-failing tools nested in a wrapper tool are still findings`() {
    // The recursive walk is a feature everywhere else: a wrong-dialect `condition.selector:` makes
    // the conditional silently always-false, and a nested tap hard-fails when its branch runs.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-tablet" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(
          step(
            "android" to listOf(
              runIfWrapper(
                conditionSelector = androidMaestroSelector("Banner"),
                then = listOf(tapBySelector(androidMaestroSelector("Close"))),
              ),
            ),
          ),
        ),
      ),
    )
    assertNotNull(result)
    assertEquals(2, result.selectorCount)
    assertTrue(result.occurrences.all { it.toolName == "block_runIf" })
  }

  @Test
  fun `mixed-driver trail with legs split per device is clean`() {
    // Same two devices and same selector values as the case above, but each device resolves its own
    // leg in its own driver's dialect. This is the shape the coarse trail+platform lint could not
    // tell apart from the broken one, and the reason it could never be fatal.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf(
          "android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY",
          "android-tablet" to "ANDROID_ONDEVICE_INSTRUMENTATION",
        ),
        steps = listOf(
          step(
            "android-phone" to listOf(tapBySelector(androidAccessibilitySelector("Checkout"))),
            "android-tablet" to listOf(tapBySelector(androidMaestroSelector("Checkout"))),
          ),
        ),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `device pinned by a broader key still resolves a narrower offending leg`() {
    // `devices:` pins the family (`android`) while the step keys a narrower leg (`android-phone`).
    // The candidate set has to include declared leg keys or this breakage is invisible.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("android-phone" to listOf(tapBySelector(androidMaestroSelector("Checkout"))))),
      ),
    )
    assertNotNull(result)
    assertEquals("android-phone", result.occurrences.single().resolvedClassifier)
    assertEquals("android-phone", result.occurrences.single().deviceClassifier)
  }

  @Test
  fun `device whose chain never reaches the offending leg is clean`() {
    // kiosk-t3's chain is [kiosk-t3, kiosk, all] — it never resolves an `android:` leg, so that
    // step runs in LLM mode for it rather than handing it an unmatchable selector.
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
    assertNull(result)
  }

  @Test
  fun `maestro selectors under only maestro-dialect drivers are clean`() {
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
        devices = mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("android" to listOf(tapBySelector(androidAccessibilitySelector("Checkout"))))),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `ios maestro selector under an ios axe pin is clean (resolver bridges iosMaestro to AXe)`() {
    // TrailblazeNodeSelectorResolver.matchesIosMaestroAgainstAxe keeps iosMaestro selectors
    // resolvable under the AXe driver, so this pair is matchable — unlike the Android pair.
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
          "android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY",
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
        devices = mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("android" to listOf(tapBySelector(nested)))),
      ),
    )
    assertNotNull(result)
    assertEquals(1, result.selectorCount)
  }

  @Test
  fun `trailhead recordings are scanned and reported as the trailhead`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = emptyList(),
        trailhead = step("android" to listOf(tapBySelector(androidMaestroSelector("Launch")))),
      ),
    )
    assertNotNull(result)
    assertNull(result.occurrences.single().stepIndex)
  }

  @Test
  fun `finding counts every occurrence but caps the example list`() {
    val tools = (1..5).map { tapBySelector(androidMaestroSelector("Item $it")) }
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
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
    assertEquals(mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"), result.affectedDevices)
    assertEquals("androidMaestro", result.occurrences.single().dialectKey)
  }

  @Test
  fun `check phase fails the build when a trail has a finding`() {
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
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runSelectorDialectLintPhase(workspaceRoot))
  }

  @Test
  fun `check phase passes a workspace whose legs are split per device`() {
    val workspaceRoot = tmp.newFolder("workspace")
    val trailsDir = workspaceRoot.resolve("trails").apply { mkdirs() }
    trailsDir.resolve("clean.trail.yaml").writeText(
      """
      config:
        id: test/clean
        devices:
          android-phone: ANDROID_ONDEVICE_ACCESSIBILITY
          android-tablet: ANDROID_ONDEVICE_INSTRUMENTATION
      trail:
        - step: "Tap on Checkout"
          recording:
            android-phone:
              - tapOnElementBySelector:
                  nodeSelector:
                    androidAccessibility:
                      textRegex: Checkout
            android-tablet:
              - tapOnElementBySelector:
                  nodeSelector:
                    androidMaestro:
                      textRegex: Checkout
      """.trimIndent(),
    )
    assertEquals(CheckCommand.EXIT_OK, CheckCommand().runSelectorDialectLintPhase(workspaceRoot))
  }

  @Test
  fun `check phase returns OK when the workspace has no trails directory`() {
    assertEquals(CheckCommand.EXIT_OK, CheckCommand().runSelectorDialectLintPhase(tmp.newFolder("empty")))
  }

  // ---- Multi-device configuration legs ----
  // A leg keyed by a configuration NAME is not a device identity, but its tools dispatch for
  // real — against whichever member is active at that point in the replay. The lint replays the
  // leg statically, flipping on recorded switchDevice calls.

  private fun switchDevice(name: String) = TrailblazeToolYamlWrapper(
    name = "switchDevice",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "switchDevice",
      raw = buildJsonObject { put("name", name) },
    ),
  )

  /** seller (start device) is accessibility, buyer is instrumentation. */
  private fun configurationTrail(
    steps: List<UnifiedTrailStep>,
    extraDevices: Map<String, TrailblazeDeviceDefinition> = emptyMap(),
    buyerDefinition: TrailblazeDeviceDefinition =
      TrailblazeDeviceDefinition(driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION),
    sellerDefinition: TrailblazeDeviceDefinition =
      TrailblazeDeviceDefinition(driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
  ) = UnifiedTrail(
    config = UnifiedTrailConfig(
      id = "test/dialect",
      devices = mapOf(
        "pos-pair" to TrailblazeDeviceDefinition(
          devices = linkedMapOf("seller" to sellerDefinition, "buyer" to buyerDefinition),
        ),
      ) + extraDevices,
    ),
    trail = steps,
  )

  @Test
  fun `a configuration leg is linted against the start member's driver`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      configurationTrail(
        steps = listOf(step("pos-pair" to listOf(tapBySelector(androidMaestroSelector("Checkout"))))),
      ),
    )
    assertNotNull(result)
    val occurrence = result.occurrences.single()
    assertEquals("pos-pair/seller", occurrence.deviceClassifier)
    assertEquals("pos-pair", occurrence.resolvedClassifier)
    assertEquals("androidMaestro", occurrence.dialectKey)
  }

  @Test
  fun `a recorded switchDevice flips which member's driver judges the rest of the leg`() {
    // First tap runs on seller (accessibility → finding); the mid-leg switch hands the session
    // to buyer (instrumentation), so the second tap — and the NEXT step's tap, because the
    // active device persists across steps — are clean.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      configurationTrail(
        steps = listOf(
          step(
            "pos-pair" to listOf(
              tapBySelector(androidMaestroSelector("Before switch")),
              switchDevice("buyer"),
              tapBySelector(androidMaestroSelector("After switch")),
            ),
          ),
          step("pos-pair" to listOf(tapBySelector(androidMaestroSelector("Next step")))),
        ),
      ),
    )
    assertNotNull(result)
    val occurrence = result.occurrences.single()
    assertEquals("pos-pair/seller", occurrence.deviceClassifier)
    assertEquals(0, occurrence.stepIndex)
  }

  @Test
  fun `a handover to an undeclared member is reported and abandons the rest of the configuration`() {
    // Keeping the stale active member would judge everything after the handover against a device
    // the replay has already left — reporting one driver's findings under another's name, or
    // missing real ones. But abandoning silently would leave the gate green on a trail whose every
    // later selector went unchecked, so the handover itself is the finding. Both taps below would
    // otherwise be findings on seller.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      configurationTrail(
        steps = listOf(
          step(
            "pos-pair" to listOf(
              switchDevice("kitchen"),
              tapBySelector(androidMaestroSelector("After the unknown handover")),
            ),
          ),
          step("pos-pair" to listOf(tapBySelector(androidMaestroSelector("Next step")))),
        ),
      ),
    )
    assertNotNull(result)
    assertTrue(result.occurrences.isEmpty())
    val handover = result.undeclaredHandovers.single()
    assertEquals("pos-pair", handover.configurationName)
    assertEquals("kitchen", handover.target)
    assertEquals(0, handover.stepIndex)
    assertEquals(listOf("seller", "buyer"), handover.declaredMembers)
    val rendered = SelectorDialectLint.renderFailures(listOf(result))
    assertTrue(rendered.contains("undeclared handover"), rendered)
    // 1-based in the render, matching what a reader counts under `trail:` and the label
    // MultiDeviceHandoverGuard puts on the same handover. The field itself stays 0-based.
    assertTrue(rendered.contains("step 1 in configuration 'pos-pair'"), rendered)
  }

  @Test
  fun `a handover to a memory-interpolated name is not a finding`() {
    // `switchDevice: {{buyerDevice}}` resolves at the tool-dispatch boundary, from memory this
    // gate never sees. Failing the build on it would reject a legitimate trail; the lint can only
    // stop tracking the active device, which is why the tap below goes unreported.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      configurationTrail(
        steps = listOf(
          step(
            "pos-pair" to listOf(
              switchDevice("{{buyerDevice}}"),
              tapBySelector(androidMaestroSelector("After the templated handover")),
            ),
          ),
        ),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `findings before an undeclared handover are still reported`() {
    // Abandoning the rest of the configuration must not discard what was already judged against a
    // known-active device.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      configurationTrail(
        steps = listOf(
          step(
            "pos-pair" to listOf(
              tapBySelector(androidMaestroSelector("Before the handover")),
              switchDevice("kitchen"),
              tapBySelector(androidMaestroSelector("After the handover")),
            ),
          ),
        ),
      ),
    )
    assertNotNull(result)
    val occurrence = result.occurrences.single()
    assertEquals("pos-pair/seller", occurrence.deviceClassifier)
    assertEquals(0, occurrence.stepIndex)
    assertEquals("kitchen", result.undeclaredHandovers.single().target)
  }

  /** phone (start device, accessibility) + dashboard (a host browser). */
  private fun webPhoneTrail(steps: List<UnifiedTrailStep>) = UnifiedTrail(
    config = UnifiedTrailConfig(
      id = "test/web-phone",
      devices = mapOf(
        "web-phone" to TrailblazeDeviceDefinition(
          devices = linkedMapOf(
            "phone" to TrailblazeDeviceDefinition(
              driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
            ),
            "dashboard" to TrailblazeDeviceDefinition(driver = TrailblazeDriverType.PLAYWRIGHT_NATIVE),
          ),
        ),
      ),
    ),
    trail = steps,
  )

  @Test
  fun `a web selector while the phone is active is a finding`() {
    // The web+phone mistake: a dashboard step recorded (or re-ordered) before its handover. The
    // resolver has no cross-platform bridge, so this tap resolves NoMatch on every run — the same
    // silent every-run failure the same-platform pair produces, which is why it's fatal too.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      webPhoneTrail(
        steps = listOf(step("web-phone" to listOf(tapBySelector(webSelector("Transactions"))))),
      ),
    )
    assertNotNull(result)
    val occurrence = result.occurrences.single()
    assertEquals("web-phone/phone", occurrence.deviceClassifier)
    assertEquals("web", occurrence.dialectKey)
    assertTrue(occurrence.crossPlatform)
    val rendered = SelectorDialectLint.renderFailures(listOf(result))
    assertTrue(rendered.contains("different platform"), rendered)
  }

  @Test
  fun `a native selector while the browser is active is a finding`() {
    // The inverse, and the reason the rule is symmetric rather than a web-selector blocklist: the
    // browser can't read an Android accessibility tree either.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      webPhoneTrail(
        steps = listOf(
          step(
            "web-phone" to listOf(
              switchDevice("dashboard"),
              tapBySelector(androidAccessibilitySelector("Charge")),
            ),
          ),
        ),
      ),
    )
    assertNotNull(result)
    val occurrence = result.occurrences.single()
    assertEquals("web-phone/dashboard", occurrence.deviceClassifier)
    assertEquals("androidAccessibility", occurrence.dialectKey)
    assertTrue(occurrence.crossPlatform)
  }

  @Test
  fun `each surface selecting in its own dialect is clean`() {
    // The trail this gate must NOT flag: the phone leg in androidAccessibility, the dashboard leg
    // in web, split by the handover between them.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      webPhoneTrail(
        steps = listOf(
          step("web-phone" to listOf(tapBySelector(androidAccessibilitySelector("Charge")))),
          step(
            "web-phone" to listOf(
              switchDevice("dashboard"),
              tapBySelector(webSelector("Transactions")),
            ),
          ),
        ),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `a single-device web trail is not judged against the launch platform`() {
    // Cross-platform detection is scoped to configuration legs on purpose. A single-device leg
    // reaches its device through the classifier chain, where a foreign dialect is authoring
    // nonsense no recording produces — and gating it there would fire on legs kept for a device
    // this run doesn't schedule.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      trail(
        devices = mapOf("android-phone" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
        steps = listOf(step("android-phone" to listOf(tapBySelector(webSelector("Transactions"))))),
      ),
    )
    assertNull(result)
  }

  @Test
  fun `members carrying only a classifier are still judged across platforms`() {
    // The usual authored shape: a cast declares `classifier:` per member and pins no drivers, so
    // no driver resolves from config.devices: at all. The cross-platform rule needs only the
    // platform the classifier folds to, and skipping these would leave the common case unlinted.
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      UnifiedTrail(
        config = UnifiedTrailConfig(
          id = "test/web-phone",
          devices = mapOf(
            "web-phone" to TrailblazeDeviceDefinition(
              devices = linkedMapOf(
                "phone" to TrailblazeDeviceDefinition(classifier = "android-phone"),
                "dashboard" to TrailblazeDeviceDefinition(classifier = "web"),
              ),
            ),
          ),
        ),
        trail = listOf(step("web-phone" to listOf(tapBySelector(webSelector("Transactions"))))),
      ),
    )
    assertNotNull(result)
    val occurrence = result.occurrences.single()
    assertEquals("web-phone/phone", occurrence.deviceClassifier)
    assertTrue(occurrence.crossPlatform)
  }

  @Test
  fun `a member without its own driver pin resolves it from config devices via its classifier`() {
    val result = SelectorDialectLint.lint(
      "t/trail.yaml",
      configurationTrail(
        steps = listOf(step("pos-pair" to listOf(tapBySelector(androidMaestroSelector("Checkout"))))),
        sellerDefinition = TrailblazeDeviceDefinition(classifier = "android-tablet"),
        extraDevices = mapOf(
          "android-tablet" to
            TrailblazeDeviceDefinition(driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
        ),
      ),
    )
    assertNotNull(result)
    assertEquals("pos-pair/seller", result.occurrences.single().deviceClassifier)
  }
}
