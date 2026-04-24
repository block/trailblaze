package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.DeviceSmsMessage
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Simulates receiving an SMS by inserting into the SMS content provider.
 *
 * Uses [AndroidDeviceCommandExecutor.insertSmsIntoInbox] which inserts via the
 * Telephony SMS content provider on Android, or `adb shell content insert` on JVM.
 * Useful for benchmark setup where the agent needs to reply to or forward received
 * messages.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_sendSmsToDevice",
  isForLlm = false,
)
@LLMDescription("Simulates receiving an SMS message on the device by inserting into the SMS inbox.")
data class AndroidWorldBenchmarksSendSmsToDeviceTrailblazeTool(
  @param:LLMDescription("The sender's phone number.")
  val fromNumber: String,
  @param:LLMDescription("The SMS message body.")
  val message: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        executor.insertSmsIntoInbox(
          message = DeviceSmsMessage(
            fromNumber = fromNumber,
            body = message,
          ),
        )
        val preview = message.replace("\n", "\\n")
        TrailblazeToolResult.Success(
          message = "Inserted SMS from '$fromNumber' with ${message.length} characters into inbox. " +
            "Body preview: '$preview'",
        )
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to insert SMS from '$fromNumber': ${e.message}",
          command = this@AndroidWorldBenchmarksSendSmsToDeviceTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }
}
