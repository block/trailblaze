package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem

class PromptSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml()

  @Test
  fun canDeserializePromptWithStepAndVerify() {
    val yaml = """
trail:
  - step: Do a thing
    recordable: false
  - step: Do another thing
    recording:
      android:
        - inputText:
            text: Hello
  - verify: Check a thing
    recordable: false
  - verify: Check another thing
    recording:
      android:
        - assertVisibleWithText:
            text: Bingo
    """.trimIndent()
    val trailItems =
      trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
    with(trailItems) {
      with(filterIsInstance<PromptsTrailItem>().single()) {
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
    assertThat(actualYaml).isEqualTo(expectedYaml + "\n")
  }

  /**
   * Regression test: When editing a step's text and saving, unrecognized tools
   * (OtherTrailblazeTool) should preserve their parameters during round-trip serialization.
   *
   * This test verifies that custom tools not on the classpath (like myApp_launchSignedIn)
   * retain their nested parameters (like email, password) when the YAML is decoded and re-encoded.
   */
  @Test
  fun unrecognizedToolRoundTripPreservesParameters() {
    // YAML with a custom tool that has nested parameters (not on classpath)
    val yaml = """
trail:
  - step: Launch app with credentials
    recording:
      android:
        - customLoginTool:
            email: user@example.com
            password: secretpassword
            nested:
              key1: value1
              key2: value2
    """.trimIndent()

    // Decode the YAML
    val trailItems =
      trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))

    // Verify the tool was parsed as OtherTrailblazeTool with correct parameters
    val step = trailItems.filterIsInstance<PromptsTrailItem>().single().promptSteps.single() as DirectionStep
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

    // Round-trip the recorded tool wrappers themselves and confirm the unrecognized tool's nested
    // parameters survive encode-then-decode (the v1 trail-document string round-trip is gone; the
    // tool object surviving the trip is the contract this test pins).
    val reDecoded = trailblazeYaml.decodeTools(trailblazeYaml.encodeTools(recording.tools))
    assertThat(reDecoded.size).isEqualTo(1)
    val reTool = reDecoded[0].trailblazeTool as OtherTrailblazeTool
    assertThat(reTool.raw.containsKey("email")).isEqualTo(true)
    assertThat(reTool.raw.containsKey("password")).isEqualTo(true)
    assertThat(reTool.raw.containsKey("nested")).isEqualTo(true)
  }

  @Test
  fun emptyToolRecordingIsLegalAndDenotesADeterministicNoOp() {
    // A `ToolRecording` with zero tools is a deliberate, hand-authored declaration — "this step
    // needs zero tools on this device" — distinct from `recording == null` ("not recorded, fall
    // through to AI"). See ToolRecording's 3-state doc.
    val recording = ToolRecording(tools = emptyList())
    assertThat(recording.tools).isEmpty()
  }

  @Test
  fun emptyRecordingYamlParsesToADeterministicNoOpStep() {
    // `recording: { tools: [] }` decodes to a non-null, zero-tool recording — a declared no-op —
    // rather than throwing or silently falling through to AI.
    val yaml = """
trail:
  - step: Nothing needed here
    recording:
      android: []
    """.trimIndent()
    val step = trailblazeYaml.decodeTrail(yaml, deviceClassifiers = listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single()
    assertThat(step.recording).isNotNull()
    assertThat(step.recording!!.tools).isEmpty()
  }
}
