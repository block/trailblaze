package xyz.block.trailblaze.yaml

import org.junit.Test
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataSerializationTest {

  private val trailblazeYaml = TrailblazeYaml()

  @Test
  fun `can parse YAML with config-based metadata`() {
    val yaml = """
      - config:
          id: "5056470"
          title: "Appointment checkout flow"
          description: "Test loyalty points received for purchase workflow"
          priority: "P0"
          metadata:
            environment: "staging"
      - prompts:
        - step: Navigate to checkout
      - maestro:
        - assertVisible:
            text: "Hello World"
    """.trimIndent()

    val config = trailblazeYaml.extractTrailConfig(yaml)

    assertNotNull(config)
    assertEquals("5056470", config.id)
    assertEquals("Appointment checkout flow", config.title)
    assertEquals("Test loyalty points received for purchase workflow", config.description)
    assertEquals("P0", config.priority)
    assertEquals("staging", config.metadata?.get("environment"))

    // Also verify we can parse the trail items
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    assertEquals(3, trailItems.size) // config, prompts, maestro
  }

  @Test
  fun `can parse YAML with trail items only and no config`() {
    val yaml = """
      - prompts:
        - step: Navigate to checkout
      - maestro:
        - assertVisible:
            text: "Hello World"
    """.trimIndent()

    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNull(config)

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    assertEquals(2, trailItems.size)
  }

  @Test
  fun `extractTrailConfig returns null when no config item exists`() {
    val yaml = """
      - prompts:
        - step: Navigate to checkout
    """.trimIndent()

    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNull(config)
  }

  @Test
  fun `can parse config with only some fields populated`() {
    val yaml = """
      - config:
          id: "test123"
          title: "Test Case"
      - prompts:
        - step: Navigate to checkout
    """.trimIndent()

    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNotNull(config)
    assertEquals("test123", config.id)
    assertEquals("Test Case", config.title)
    assertNull(config.description)
    assertNull(config.priority)
    assertNull(config.metadata)
  }
}
