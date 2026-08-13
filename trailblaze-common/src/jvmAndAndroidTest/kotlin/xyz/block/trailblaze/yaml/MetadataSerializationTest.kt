package xyz.block.trailblaze.yaml

import org.junit.Test
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataSerializationTest {

  private val trailblazeYaml = createTrailblazeYaml()

  @Test
  fun `can parse config metadata`() {
    val yaml = """
      config:
        id: "5056470"
        title: "Appointment checkout flow"
        description: "Test loyalty points received for purchase workflow"
        priority: "P0"
        metadata:
          environment: "staging"
      trail:
        - step: Navigate to checkout
        - verify: Verify text "Hello World" is visible
          recording:
            android:
              - assertVisibleWithText:
                  text: "Hello World"
    """.trimIndent()

    val trail = trailblazeYaml.decodeUnifiedTrail(yaml)

    assertEquals("5056470", trail.config.id)
    assertEquals("Appointment checkout flow", trail.config.title)
    assertEquals("Test loyalty points received for purchase workflow", trail.config.description)
    assertEquals("P0", trail.config.priority)
    assertEquals("staging", trail.config.metadata?.get("environment"))

    assertEquals(2, trail.trail.size)
  }

  @Test
  fun `can parse trail steps with no config block`() {
    val yaml = """
      trail:
        - step: Navigate to checkout
        - verify: Verify text "Hello World" is visible
          recording:
            android:
              - assertVisibleWithText:
                  text: "Hello World"
    """.trimIndent()

    val trail = trailblazeYaml.decodeUnifiedTrail(yaml)

    assertNull(trail.config.id)
    assertNull(trail.config.title)
    assertNull(trail.config.metadata)

    assertEquals(2, trail.trail.size)
  }

  @Test
  fun `absent config block decodes to an empty config`() {
    val yaml = """
      trail:
        - step: Navigate to checkout
    """.trimIndent()

    val config = trailblazeYaml.decodeUnifiedTrail(yaml).config

    assertNull(config.id)
    assertNull(config.title)
    assertNull(config.description)
    assertNull(config.priority)
    assertNull(config.metadata)
  }

  @Test
  fun `can parse config with only some fields populated`() {
    val yaml = """
      config:
        id: "test123"
        title: "Test Case"
      trail:
        - step: Navigate to checkout
    """.trimIndent()

    val config = trailblazeYaml.decodeUnifiedTrail(yaml).config

    assertEquals("test123", config.id)
    assertEquals("Test Case", config.title)
    assertNull(config.description)
    assertNull(config.priority)
    assertNull(config.metadata)
  }
}
