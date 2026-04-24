package xyz.block.trailblaze.android.tools.androidworldbenchmarks

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.device.AdbJvmAndAndroidUtil.withAndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.device.DeviceContact
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Adds a contact to the device's contacts provider.
 *
 * Uses [AndroidDeviceCommandExecutor.addContact] which delegates to
 * ContentProviderOperation batch insert on Android, or `adb shell content insert`
 * commands on JVM. Useful for SMS and phone benchmarks that require contacts
 * to be pre-populated.
 */
@Serializable
@TrailblazeToolClass(
  name = "androidworldbenchmarks_addContactToDevice",
  isForLlm = false,
)
@LLMDescription("Adds a contact with the given name and phone number to the device.")
data class AndroidWorldBenchmarksAddContactToDeviceTrailblazeTool(
  @param:LLMDescription("The display name for the contact.")
  val name: String,
  @param:LLMDescription("The phone number for the contact.")
  val phoneNumber: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    return withAndroidDeviceCommandExecutor(toolExecutionContext) { executor ->
      try {
        executor.addContact(
          contact = DeviceContact(
            displayName = name,
            phoneNumber = phoneNumber,
          ),
        )
        TrailblazeToolResult.Success(
          message = "Added contact '$name' with phone number '$phoneNumber' to the device.",
        )
      } catch (e: Exception) {
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Failed to add contact '$name' ($phoneNumber): ${e.message}",
          command = this@AndroidWorldBenchmarksAddContactToDeviceTrailblazeTool,
          stackTrace = e.stackTraceToString(),
        )
      }
    }
  }
}
