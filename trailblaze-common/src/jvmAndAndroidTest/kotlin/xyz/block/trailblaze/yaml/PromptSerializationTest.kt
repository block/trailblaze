package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem

class PromptSerializationTest {
  private val trailblazeYaml = createTrailblazeYaml()

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
    assertThat(reEncodedYaml).isEqualTo(yaml + "\n")
  }

  @Test
  fun autoSatisfiedRecordingDeserializes() {
    // A step the recording author observed as already-complete: empty tools, autoSatisfied: true.
    // Replay skips this step deterministically instead of falling through to AI.
    val yaml = """
- prompts:
  - step: Confirm closing the dialog
    recording:
      tools: []
      autoSatisfied: true
    """.trimIndent()

    val trailItems = trailblazeYaml.decodeTrail(yaml)
    with(trailItems) {
      assertThat(size).isEqualTo(1)
      with(get(0) as PromptsTrailItem) {
        assertThat(promptSteps.size).isEqualTo(1)
        val step = promptSteps[0] as DirectionStep
        assertThat(step.recording).isEqualTo(
          ToolRecording(tools = emptyList(), autoSatisfied = true),
        )
      }
    }
  }

  @Test
  fun autoSatisfiedRecordingRoundTrips() {
    // Encode → decode preserves the auto-satisfied flag exactly.
    val original = listOf(
      PromptsTrailItem(
        listOf(
          DirectionStep(
            step = "Confirm closing the dialog",
            recording = ToolRecording(tools = emptyList(), autoSatisfied = true),
          ),
        ),
      ),
    )
    val encoded = trailblazeYaml.encodeToString(original)
    val decoded = trailblazeYaml.decodeTrail(encoded)
    assertThat(decoded).isEqualTo(original)
  }

  @Test
  fun emptyRecordingWithoutAutoSatisfiedThrowsAtConstruction() {
    // Bot review caught this regression: previously, `recording: {}` (no tools, no flag) would
    // fail to parse because `tools` was required. After the schema change, the require() block
    // ensures hand-edited or partial recordings still fail fast — a no-op recorded step is only
    // valid when the author explicitly marked intent with autoSatisfied=true.
    val ex = assertThrows(IllegalArgumentException::class.java) {
      ToolRecording(tools = emptyList(), autoSatisfied = false)
    }
    assertThat(ex.message!!).contains("autoSatisfied")
  }

  @Test
  fun emptyRecordingYamlWithoutAutoSatisfiedFailsToParse() {
    // Round-trip the regression: malformed YAML must throw, not silently produce a no-op step.
    val malformed = """
- prompts:
  - step: Should not parse
    recording:
      tools: []
    """.trimIndent()
    val ex = assertThrows(Exception::class.java) {
      trailblazeYaml.decodeTrail(malformed)
    }
    // The cause chain should mention the constructor invariant; we don't assert exact message
    // because kaml wraps in YamlException — just ensure the parse fails.
    assertThat(ex.message != null || ex.cause != null).isEqualTo(true)
  }

  @Test
  fun autoSatisfiedFalseIsOmittedFromYamlForBackwardsCompat() {
    // encodeDefaults=false in the YAML config; existing trails without autoSatisfied keep
    // the same on-disk shape. Only auto-satisfied recordings carry the explicit field.
    val items = listOf(
      PromptsTrailItem(
        listOf(
          DirectionStep(
            step = "Tap login",
            recording = ToolRecording(
              tools = listOf(
                TrailblazeToolYamlWrapper(
                  name = "inputText",
                  trailblazeTool = InputTextTrailblazeTool(text = "hello"),
                ),
              ),
              autoSatisfied = false,
            ),
          ),
        ),
      ),
    )
    val encoded = trailblazeYaml.encodeToString(items)
    // The default-false flag must not appear in YAML — round-trip clean against legacy fixtures.
    assertThat(encoded.contains("autoSatisfied")).isEqualTo(false)
  }
}
