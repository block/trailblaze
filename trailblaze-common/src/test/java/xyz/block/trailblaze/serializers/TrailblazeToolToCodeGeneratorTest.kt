package xyz.block.trailblaze.serializers

import kotlinx.serialization.Serializable
import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveCompleteTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import kotlin.test.assertEquals

class TrailblazeToolToCodeGeneratorTest {

  @Serializable
  data class CodeGenCustomTrailblazeTool(
    val text: String,
    val number: Int,
  ) : TrailblazeTool

  @Serializable
  class NoParamsTrailblazeTool : TrailblazeTool

  @Test
  fun testTrailblazeToolNoParams() {
    val generatedCode =
      TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(NoParamsTrailblazeTool())
    println(generatedCode)

    assertEquals(
      """
NoParamsTrailblazeTool()
      """.trimIndent(),
      generatedCode,
    )
  }

  @Test
  fun testTrailblazeTool() {
    val generatedCode =
      TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(EraseTextTrailblazeTool(8))
    println(generatedCode)

    assertEquals(
      """
EraseTextTrailblazeTool(
  charactersToErase = 8,
)
      """.trimIndent(),
      generatedCode,
    )
  }

  @Test
  fun testTrailblazeObjectiveCompleteCommand() {
    val generatedCode = TrailblazeToolToCodeSerializer().serializeTrailblazeToolToCode(
      ObjectiveCompleteTrailblazeTool(
        description = "Test objective",
        explanation = "explanation",
        status = "completed",
      ),
    )
    println(generatedCode)

    assertEquals(
      """
ObjectiveCompleteTrailblazeTool(
  description = "Test objective",
  explanation = "explanation",
  status = "completed",
)
      """.trimIndent(),
      generatedCode,
    )
  }

  @Test
  fun test() {
    val generatedCode = TrailblazeToolToCodeSerializer().serializeToCode(
      mapOf(
        "Prompt 1" to listOf(
          NoParamsTrailblazeTool(),
          EraseTextTrailblazeTool(8),
          CodeGenCustomTrailblazeTool("Hello", 1),
        ),
        "Prompt 2" to listOf<TrailblazeTool>(
          SwipeTrailblazeTool(direction = "DOWN"),
          SwipeTrailblazeTool(direction = "UP"),
        ),
      ),
    )
    println(generatedCode)
    assertEquals(
      expected = """
import xyz.block.trailblaze.serializers.TrailblazeToolToCodeGeneratorTest.CodeGenCustomTrailblazeTool
import xyz.block.trailblaze.serializers.TrailblazeToolToCodeGeneratorTest.NoParamsTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.EraseTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool

run(""${'"'}Prompt 1""${'"'}) {
  listOf(
    NoParamsTrailblazeTool(),
    EraseTextTrailblazeTool(
      charactersToErase = 8,
    ),
    CodeGenCustomTrailblazeTool(
      text = "Hello",
      number = 1,
    ),
  )
}

run(""${'"'}Prompt 2""${'"'}) {
  listOf(
    SwipeTrailblazeTool(
      direction = "DOWN",
    ),
    SwipeTrailblazeTool(
      direction = "UP",
    ),
  )
}
      """.trimIndent().trim(),

      actual = generatedCode.trim(),
    )
  }
}
