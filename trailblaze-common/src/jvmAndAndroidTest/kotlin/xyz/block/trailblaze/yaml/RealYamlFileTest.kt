package xyz.block.trailblaze.yaml

import org.junit.Test
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealYamlFileTest {

  private val trailblazeYaml = createTrailblazeYaml()

  @Test
  fun `can parse realistic unified trail`() {
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

    val trail = trailblazeYaml.decodeUnifiedTrail(yaml)

    assertEquals("5056470", trail.config.id)
    assertEquals("Appointment checkout flow", trail.config.title)
    assertEquals("Test loyalty points received for purchase workflow", trail.config.description)

    assertEquals(3, trail.trail.size)
    assertEquals("tap +", trail.trail[0].step)
    assertEquals(listOf("tapOnElementWithAccessibilityText"), trail.trail[0].recordings.getValue("android").map { it.name })

    // The third step was authored as `verify:`, which the unified model records on the step
    // itself rather than as a separate item type.
    assertTrue(trail.trail[2].verify)
    assertEquals("Verify total points are visible", trail.trail[2].step)
  }

  @Test
  fun `can parse trail with no config block`() {
    val yaml = """
      trail:
        - step: Navigate to login
        - verify: Verify login is visible
          recording:
            android:
              - assertVisibleWithText:
                  text: Login
    """.trimIndent()

    val trail = trailblazeYaml.decodeUnifiedTrail(yaml)

    // `config:` is optional — an absent block decodes to an empty config, not an error.
    assertNull(trail.config.id)
    assertNull(trail.config.title)

    assertEquals(2, trail.trail.size)
    // A step with no `recording:` runs in LLM mode.
    assertTrue(trail.trail[0].recordings.isEmpty())
  }
}
