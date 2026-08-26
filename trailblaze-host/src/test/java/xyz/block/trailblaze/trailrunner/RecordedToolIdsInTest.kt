package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Behavioral contract of the desktop tool-usage routes' file scan: a usage is a recorded
 * invocation from the parsed trail model (prose mentions don't count, tools nested inside a
 * recorded conditional do), and a file the unified decoder can't parse falls back to the text
 * probe instead of silently reporting no usages.
 */
class RecordedToolIdsInTest {

  private val yaml = createTrailblazeYaml()

  @Test
  fun `recorded tools count, prose mentions don't, nested conditional tools do`() {
    val ids = recordedToolIdsIn(
      """
      config:
        id: t
      trail:
        - step: Use demo_proseOnly to add an item
          recording:
            android:
              - demo_addItem: {}
        - step: Dismiss the modal if it appears
          recording:
            android:
              - demo_runIf:
                  condition:
                    tool:
                      demo_assertVisible: {}
                  then:
                    - demo_dismissModal: {}
      """.trimIndent(),
      yaml,
    )

    assertTrue("demo_addItem" in ids)
    assertTrue("demo_runIf" in ids)
    assertTrue("demo_assertVisible" in ids, "a conditional's predicate tool is a usage")
    assertTrue("demo_dismissModal" in ids, "a guarded branch tool is a usage")
    assertFalse("demo_proseOnly" in ids, "a tool named only in step text is not a usage")
  }

  @Test
  fun `an undecodable file falls back to the text probe rather than reporting unused`() {
    val ids = recordedToolIdsIn(
      """
      - step: this is the retired v1 list-root shape
        recordedSteps:
          - demo_legacyTool: {}
      """.trimIndent(),
      yaml,
    )
    assertEquals(setOf("demo_legacyTool", "step"), ids)
  }
}
