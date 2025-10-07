package xyz.block.trailblaze.api

import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

object AgentMessages {

  fun TrailblazeToolResult.toContentString(toolName: String, toolArgs: JsonObject): String = when (this) {
    is TrailblazeToolResult.Success -> successContentString(toolName, toolArgs)
    is TrailblazeToolResult.Error.MaestroValidationError -> validationErrorContentString(
      toolName,
      toolArgs,
      errorMessage,
    )

    is TrailblazeToolResult.Error.UnknownTrailblazeTool -> unknownCommandErrorContentString(
      toolName,
      toolArgs,
      errorMessage,
    )

    is TrailblazeToolResult.Error.EmptyToolCall -> emptyToolCallErrorContentString()
    is TrailblazeToolResult.Error.ExceptionThrown -> errorExceptionContentString(this)
    is TrailblazeToolResult.Error.UnknownTool -> unknownToolErrorContentString(functionName, functionArgs, errorMessage)
    is TrailblazeToolResult.Error.MissingRequiredArgs -> missingRequiredArgsContentString(
      functionName,
      functionArgs,
      requiredArgs,
    )
  }

  private fun errorExceptionContentString(errorException: TrailblazeToolResult.Error.ExceptionThrown) = buildString {
    appendLine("# Error executing tool: $errorException")
    appendLine("Exception Message: ${errorException.errorMessage}")
    appendLine("Command: ${errorException.command}")
  }

  private fun successContentString(toolName: String, toolArgs: JsonObject) = buildString {
    appendLine("**Successfully used the `$toolName` tool on the device with the following parameters:**")
    appendLine(asJsonCodeBlock(toolArgs))
  }

  private fun asJsonCodeBlock(jsonObject: JsonObject) = buildString {
    appendLine("```json")
    appendLine(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(jsonObject))
    appendLine("```")
  }

  private fun validationErrorContentString(
    toolName: String,
    toolArgs: JsonObject,
    errorMessage: String,
  ) = buildString {
    appendLine(
      "**Failed to perform the following `$toolName` action on the device because of a verification error.**",
    )
    appendLine("- Error message: $errorMessage")
    appendLine("- Parameters ${asJsonCodeBlock(toolArgs)}")
  }

  private fun unknownCommandErrorContentString(
    toolName: String,
    toolArgs: JsonObject,
    errorMessage: String,
  ) = buildString {
    appendLine("# Unknown tool `$toolName`:")
    appendLine("- Error message: $errorMessage")
    appendLine("- Parameters ${asJsonCodeBlock(toolArgs)}")
  }

  private fun emptyToolCallErrorContentString() = buildString {
    appendLine("# FAILURE: No tool call provided")
    appendLine("- Error message: ${TrailblazeToolResult.Error.EmptyToolCall.errorMessage}")
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
    appendLine("# ERROR: Tool attempted is missing required arguments.")
    appendLine("Tool: $functionName")
    appendLine("Parameters provided $functionArgs")
    appendLine("Parameters required $requiredArguments")
  }
}
