package xyz.block.trailblaze.logs.client

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.serializer
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolJsonSerializer
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.reflect.KClass

object TrailblazeJson {

  /**
   * Wire-format key that kotlinx-serialization uses as the polymorphic class discriminator
   * (configured below at [Json.classDiscriminator]). Exposed so the legacy log-decode path
   * in [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeToolFlatSerializer] reads from
   * a single source of truth — changing one without the other would silently break decode
   * of pre-#2634 logs.
   */
  const val POLYMORPHIC_CLASS_DISCRIMINATOR = "class"

  fun createTrailblazeJsonInstance(
    allToolClasses: Map<ToolName, KClass<out TrailblazeTool>>,
  ): Json = createTrailblazeJsonInstance(allToolClasses, emptyMap(), {})

  fun createTrailblazeJsonInstance(
    allToolClasses: Map<ToolName, KClass<out TrailblazeTool>>,
    yamlDefinedSerializers: Map<ToolName, KSerializer<out TrailblazeTool>>,
  ): Json = createTrailblazeJsonInstance(allToolClasses, yamlDefinedSerializers, {})

  fun createTrailblazeJsonInstance(
    allToolClasses: Map<ToolName, KClass<out TrailblazeTool>>,
    yamlDefinedSerializers: Map<ToolName, KSerializer<out TrailblazeTool>>,
    serializerModuleModifier: (SerializersModuleBuilder) -> Unit,
  ): Json = Json {
    classDiscriminator = POLYMORPHIC_CLASS_DISCRIMINATOR // Key to determine subclass
    ignoreUnknownKeys = true // Avoids errors on unknown fields
    isLenient = true // Allows unquoted strings & other relaxed parsing
    prettyPrint = true
    allowStructuredMapKeys = true
    @OptIn(InternalSerializationApi::class)
    serializersModule = SerializersModule {
      polymorphicDefaultSerializer(TrailblazeLog::class) { value ->
        value::class.serializer() as? KSerializer<TrailblazeLog>
      }
      polymorphicDefaultSerializer(TrailblazeToolResult::class) { value ->
        value::class.serializer() as? KSerializer<TrailblazeToolResult>
      }
      polymorphicDefaultSerializer(AgentTaskStatus::class) { value ->
        value::class.serializer() as? KSerializer<AgentTaskStatus>
      }
      polymorphicDefaultSerializer(SessionStatus::class) { value ->
        value::class.serializer() as? KSerializer<SessionStatus>
      }

      // Executor-boundary call sites (planner, recordings, RPC bridges) encode/decode a
      // TrailblazeTool through `TrailblazeToolRepo.toolCallToTrailblazeTool` (decode) or the
      // tool's concrete class serializer (encode) — the abstract type is no longer
      // polymorphically wired through the Json module for that path.
      //
      // For everywhere else — `@Serializable` data classes that embed a `@Contextual
      // TrailblazeTool` field (ExecuteToolsRequest.tools, TrailblazeToolResult.Error.command,
      // TrailblazeToolYamlWrapper.trailblazeTool, …) — register a contextual serializer that
      // encodes the tool through [OtherTrailblazeTool]'s flat `{toolName, raw}` shape and
      // decodes back as an `OtherTrailblazeTool`. Receivers that need a concrete executable
      // resolve through the repo at dispatch time.
      contextual(TrailblazeTool::class, TrailblazeToolJsonSerializer)

      serializerModuleModifier(this)
    }
  }

  var defaultWithoutToolsInstance: Json = createTrailblazeJsonInstance(emptyMap())
}
