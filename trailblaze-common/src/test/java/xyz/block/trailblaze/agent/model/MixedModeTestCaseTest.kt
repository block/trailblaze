package xyz.block.trailblaze.agent.model

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import org.junit.Test
import xyz.block.trailblaze.agent.model.TestObjective.AssertEqualsCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertNotEqualsCommand
import xyz.block.trailblaze.agent.model.TestObjective.AssertWithAiCommand
import xyz.block.trailblaze.agent.model.TestObjective.MaestroCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberNumberCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberTextCommand
import xyz.block.trailblaze.agent.model.TestObjective.RememberWithAiCommand
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazeCommand
import xyz.block.trailblaze.agent.model.TestObjective.TrailblazeObjective.TrailblazePrompt
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnElementWithTextTrailblazeTool

class MixedModeTestCaseTest {

  @Test
  fun emptyYamlConfigThrows() {
    assertThat(
      runCatching {
        MixedModeTestCase(yamlContent = "")
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun trailblazeCommand() {
    val yaml = """
- run:
  - click "Use Email"
  - enter the email "jack@block.xyz"
  - tap on "Next"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    val objectives = testCase.objectives
    assertThat(objectives.size).isEqualTo(1)
    val trailblazePrompt = objectives[0] as TrailblazePrompt
    val expectedPrompt = "click \"Use Email\"\nenter the email \"jack@block.xyz\"\ntap on \"Next\""
    assertThat(trailblazePrompt.fullPrompt).isEqualTo(expectedPrompt)
    assertThat(trailblazePrompt.steps.size).isEqualTo(3)
    with(trailblazePrompt.steps[0]) {
      assertThat(description).isEqualTo("click \"Use Email\"")
      assertThat(taskIndex).isEqualTo(0)
    }
    with(trailblazePrompt.steps[1]) {
      assertThat(description).isEqualTo("enter the email \"jack@block.xyz\"")
      assertThat(taskIndex).isEqualTo(1)
    }
    with(trailblazePrompt.steps[2]) {
      assertThat(description).isEqualTo("tap on \"Next\"")
      assertThat(taskIndex).isEqualTo(2)
    }
  }

  @Test
  fun trailblazeCommandInLargerYaml() {
    val yaml = """
- run:
    - tap favorites
    - tap pizza

- assertVisible: "Pizza"
- assertNotVisible: "Search.*"
- assertVisible: "1 item"

- run:
    - tap library
    - click on search all items
    - search for pizza
    - tap on the pizza image

- assertVisible: "2 items"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    val objectives = testCase.objectives
    assertThat(objectives.size).isEqualTo(6)
    // Verify first objective is an AI prompt
    val trailblazePrompt = objectives[0] as TrailblazePrompt
    val expectedPrompt = "tap favorites\ntap pizza"
    assertThat(trailblazePrompt.fullPrompt).isEqualTo(expectedPrompt)
    assertThat(trailblazePrompt.steps.size).isEqualTo(2)
    with(trailblazePrompt.steps[0]) {
      assertThat(description).isEqualTo("tap favorites")
      assertThat(taskIndex).isEqualTo(0)
      assertThat(fullPrompt).isEqualTo(expectedPrompt)
    }
    with(trailblazePrompt.steps[1]) {
      assertThat(description).isEqualTo("tap pizza")
      assertThat(taskIndex).isEqualTo(1)
      assertThat(fullPrompt).isEqualTo(expectedPrompt)
    }
    // Verify the next three objectives should be assertions
    with(objectives[1] as MaestroCommand) {
      assertThat(maestroCommandWithVars).isEqualTo("- assertVisible: Pizza")
    }
    with(objectives[2] as MaestroCommand) {
      assertThat(maestroCommandWithVars).isEqualTo("- assertNotVisible: Search.*")
    }
    with(objectives[3] as MaestroCommand) {
      assertThat(maestroCommandWithVars).isEqualTo("- assertVisible: 1 item")
    }
    // Verify fifth objective is an AI prompt
    val otherTrailblazePrompt = objectives[4] as TrailblazePrompt
    val otherExpectedPrompt = "tap library\nclick on search all items\nsearch for pizza\ntap on the pizza image"
    assertThat(otherTrailblazePrompt.fullPrompt).isEqualTo(otherExpectedPrompt)
    assertThat(otherTrailblazePrompt.steps.size).isEqualTo(4)
    with(otherTrailblazePrompt.steps[0]) {
      assertThat(description).isEqualTo("tap library")
      assertThat(taskIndex).isEqualTo(0)
      assertThat(fullPrompt).isEqualTo(otherExpectedPrompt)
    }
    with(otherTrailblazePrompt.steps[1]) {
      assertThat(description).isEqualTo("click on search all items")
      assertThat(taskIndex).isEqualTo(1)
      assertThat(fullPrompt).isEqualTo(otherExpectedPrompt)
    }
    with(otherTrailblazePrompt.steps[2]) {
      assertThat(description).isEqualTo("search for pizza")
      assertThat(taskIndex).isEqualTo(2)
      assertThat(fullPrompt).isEqualTo(otherExpectedPrompt)
    }
    with(otherTrailblazePrompt.steps[3]) {
      assertThat(description).isEqualTo("tap on the pizza image")
      assertThat(taskIndex).isEqualTo(3)
      assertThat(fullPrompt).isEqualTo(otherExpectedPrompt)
    }
    // validate last objective is a maestro command
    with(objectives[5] as MaestroCommand) {
      assertThat(maestroCommandWithVars).isEqualTo("- assertVisible: 2 items")
    }
  }

  @Test
  fun trailblazeCommandWithSteps() {
    val yaml = """
- run:
    - click "Use Email":
        - tapOnElementWithText:
            text: "Use Email"
    - enter the email "jack@block.xyz":
        - inputText:
            text: "jack@block.xyz"
    - tap on "Next":
        - tapOnElementWithText:
            text: "Next"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    val objectives = testCase.objectives
    assertThat(objectives.size).isEqualTo(1)
    val trailblazePrompt = objectives[0] as TrailblazeCommand
    assertThat(trailblazePrompt.tools.size).isEqualTo(3)
    with(trailblazePrompt.tools[0]) {
      assertThat(tools.size).isEqualTo(1)
      with(tools[0]) {
        val tool = this as TapOnElementWithTextTrailblazeTool
        assertThat(tool.text).isEqualTo("Use Email")
      }
    }
    with(trailblazePrompt.tools[1]) {
      assertThat(tools.size).isEqualTo(1)
      with(tools[0]) {
        val tool = this as InputTextTrailblazeTool
        assertThat(tool.text).isEqualTo("jack@block.xyz")
      }
    }
    with(trailblazePrompt.tools[2]) {
      assertThat(tools.size).isEqualTo(1)
      with(tools[0]) {
        val tool = this as TapOnElementWithTextTrailblazeTool
        assertThat(tool.text).isEqualTo("Next")
      }
    }
  }

  @Test
  fun trailblazePrompt() {
    val yaml = "- try out a regular prompt here?"
    val testCase = MixedModeTestCase(yaml)
    val objectives = testCase.objectives
    assertThat(objectives.size).isEqualTo(1)
    val trailblazePrompt = objectives[0] as TrailblazePrompt
    val expectedPrompt = "try out a regular prompt here?"
    assertThat(trailblazePrompt.fullPrompt).isEqualTo(expectedPrompt)
    assertThat(trailblazePrompt.steps.size).isEqualTo(1)
    with(trailblazePrompt.steps[0]) {
      assertThat(description).isEqualTo("try out a regular prompt here?")
      assertThat(taskIndex).isEqualTo(0)
    }
  }

  @Test
  fun rememberTextCommand() {
    val yaml = """
- rememberText:
    prompt: "Charge button at the bottom of the screen"
    variable: "chargeText"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          RememberTextCommand(
            promptWithVars = "Charge button at the bottom of the screen",
            variableName = "chargeText",
          ),
        ),
      )
  }

  @Test
  fun rememberTextCommandNoPrompt() {
    val yaml = """
- rememberText:
    variable: "chargeText"
    """.trimIndent()
    assertThat(
      runCatching { MixedModeTestCase(yaml) },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun rememberTextCommandNoVariable() {
    val yaml = """
- rememberText:
    prompt: "Charge button at the bottom of the screen"
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun rememberNumberCommand() {
    val yaml = """
- rememberNumber:
    prompt: "Charge button at the bottom of the screen"
    variable: "chargeAmount"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          RememberNumberCommand(
            promptWithVars = "Charge button at the bottom of the screen",
            variableName = "chargeAmount",
          ),
        ),
      )
  }

  @Test
  fun rememberNumberCommandNoPrompt() {
    val yaml = """
- rememberNumber:
    variable: "chargeAmount"
    """.trimIndent()
    assertThat(
      runCatching { MixedModeTestCase(yaml) },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun rememberNumberCommandNoVariable() {
    val yaml = """
- rememberNumber:
    prompt: "Charge button at the bottom of the screen"
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun rememberWithAiCommand() {
    val yaml = """
- rememberWithAI:
    prompt: "What is the current charge amount shown on the Charge button? Respond with ONLY the numeric value."
    variable: "aiChargeAmount"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          RememberWithAiCommand(
            promptWithVars = "What is the current charge amount shown on the Charge button? Respond with ONLY the numeric value.",
            variableName = "aiChargeAmount",
          ),
        ),
      )
  }

  @Test
  fun rememberWithAiCommandNoPrompt() {
    val yaml = """
- rememberWithAI:
    variable: "aiChargeAmount"
    """.trimIndent()
    assertThat(
      runCatching { MixedModeTestCase(yaml) },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun rememberWithAiCommandNoVariable() {
    val yaml = """
- rememberWithAI:
    prompt: "What is the current charge amount shown on the Charge button? Respond with ONLY the numeric value."
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertEqualsCommand() {
    val yaml = """
- assertEquals:
    actual: "{{chargeText}}"
    expected: "Charge ${'$'}5.00"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          AssertEqualsCommand(
            actual = "{{chargeText}}",
            expected = "Charge $5.00",
          ),
        ),
      )
  }

  @Test
  fun assertEqualsCommandNoExpected() {
    val yaml = """
- assertEquals:
    actual: "{{chargeText}}"
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertEqualsCommandNoActual() {
    val yaml = """
- assertEquals:
    expected: "Charge ${'$'}5.00"
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertNotEqualsCommand() {
    val yaml = """
- assertNotEquals:
    actual: "{{chargeText}}"
    expected: "Something totally different"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          AssertNotEqualsCommand(
            actual = "{{chargeText}}",
            expected = "Something totally different",
          ),
        ),
      )
  }

  @Test
  fun assertNotEqualsCommandNoExpected() {
    val yaml = """
- assertNotEquals:
    actual: "{{chargeText}}"
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertNotEqualsCommandNoActual() {
    val yaml = """
- assertNotEquals:
    expected: "Something totally different"
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertWithAiCommand() {
    val yaml = """
- assertWithAI:
    prompt: "Is there a Charge button showing ${'$'}0.00 at the bottom of the screen?"
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          AssertWithAiCommand(
            promptWithVars = "Is there a Charge button showing $0.00 at the bottom of the screen?",
          ),
        ),
      )
  }

  @Test
  fun assertWithAiCommandNoPrompt() {
    val yaml = """
- assertWithAI:
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertMathCommand() {
    val yaml = """
- assertMath:
    expression: "{{chargeAmount}} + 5"
    expected: 5
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          TestObjective.AssertMathCommand(
            expression = "{{chargeAmount}} + 5",
            expected = "5",
          ),
        ),
      )
  }

  @Test
  fun assertMathCommandNoExpression() {
    val yaml = """
- assertMath:
    expected: 5
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun assertMathCommandNoExpected() {
    val yaml = """
- assertMath:
    expected: 5
    """.trimIndent()
    assertThat(
      runCatching {
        MixedModeTestCase(yaml)
      },
    ).isFailure()
      .isInstanceOf(TrailblazeException::class)
  }

  @Test
  fun maestroCommand() {
    val yaml = """
- assertVisible: Charge .*   
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          MaestroCommand("- assertVisible: Charge .*"),
        ),
      )
  }

  @Test
  fun noParamMaestroCommand() {
    val yaml = """
- scroll  
    """.trimIndent()
    val testCase = MixedModeTestCase(yaml)
    assertThat(testCase.objectives)
      .isEqualTo(
        listOf(
          MaestroCommand("- scroll"),
        ),
      )
  }
}
