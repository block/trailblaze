package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * DEPRECATED back-compat alias for [MaestroTrailblazeTool] (now registered as `mobile_maestro`).
 *
 * The raw-Maestro escape hatch was renamed `maestro` → `mobile_maestro` (it's the mobile-framework
 * surface; web is Playwright-native). To avoid breaking trails authored before the rename — both
 * internal recordings and any external/OSS `maestro:` trails — this shim keeps the legacy `maestro:`
 * tool name resolvable. It holds the identical `{commands: [...]}` payload and simply forwards
 * execution to the canonical [MaestroTrailblazeTool], so behavior is byte-for-byte the same.
 *
 * Hidden from the LLM (`surfaceToLlm = false`) and the scripted-tool surface
 * (`surfaceToScriptedTools = false`), and non-recordable (`isRecordable = false`) — nothing new
 * should be authored against `maestro:`; the recorder always emits `mobile_maestro`. Delete this
 * class once no `maestro:` trails remain in the wild.
 */
@Serializable(with = MaestroDeprecatedTrailblazeToolSerializer::class)
@TrailblazeToolClass(
  name = "maestro",
  surfaceToLlm = false,
  surfaceToScriptedTools = false,
  isRecordable = false,
)
@LLMDescription(
  "DEPRECATED alias for `mobile_maestro`. Legacy `maestro:` trails still run by delegating to " +
    "`mobile_maestro`; author new trails against `mobile_maestro`.",
)
data class MaestroDeprecatedTrailblazeTool(
  /** Full Maestro commands-list YAML — same payload as [MaestroTrailblazeTool.yaml]. */
  val yaml: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log(
      "[deprecation] The `maestro` tool name is deprecated and will be removed — use " +
        "`mobile_maestro`. Delegating this call to `mobile_maestro`.",
    )
    return MaestroTrailblazeTool(yaml).execute(toolExecutionContext)
  }
}

/**
 * (De)serializes [MaestroDeprecatedTrailblazeTool] with the exact same `{commands: [...]}` wire
 * shape as [MaestroTrailblazeTool], by delegating to [MaestroTrailblazeToolSerializer]. This keeps
 * the legacy `maestro:` YAML/JSON format identical to `mobile_maestro:` while the tool just forwards.
 */
object MaestroDeprecatedTrailblazeToolSerializer : KSerializer<MaestroDeprecatedTrailblazeTool> {
  override val descriptor: SerialDescriptor = MaestroTrailblazeToolSerializer.descriptor

  override fun serialize(encoder: Encoder, value: MaestroDeprecatedTrailblazeTool) {
    MaestroTrailblazeToolSerializer.serialize(encoder, MaestroTrailblazeTool(value.yaml))
  }

  override fun deserialize(decoder: Decoder): MaestroDeprecatedTrailblazeTool =
    MaestroDeprecatedTrailblazeTool(MaestroTrailblazeToolSerializer.deserialize(decoder).yaml)
}
