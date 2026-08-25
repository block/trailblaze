package xyz.block.trailblaze.usages

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Behavioral contract of [TrailToolUsageScanner]: a usage is a recorded invocation with its
 * classifier keys — not a text mention — and tools nested inside a recorded conditional wrapper
 * count as usages of the inner tool too.
 */
class TrailToolUsageScannerTest {

  @Test
  fun `a recorded tool reports the classifier keys and steps that invoke it`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "t"),
      trailhead = UnifiedTrailStep(
        step = "Launch signed in",
        recordings = linkedMapOf("all" to listOf(toolNamed("demo_launchSignedIn"))),
      ),
      trail = listOf(
        UnifiedTrailStep(
          step = "Add an item",
          recordings = linkedMapOf(
            "android" to listOf(toolNamed("demo_addItem")),
            "ios-iphone" to listOf(toolNamed("demo_addItem"), toolNamed("demo_dismissModal")),
          ),
        ),
        UnifiedTrailStep(
          step = "Check out",
          recordings = linkedMapOf("android" to listOf(toolNamed("demo_addItem"))),
        ),
      ),
    )

    val usages = TrailToolUsageScanner.toolUsages(trail)

    val addItem = usages.getValue("demo_addItem")
    assertEquals(listOf(0, 1), addItem.map { it.stepIndex })
    assertEquals(listOf("android", "ios-iphone"), addItem[0].classifiers)
    assertEquals(listOf("android"), addItem[1].classifiers)

    val dismiss = usages.getValue("demo_dismissModal").single()
    assertEquals(listOf("ios-iphone"), dismiss.classifiers)
    assertEquals(
      listOf("android", "ios-iphone"),
      dismiss.declaredClassifiers,
      "the step's full declared key set rides along so consumers can closest-wins resolve",
    )

    val trailhead = usages.getValue("demo_launchSignedIn").single()
    assertNull(trailhead.stepIndex)
    assertEquals(listOf("all"), trailhead.classifiers)
  }

  @Test
  fun `a tool named only in step text is not a usage`() {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "t"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Use demo_addItem to add an item",
          recordings = linkedMapOf("android" to listOf(toolNamed("demo_tapAdd"))),
        ),
      ),
    )
    assertFalse("demo_addItem" in TrailToolUsageScanner.toolUsages(trail))
  }

  @Test
  fun `a recorded conditional's predicate and branch tools count, recursively`() {
    // The shape a runIf-style conditional actually records: ONE predicate tool-call under
    // `condition: { tool: {...} }`, and branch actions as arrays of single-key tool-calls under
    // `then:` / `else:` — a branch entry can itself be another conditional.
    val innerGuard = JsonObject(
      mapOf(
        "condition" to JsonObject(
          mapOf("tool" to JsonObject(mapOf("demo_assertGone" to JsonObject(emptyMap())))),
        ),
        "then" to JsonArray(listOf(JsonObject(mapOf("demo_deepDismiss" to JsonObject(emptyMap()))))),
      ),
    )
    val wrapper = TrailblazeToolYamlWrapper(
      name = "demo_runIf",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "demo_runIf",
        raw = JsonObject(
          mapOf(
            "negate" to JsonPrimitive(true),
            "condition" to JsonObject(
              mapOf("tool" to JsonObject(mapOf("demo_assertVisible" to JsonObject(emptyMap())))),
            ),
            "then" to JsonArray(
              listOf(
                JsonObject(mapOf("demo_dismissModal" to JsonObject(emptyMap()))),
                JsonObject(mapOf("demo_runIf" to innerGuard)),
              ),
            ),
            "else" to JsonArray(listOf(JsonObject(mapOf("demo_logSkipped" to JsonObject(emptyMap()))))),
          ),
        ),
      ),
    )
    assertEquals(
      setOf(
        "demo_runIf",
        "demo_assertVisible",
        "demo_dismissModal",
        "demo_assertGone",
        "demo_deepDismiss",
        "demo_logSkipped",
      ),
      TrailToolUsageScanner.toolNamesIn(wrapper),
    )
  }

  @Test
  fun `a wrapper nesting a plain tools list counts its entries`() {
    val wrapper = TrailblazeToolYamlWrapper(
      name = "demo_group",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "demo_group",
        raw = JsonObject(
          mapOf(
            "tools" to JsonArray(listOf(JsonObject(mapOf("demo_dismissModal" to JsonObject(emptyMap()))))),
          ),
        ),
      ),
    )
    assertEquals(setOf("demo_group", "demo_dismissModal"), TrailToolUsageScanner.toolNamesIn(wrapper))
  }

  @Test
  fun `an argument that isn't a tool list doesn't invent tool names`() {
    val wrapper = TrailblazeToolYamlWrapper(
      name = "demo_configure",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "demo_configure",
        raw = JsonObject(
          mapOf(
            // A scalar-list arg that happens to be named `tools` — its values are data, not calls.
            "tools" to JsonArray(listOf(JsonPrimitive("hammer"), JsonPrimitive("wrench"))),
            // A multi-key object inside a `then` array is not the single-key tool-call shape.
            "layout" to JsonObject(
              mapOf(
                "then" to JsonArray(
                  listOf(JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))),
                ),
              ),
            ),
            // A scalar `tool` arg is data, not a nested tool-call object.
            "tool" to JsonPrimitive("screwdriver"),
            // An items list of single-key objects under a non-tool key is data, not calls.
            "items" to JsonArray(listOf(JsonObject(mapOf("Coffee" to JsonObject(emptyMap()))))),
          ),
        ),
      ),
    )
    assertEquals(setOf("demo_configure"), TrailToolUsageScanner.toolNamesIn(wrapper))
  }

  @Test
  fun `a recorded conditional authored in yaml decodes into the shape the scanner harvests`() {
    // End-to-end guard for the nested-walk assumption, using the shape runIf-style conditionals
    // actually record in trail files: an unrecognized wrapper decodes to raw JSON carrying the
    // predicate under `condition.tool` and the branch actions under `then:`, in exactly the
    // single-key tool-call shape [TrailToolUsageScanner.toolNamesIn] walks.
    val yaml = """
      config:
        id: nested
      trail:
        - step: Dismiss the modal if it appears
          recording:
            android:
              - demo_runIf:
                  negate: true
                  condition:
                    tool:
                      demo_assertVisible:
                        selector:
                          text: Continue
                  then:
                    - demo_dismissModal:
                        selector:
                          text: Dismiss
    """.trimIndent()

    val trail = TrailblazeYaml.Default.decodeUnifiedTrail(yaml)
    val usages = TrailToolUsageScanner.toolUsages(trail)

    assertTrue("demo_assertVisible" in usages, "the guard's predicate tool should count as a usage")
    assertTrue("demo_dismissModal" in usages, "the guarded branch tool should count as a usage")
    assertEquals(listOf("android"), usages.getValue("demo_dismissModal").single().classifiers)
  }

  @Test
  fun `a broad key's invocation carries the step's full declared key set`() {
    // Replay resolves each device to its single closest key, so the `all:` recording is shadowed
    // on tablet devices here. The scanner reports authored keys; declaredClassifiers is what lets
    // a consumer detect the shadowing.
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "t"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Launch",
          recordings = linkedMapOf(
            "all" to listOf(toolNamed("demo_launch")),
            "android-tablet" to listOf(toolNamed("demo_launchTablet")),
          ),
        ),
      ),
    )

    val launch = TrailToolUsageScanner.toolUsages(trail).getValue("demo_launch").single()
    assertEquals(listOf("all"), launch.classifiers)
    assertEquals(listOf("all", "android-tablet"), launch.declaredClassifiers)
  }

  private fun toolNamed(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(emptyMap())),
  )
}
