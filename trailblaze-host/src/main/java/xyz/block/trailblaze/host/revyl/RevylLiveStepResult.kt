package xyz.block.trailblaze.host.revyl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.util.Console

/**
 * Structured result from a Revyl CLI live-step command (`revyl device instruction`
 * or `revyl device validation`).
 *
 * Maps to the `LiveStepResponse` struct returned by the Go CLI's `--json` output.
 * The [stepOutput] object carries agent-specific detail such as `status`,
 * `status_reason`, and `validation_result`.
 *
 * @property success Whether the step completed successfully.
 * @property stepType The step type that was executed ("instruction" or "validation").
 * @property stepId Unique identifier assigned to the step by the worker.
 * @property stepOutput Agent output detail including status reason and validation result.
 */
@Serializable
data class RevylLiveStepResult(
  val success: Boolean = false,
  @SerialName("step_type") val stepType: String = "",
  @SerialName("step_id") val stepId: String = "",
  @SerialName("step_output") val stepOutput: JsonObject? = null,
) {

  /**
   * Human-readable reason for the step outcome, extracted from [stepOutput].
   * Returns null when the field is absent or the output is unparseable.
   */
  val statusReason: String?
    get() = stepOutput?.get("status_reason")?.jsonPrimitive?.contentOrNull

  companion object {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    /**
     * Parses CLI JSON stdout into a [RevylLiveStepResult].
     *
     * Falls back to a failure result if parsing fails so callers always
     * get a non-null object to inspect.
     *
     * @param jsonString Raw JSON from `revyl device instruction/validation --json`.
     * @return Parsed result with success flag and step output.
     */
    fun fromJson(jsonString: String): RevylLiveStepResult {
      return try {
        lenientJson.decodeFromString<RevylLiveStepResult>(jsonString.trim())
      } catch (e: Exception) {
        Console.log("RevylLiveStepResult: JSON parse failed: ${e.message}")
        RevylLiveStepResult(success = false)
      }
    }
  }
}
