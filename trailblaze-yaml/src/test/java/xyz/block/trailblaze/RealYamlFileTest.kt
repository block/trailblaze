package xyz.block.trailblaze

import org.junit.Test
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RealYamlFileTest {

  private val trailblazeYaml = TrailblazeYaml()

  @Test
  fun `can parse realistic YAML with config-based metadata`() {
    val yaml = """
      - config:
          id: "5056470"
          title: "Appointment checkout flow"
          description: "Test loyalty points received for purchase workflow"
      - prompts:
        - step: tap +
          recording:
            tools:
            - tapOnElementWithAccessibilityText:
                accessibilityText: Create Appointment
        - step: tap create appointment
          recording:
            tools:
            - tapOnElementWithText:
                text: Create appointment
      - maestro:
        - assertVisible:
            text: .*total Points
    """.trimIndent()

    // Test extracting metadata
    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNotNull(config)
    assertEquals("5056470", config.id)
    assertEquals("Appointment checkout flow", config.title)

    // Test parsing trail items
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    assertEquals(3, trailItems.size) // config, prompts, and maestro sections
  }

  @Test
  fun `can parse YAML without config item`() {
    val yaml = """
      - prompts:
        - step: Navigate to login
      - maestro:
        - assertVisible:
            text: Login
    """.trimIndent()

    val items = trailblazeYaml.decodeTrail(yaml)
    assertEquals(2, items.size)

    // Config should be null when no config item
    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNull(config)
  }
}
