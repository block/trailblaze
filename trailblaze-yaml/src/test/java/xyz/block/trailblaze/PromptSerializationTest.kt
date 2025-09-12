package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.VerificationStep

class PromptSerializationTest {
  private val trailblazeYaml = TrailblazeYaml()

  @Test
  fun canDeserializePromptWithStepAndVerify() {
    val yaml = """
- prompts:
  - step: Do a thing
    recordable: false
  - step: Do another thing
    recording:
      tools:
        - inputText:
            text: Hello
  - verify: Check a thing
    recordable: false
  - verify: Check another thing
    recording:
      tools:
        - assertVisibleWithText:
            text: Bingo
    """.trimIndent()
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as PromptsTrailItem) {
        assertThat(promptSteps.size).isEqualTo(4)
        assertThat(promptSteps[0]).isEqualTo(
          DirectionStep(
            step = "Do a thing",
            recordable = false,
            recording = null,
          ),
        )
        assertThat(promptSteps[1]).isEqualTo(
          DirectionStep(
            step = "Do another thing",
            recordable = true,
            recording = ToolRecording(
              listOf(
                TrailblazeToolYamlWrapper(
                  name = "inputText",
                  trailblazeTool = InputTextTrailblazeTool(
                    text = "Hello",
                  ),
                ),
              ),
            ),
          ),
        )
        assertThat(promptSteps[2]).isEqualTo(
          VerificationStep(
            verify = "Check a thing",
            recordable = false,
            recording = null,
          ),
        )
        assertThat(promptSteps[3]).isEqualTo(
          VerificationStep(
            verify = "Check another thing",
            recordable = true,
            recording = ToolRecording(
              listOf(
                TrailblazeToolYamlWrapper(
                  name = "assertVisibleWithText",
                  trailblazeTool = AssertVisibleWithTextTrailblazeTool(
                    text = "Bingo",
                  ),
                ),
              ),
            ),
          ),
        )
      }
    }
  }

  @Test
  fun canSerializePromptWithStepAndVerify() {
    val trailItems: List<TrailYamlItem> = listOf(
      PromptsTrailItem(
        listOf(
          DirectionStep(
            step = "Do a thing",
            recordable = false,
            recording = null,
          ),
          DirectionStep(
            step = "Do another thing",
            recordable = true,
            recording = ToolRecording(
              listOf(
                TrailblazeToolYamlWrapper(
                  name = "inputText",
                  trailblazeTool = InputTextTrailblazeTool(
                    text = "Hello",
                  ),
                ),
              ),
            ),
          ),
          VerificationStep(
            verify = "Check a thing",
            recordable = false,
            recording = null,
          ),
          VerificationStep(
            verify = "Check another thing",
            recordable = true,
            recording = ToolRecording(
              listOf(
                TrailblazeToolYamlWrapper(
                  name = "assertVisibleWithText",
                  trailblazeTool = AssertVisibleWithTextTrailblazeTool(
                    text = "Bingo",
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    )
    val expectedYaml = """
- prompts:
  - step: Do a thing
    recordable: false
  - step: Do another thing
    recording:
      tools:
      - inputText:
          text: Hello
  - verify: Check a thing
    recordable: false
  - verify: Check another thing
    recording:
      tools:
      - assertVisibleWithText:
          text: Bingo
    """.trimIndent()
    val actualYaml = trailblazeYaml.encodeToString(trailItems)
    assertThat(actualYaml).isEqualTo(expectedYaml)
  }
}
