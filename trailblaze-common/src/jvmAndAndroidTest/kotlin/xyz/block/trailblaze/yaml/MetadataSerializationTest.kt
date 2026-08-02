package xyz.block.trailblaze.yaml

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataSerializationTest {

  private val trailblazeYaml = createTrailblazeYaml()

  @Test
  fun `can parse YAML with config-based metadata`() {
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

    val config = trailblazeYaml.extractTrailConfig(yaml)

    assertNotNull(config)
    assertEquals("5056470", config.id)
    assertEquals("Appointment checkout flow", config.title)
    assertEquals("Test loyalty points received for purchase workflow", config.description)
    assertEquals("P0", config.priority)
    assertEquals("staging", config.metadata?.get("environment"))

    // Also verify we can parse the trail items — lowering emits a config item + a prompts item.
    val trailItems =
      trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
    assertEquals(2, trailItems.size) // config, prompts
  }

  @Test
  fun `can parse YAML with trail items only and no config`() {
    val yaml = """
      trail:
        - step: Navigate to checkout
        - verify: Verify text "Hello World"is visible
          recording:
            android:
              - assertVisibleWithText:
                  text: "Hello World"
    """.trimIndent()

    // A config-less unified doc lowers to an empty (all-null) config rather than v1's absent-config
    // null; assert it carries no metadata — the equivalent "no config" signal.
    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNull(config?.id)
    assertNull(config?.title)
    assertNull(config?.metadata)

    val prompts = trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
    assertEquals(2, prompts.promptSteps.size)
  }

  @Test
  fun `extractTrailConfig returns empty config when no config block exists`() {
    val yaml = """
      trail:
        - step: Navigate to checkout
    """.trimIndent()

    // No `config:` block → unified defaults to an empty config, so every metadata field is null.
    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNull(config?.id)
    assertNull(config?.title)
    assertNull(config?.description)
    assertNull(config?.priority)
    assertNull(config?.metadata)
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

    val config = trailblazeYaml.extractTrailConfig(yaml)
    assertNotNull(config)
    assertEquals("test123", config.id)
    assertEquals("Test Case", config.title)
    assertNull(config.description)
    assertNull(config.priority)
    assertNull(config.metadata)
  }
}
