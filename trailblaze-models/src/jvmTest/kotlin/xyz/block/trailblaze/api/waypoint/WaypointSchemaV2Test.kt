package xyz.block.trailblaze.api.waypoint

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

// The classifier-lineage primitive itself (chainFor/resolutionChain) is owned and tested by
// TrailblazeClassifierLineageTest; these tests cover the waypoint v2 serializer + resolution that
// build on it.
class WaypointSchemaV2Test {

  private val yaml = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
  )

  // --- Serializer round-trip ---

  @Test
  fun `decode classifier-keyed definition`() {
    val src = """
      id: "myapp/items"
      description: "Items home."
      route: "myapp-scheme://items"
      android:
        required:
          - description: "All items row"
            selector:
              androidAccessibility:
                textRegex: "All items"
        example:
          file: "items.android.example.json"
          capturedAt: "2026-06-27T08:45:09Z"
      web:
        route: "https://app.example.com/items"
        required:
          - selector:
              web:
                ariaRole: "heading"
    """.trimIndent()
    val def = yaml.decodeFromString(WaypointDefinition.serializer(), src)
    assertEquals("myapp/items", def.id)
    assertEquals("Items home.", def.description)
    assertEquals("myapp-scheme://items", def.route)
    assertEquals(setOf("android", "web"), def.byClassifier.keys)
    assertEquals(1, def.byClassifier.getValue("android").required.size)
    assertEquals("items.android.example.json", def.byClassifier.getValue("android").example?.file)
    assertEquals("https://app.example.com/items", def.byClassifier.getValue("web").route)
  }

  @Test
  fun `a waypoint with only id (no classifier blocks) decodes to an empty byClassifier`() {
    // A legitimate "known place, selectors not captured yet" file — id (+ optional description) and
    // nothing else. Must load with an empty byClassifier rather than error.
    val src = """
      id: "myapp/placeholder"
      description: "Known place; selectors not captured yet."
    """.trimIndent()
    val def = yaml.decodeFromString(WaypointDefinition.serializer(), src)
    assertEquals("myapp/placeholder", def.id)
    assertEquals("Known place; selectors not captured yet.", def.description)
    assertTrue(def.byClassifier.isEmpty())
  }

  @Test
  fun `encode then decode round-trips`() {
    val def = WaypointDefinition(
      id = "myapp/home",
      description = "Home.",
      route = "myapp-scheme://home",
      byClassifier = linkedMapOf(
        "android" to WaypointVariant(
          required = listOf(
            WaypointCondition(
              description = "Home header",
              selector = TrailblazeNodeSelector(
                androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Home"),
              ),
            ),
          ),
          forbidden = listOf(
            WaypointCondition(
              selector = TrailblazeNodeSelector(
                androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "PIN"),
              ),
            ),
          ),
          example = WaypointExampleRef(file = "home.android.example.json", capturedAt = "2026-06-27T00:00:00Z"),
        ),
        "ios" to WaypointVariant(
          route = "myapp-scheme://home-ios",
          required = listOf(
            WaypointCondition(selector = TrailblazeNodeSelector(iosAxe = DriverNodeMatch.IosAxe(uniqueId = "HomeHeader"))),
          ),
        ),
      ),
    )
    val encoded = yaml.encodeToString(WaypointDefinition.serializer(), def)
    val decoded = yaml.decodeFromString(WaypointDefinition.serializer(), encoded)
    assertEquals(def, decoded)
  }

  // --- v1 rejection (hard cut: the legacy top-level shape no longer loads) ---

  @Test
  fun `v1 top-level required is rejected with a migrate message`() {
    val v1 = """
      id: "myapp/legacy"
      required:
        - selector:
            androidAccessibility:
              textRegex: "Home"
    """.trimIndent()
    val e = assertFailsWith<IllegalArgumentException> {
      yaml.decodeFromString(WaypointDefinition.serializer(), v1)
    }
    assertTrue(e.message!!.contains("legacy v1"), "message should explain the v1 rejection: ${e.message}")
    assertTrue(e.message!!.contains("classifier block"), "message should point at the v2 fix: ${e.message}")
  }

  @Test
  fun `v1 rejection names the waypoint id even when the legacy key precedes id`() {
    // The id is read straight from the parsed node, so the message is actionable regardless of
    // document key order (here `required:` comes before `id:`).
    val v1 = """
      required:
        - selector:
            androidAccessibility:
              textRegex: "Home"
      id: "myapp/legacy-id-last"
    """.trimIndent()
    val e = assertFailsWith<IllegalArgumentException> {
      yaml.decodeFromString(WaypointDefinition.serializer(), v1)
    }
    assertTrue(e.message!!.contains("myapp/legacy-id-last"), "message should name the id: ${e.message}")
  }

  @Test
  fun `v1 top-level forbidden is rejected`() {
    val v1 = """
      id: "myapp/legacy"
      forbidden:
        - selector:
            iosMaestro:
              accessibilityTextRegex: "PIN"
    """.trimIndent()
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeFromString(WaypointDefinition.serializer(), v1)
    }
  }

  @Test
  fun `v1 platforms key is rejected even alongside a valid classifier block`() {
    val v1 = """
      id: "myapp/legacy"
      platforms: ["android", "ios"]
      web:
        required:
          - selector:
              web:
                ariaRole: "heading"
    """.trimIndent()
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeFromString(WaypointDefinition.serializer(), v1)
    }
  }

  // --- Resolution ---

  @Test
  fun `resolveFor picks each field closest-wins up the lineage`() {
    val def = WaypointDefinition(
      id = "myapp/items",
      route = "myapp-scheme://items",
      byClassifier = linkedMapOf(
        "android" to WaypointVariant(
          required = listOf(WaypointCondition(selector = textSel("All items"))),
        ),
        "android-tablet" to WaypointVariant(
          example = WaypointExampleRef(file = "items.android-tablet.example.json"),
        ),
      ),
    )
    val resolved = def.resolveFor(TrailblazeDeviceClassifier("android-tablet"))
    // required comes from the family `android` block (the sparse `android-tablet` block only has example)
    assertEquals(1, resolved.required.size)
    // example comes from the most-specific `android-tablet` block
    assertEquals("items.android-tablet.example.json", resolved.example?.file)
    // route falls back to the top-level default
    assertEquals("myapp-scheme://items", resolved.route)
    assertEquals(TrailblazeDeviceClassifier("android-tablet"), resolved.classifier)
  }

  @Test
  fun `resolveFor example walks up the lineage to an ancestor block`() {
    val def = WaypointDefinition(
      id = "myapp/items",
      byClassifier = linkedMapOf(
        "android" to WaypointVariant(
          required = listOf(WaypointCondition(selector = textSel("All items"))),
          example = WaypointExampleRef(file = "items.android.example.json"),
        ),
        // A sibling form-factor with no example of its own.
        "android-phone" to WaypointVariant(required = listOf(WaypointCondition(selector = textSel("All items")))),
      ),
    )
    // android-tablet → lineage [android-tablet, android]; no tablet example, so it picks the
    // ancestor `android` block's example (NOT the android-phone sibling's, which isn't in the chain).
    val resolved = def.resolveFor(TrailblazeDeviceClassifier("android-tablet"))
    assertEquals("items.android.example.json", resolved.example?.file)
  }

  @Test
  fun `resolveFor example has no sibling fallback`() {
    val def = WaypointDefinition(
      id = "myapp/items",
      byClassifier = linkedMapOf(
        "android" to WaypointVariant(required = listOf(WaypointCondition(selector = textSel("All items")))),
        "android-phone" to WaypointVariant(example = WaypointExampleRef(file = "phone.json")),
      ),
    )
    // A tablet with no tablet/family example shows nothing — never the phone's.
    val resolved = def.resolveFor(TrailblazeDeviceClassifier("android-tablet"))
    assertNull(resolved.example)
  }

  @Test
  fun `variant route overrides the top-level default`() {
    val def = WaypointDefinition(
      id = "myapp/items",
      route = "myapp-scheme://items",
      byClassifier = linkedMapOf(
        "web" to WaypointVariant(route = "https://app.example.com/items"),
      ),
    )
    assertEquals("https://app.example.com/items", def.resolveFor(TrailblazeDeviceClassifier("web")).route)
  }

  private fun textSel(text: String) = TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = text),
  )
}
