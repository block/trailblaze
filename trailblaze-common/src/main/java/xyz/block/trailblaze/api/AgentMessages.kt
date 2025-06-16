package xyz.block.trailblaze.api

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success

object AgentMessages {

  fun TrailblazeToolResult.toContentString(toolName: String, toolArgs: JsonObject): String = when (this) {
    is Success -> successContentString(toolName, toolArgs)
    is Error.MaestroValidationError -> validationErrorContentString(toolName, toolArgs, errorMessage)
    is Error.UnknownTrailblazeTool -> unknownCommandErrorContentString(toolName, toolArgs, errorMessage)
    is Error.EmptyToolCall -> emptyToolCallErrorContentString()
    is Error.ExceptionThrown -> errorExceptionContentString(this)
    is Error.UnknownTool -> unknownToolErrorContentString(functionName, functionArgs, errorMessage)
    is Error.MissingRequiredArgs -> missingRequiredArgsContentString(functionName, functionArgs, requiredArgs)
  }

  private fun errorExceptionContentString(errorException: Error.ExceptionThrown) = buildString {
    appendLine("# Error executing tool: $errorException")
    appendLine("Exception Message: ${errorException.errorMessage}")
    appendLine("Command: ${TrailblazeJsonInstance.encodeToString(errorException.command)}")
  }

  private fun successContentString(toolName: String, toolArgs: JsonObject) = buildString {
    appendLine("# Successfully performed the following action on the device:")
    appendLine("Tool: $toolName")
    appendLine("Parameters $toolArgs")
  }

  private fun validationErrorContentString(
    toolName: String,
    toolArgs: JsonObject,
    errorMessage: String,
  ) = buildString {
    appendLine(
      "# Failed to perform the following action on the device because of a verification error.",
    )
    appendLine("Tool: $toolName")
    appendLine("Parameters $toolArgs")
    appendLine("Error message: $errorMessage")
  }

  private fun unknownCommandErrorContentString(
    toolName: String,
    toolArgs: JsonObject,
    errorMessage: String,
  ) = buildString {
    appendLine("# Unknown command provided, please try a different tool.")
    appendLine("Tool: $toolName")
    appendLine("Parameters $toolArgs")
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

  private fun missingRequiredArgsContentString(
    functionName: String,
    functionArgs: JsonObject,
    requiredArguments: List<String>,
  ) = buildString {
    appendLine("# Tool attempted is missing required arguments.")
    appendLine("Tool: $functionName")
    appendLine("Parameters provided $functionArgs")
    appendLine("Parameters required $requiredArguments")
  }
}
