package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface TrailblazeToolResult {

  /**
   * The tool executed successfully.
   *
   * - [message] is the tool's primary string output, relayed back to the LLM as contextual
   *   feedback (e.g., "Clicked on element. Page navigated to ..."). Null means the tool ran
   *   without producing a user-readable message.
   * - [structuredContent] carries the tool's structured return value when the producer
   *   populated one. Today this is set by MCP scripted tools (subprocess or on-device QuickJS
   *   bundle) whose handler returns a non-string typed result, threaded onto the
   *   `JsScriptingCallbackResult.CallToolResult.structuredContent` wire field so a scripted
   *   caller (`client.tools.<name>(args)`) can unwrap it into the typed `result` declared in
   *   the SDK's `TrailblazeToolMap`. Null = "no structured payload" — the caller falls back to
   *   [message] for the legacy text-only wire shape.
   */
  @Serializable
  data class Success(
    val message: String? = null,
    val structuredContent: JsonElement? = null,
  ) : TrailblazeToolResult

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
      @Contextual val command: TrailblazeTool? = null,
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
      @Contextual val command: TrailblazeTool,
    ) : Error {
      override val errorMessage: String
        get() = """
Unknown custom command, ensure there is a mapping between the custom command and Maestro commands!
        """.trimIndent()
    }

    @Serializable
    data class InvalidToolCall(
      override val errorMessage: String,
      @Contextual val command: TrailblazeTool,
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
