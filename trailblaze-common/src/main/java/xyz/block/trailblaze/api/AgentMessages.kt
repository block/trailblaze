package xyz.block.trailblaze.api

import com.aallam.openai.api.chat.FunctionCall
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success

object AgentMessages {

  fun TrailblazeToolResult.toContentString(function: FunctionCall): String = when (this) {
    is Success -> successContentString(function)
    is Error.MaestroValidationError -> validationErrorContentString(function, errorMessage)
    is Error.UnknownTrailblazeTool -> unknownCommandErrorContentString(function, errorMessage)
    is Error.EmptyToolCall -> emptyToolCallErrorContentString()
    is Error.ExceptionThrown -> errorExceptionContentString(this)
    is Error.UnknownTool -> unknownToolErrorContentString(functionName, functionArgs, errorMessage)
  }

  private fun errorExceptionContentString(errorException: Error.ExceptionThrown) = buildString {
    appendLine(
      "# Error executing a command of type ${errorException.command::class.java.simpleName}",
    )
    appendLine("Exception Message: ${errorException.errorMessage}")
    appendLine("Command: ${TrailblazeJsonInstance.encodeToString(errorException.command)}")
  }

  private fun successContentString(function: FunctionCall) = buildString {
    appendLine("# Successfully performed the following action on the device:")
    appendLine("Tool: ${function.name}")
    appendLine("Parameters ${function.argumentsAsJson()}")
  }

  private fun validationErrorContentString(
    function: FunctionCall,
    errorMessage: String,
  ) = buildString {
    appendLine(
      "# Failed to perform the following action on the device because of a verification error.",
    )
    appendLine("Tool: ${function.name}")
    appendLine("Parameters ${function.argumentsAsJson()}")
    appendLine("Error message: $errorMessage")
  }

  private fun unknownCommandErrorContentString(
    function: FunctionCall,
    errorMessage: String,
  ) = buildString {
    appendLine("# Unknown command provided, please try a different tool.")
    appendLine("Tool: ${function.name}")
    appendLine("Parameters ${function.argumentsAsJson()}")
    appendLine("Error message: $errorMessage")
  }

  private fun emptyToolCallErrorContentString() = buildString {
    appendLine("# FAILURE: No tool call provided")
    appendLine("Error message: ${Error.EmptyToolCall.errorMessage}")
  }

  private fun unknownToolErrorContentString(
    functionName: String,
    functionArgs: JsonObject,
    errorMessage: String,
  ) = buildString {
    appendLine("# Unregistered command provided, please try a different tool.")
    appendLine("Tool: $functionName")
    appendLine("Parameters $functionArgs")
    appendLine("Error message: $errorMessage")
  }
}
