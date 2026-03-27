package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface TrailblazeToolResult {

  /**
   * The tool executed successfully.
   * Optionally includes a [message] to relay back to the LLM as contextual feedback
   * (e.g., "Clicked on element. Page navigated to ...").
   */
  @Serializable
  data class Success(val message: String? = null) : TrailblazeToolResult

  @Serializable
  sealed interface Error : TrailblazeToolResult {
    val errorMessage: String

    @Serializable
    data class UnknownTool(
      val functionName: String,
      val functionArgs: JsonObject,
    ) : Error {
      override val errorMessage: String
        get() = "Unknown tool call provided: $functionName with args: $functionArgs"
    }

    @Serializable
    data object EmptyToolCall : Error {
      override val errorMessage: String
        get() = """
No tool call provided, this is an error.
Please always provide a tool call that will help complete the task.
        """.trimIndent()
    }

    @Serializable
    data class ExceptionThrown(
      override val errorMessage: String,
      val command: TrailblazeTool? = null,
      val stackTrace: String? = null,
    ) : Error {
      companion object {
        fun fromThrowable(
          throwable: Throwable,
          trailblazeTool: TrailblazeTool? = null,
        ): ExceptionThrown = ExceptionThrown(
          errorMessage = throwable.message ?: "Unknown error",
          stackTrace = throwable.stackTraceToString(),
          command = trailblazeTool,
        )
      }
    }

    @Serializable
    data class MaestroValidationError(
      override val errorMessage: String,
      val commandJsonObject: JsonObject,
    ) : Error

    @Serializable
    data class MissingRequiredArgs(
      val functionName: String,
      val functionArgs: JsonObject,
      val requiredArgs: List<String>,
    ) : Error {
      override val errorMessage: String
        get() = "Tool call $functionName is missing required args. Provided args: $functionArgs. Required args: $requiredArgs."
    }

    @Serializable
    data class UnknownTrailblazeTool(
      val command: TrailblazeTool,
    ) : Error {
      override val errorMessage: String
        get() = """
Unknown custom command, ensure there is a mapping between the custom command and Maestro commands!
        """.trimIndent()
    }

    @Serializable
    data class InvalidToolCall(
      override val errorMessage: String,
      val command: TrailblazeTool,
    ) : Error

    /**
     * A fatal, non-recoverable error that should immediately abort the test.
     * Unlike other errors (which are sent back to the LLM for potential retry),
     * this error terminates execution right away.
     *
     * Use for precondition failures like missing hardware, disconnected devices, etc.
     */
    @Serializable
    data class FatalError(
      override val errorMessage: String,
      val stackTraceString: String? = null,
    ) : Error
  }
}
