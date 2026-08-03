package xyz.block.trailblaze.yaml

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RealYamlFileTest {

  private val trailblazeYaml = createTrailblazeYaml()
  private val androidClassifiers = listOf(TrailblazeDeviceClassifier("android"))

  @Test
  fun `can parse realistic YAML with config-based metadata`() {
    val yaml = """
      config:
        id: "5056470"
        title: "Appointment checkout flow"
        description: "Test loyalty points received for purchase workflow"
      trail:
        - step: tap +
          recording:
            android:
              - tapOnElementWithAccessibilityText:
                  accessibilityText: Create Appointment
        - step: tap create appointment
          recording:
            android:
              - tapOnElementWithText:
                  text: Create appointment
        - verify: Verify total points are visible
          recording:
            android:
              - assertVisibleWithText:
                  text: .*total Points
    """.trimIndent()

    // Test extracting metadata
    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNotNull(config)
    assertEquals("5056470", config.id)
    assertEquals("Appointment checkout flow", config.title)

    // Test parsing trail items
    val trailItems = trailblazeYaml.decodeTrail(yaml, deviceClassifiers = androidClassifiers)
    assertEquals(2, trailItems.size) // config and prompts sections
  }

  @Test
  fun `can parse YAML without config item`() {
    val yaml = """
      trail:
        - step: Navigate to login
        - verify: Verify login is visible
          recording:
            android:
              - assertVisibleWithText:
                  text: Login
    """.trimIndent()

    val items = trailblazeYaml.decodeTrail(yaml, deviceClassifiers = androidClassifiers)
    // A unified trail always lowers to a (synthesized) config item plus the prompts section.
    assertEquals(2, items.size)

    // No config metadata was declared, so extraction yields an empty config with no id.
    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNull(config?.id)
  }
}
