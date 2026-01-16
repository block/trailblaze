package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Test
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem

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

  /**
   * Regression test: When editing a step's text and saving, unrecognized tools
   * (OtherTrailblazeTool) should preserve their parameters during round-trip serialization.
   *
   * This test verifies that custom tools not on the classpath (like launchSquareAppSignedIn)
   * retain their nested parameters (like email, password) when the YAML is decoded and re-encoded.
   */
  @Test
  fun unrecognizedToolRoundTripPreservesParameters() {
    // YAML with a custom tool that has nested parameters (not on classpath)
    val yaml = """
- prompts:
  - step: Launch app with credentials
    recording:
      tools:
      - customLoginTool:
          email: user@example.com
          password: secretpassword
          nested:
            key1: value1
            key2: value2
    """.trimIndent()

    // Decode the YAML
    val trailItems = trailblazeYaml.decodeTrail(yaml)

    // Verify the tool was parsed as OtherTrailblazeTool with correct parameters
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as PromptsTrailItem) {
        assertThat(promptSteps.size).isEqualTo(1)
        val step = promptSteps[0] as DirectionStep
        assertThat(step.step).isEqualTo("Launch app with credentials")
        val recording = step.recording!!
        assertThat(recording.tools.size).isEqualTo(1)
        val tool = recording.tools[0]
        assertThat(tool.name).isEqualTo("customLoginTool")
        assertThat(tool.trailblazeTool).isInstanceOf(OtherTrailblazeTool::class)
        val otherTool = tool.trailblazeTool as OtherTrailblazeTool
        // Verify the raw JSON contains the expected parameters
        assertThat(otherTool.raw.containsKey("email")).isEqualTo(true)
        assertThat(otherTool.raw.containsKey("password")).isEqualTo(true)
        assertThat(otherTool.raw.containsKey("nested")).isEqualTo(true)
      }
    }

    // Re-encode to YAML
    val reEncodedYaml = trailblazeYaml.encodeToString(trailItems)

    // Verify round-trip preserves the parameters
    assertThat(reEncodedYaml).isEqualTo(yaml)
  }
}
