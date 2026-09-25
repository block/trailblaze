package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.prop
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import kotlin.test.AfterTest
import kotlin.test.Test

class SubprocessToolRegistrationTest {

  private val emptySchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList())

  private val schemaWithEmail = ToolSchema(
    properties = buildJsonObject {
      putJsonObject("email") { put("type", "string"); put("description", "email") }
    },
    required = listOf("email"),
  )

  private fun stubProvider(): () -> McpSubprocessSession = {
    error("session not needed during descriptor construction")
  }

  @AfterTest fun clearCallbackRegistry() {
    JsScriptingInvocationRegistry.clearForTest()
  }

  @Test fun `trailblazeDescriptor mirrors the advertised name description and schema`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("myapp_login"),
      description = "Log in",
      inputSchema = schemaWithEmail,
      meta = TrailblazeToolMeta(),
    )
    val registration = SubprocessToolRegistration(registered, stubProvider())

    assertThat(registration.name).isEqualTo(ToolName("myapp_login"))
    assertThat(registration.trailblazeDescriptor.name).isEqualTo("myapp_login")
    assertThat(registration.trailblazeDescriptor.description).isEqualTo("Log in")
    assertThat(registration.trailblazeDescriptor.requiredParameters.single().name).isEqualTo("email")
  }

  @Test fun `decodeToolCall returns a SubprocessTrailblazeTool bound to this source`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("myapp_login"),
      description = null,
      inputSchema = emptySchema,
      meta = TrailblazeToolMeta(),
    )
    val registration = SubprocessToolRegistration(registered, stubProvider())

    val decoded = registration.decodeToolCall("""{"email":"a@b.c"}""")
    assertThat(decoded).isInstanceOf(SubprocessTrailblazeTool::class)
      .prop(SubprocessTrailblazeTool::advertisedName).isEqualTo(ToolName("myapp_login"))
  }

  @Test fun `buildKoogTool wires the serializer and descriptor`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("tick"),
      description = "tick the clock",
      inputSchema = emptySchema,
      meta = TrailblazeToolMeta(),
    )
    val registration = SubprocessToolRegistration(registered, stubProvider())
    val koogTool = registration.buildKoogTool { error("context not needed to build") }

    assertThat(koogTool.descriptor.name).isEqualTo("tick")
    assertThat(koogTool.descriptor.description).isEqualTo("tick the clock")
  }

  @Test fun `hidden MCP tool remains dispatchable but is not surfaced to the LLM`() {
    val registered = RegisteredSubprocessTool(
      advertisedName = ToolName("hidden_cleanup"),
      description = "Internal cleanup",
      inputSchema = emptySchema,
      meta = TrailblazeToolMeta(surfaceToLlm = false),
    )

    assertThat(SubprocessToolRegistration(registered, stubProvider()).surfaceToLlm).isEqualTo(false)
  }

  @Test fun `session finalizer gets a fresh live callback invocation after tool return`() {
    val sessionId = SessionId("finalizer-callback-test")
    val context = SubprocessToolRegistration.JsScriptingCallbackContext(
      baseUrl = "http://localhost:52525",
      toolRepo = TrailblazeToolRepo(
        DynamicTrailblazeToolSet(name = "finalizer-callback-test", toolClasses = emptySet()),
      ),
    )
    context.recordExecutionContext(TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId("unit", TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = 1080,
        heightPixels = 2400,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    ))

    val finalizer = requireNotNull(context.openFinalizerInvocation(sessionId))

    assertThat(JsScriptingInvocationRegistry.lookup(finalizer.handle.invocationId)).isNotNull()
    finalizer.handle.close()
    assertThat(JsScriptingInvocationRegistry.lookup(finalizer.handle.invocationId)).isNull()
    context.clearExecutionContext()
    assertThat(context.openFinalizerInvocation(sessionId)).isNull()
  }
}
