package xyz.block.trailblaze.scripting.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.doesNotContain
import assertk.assertions.isNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackAction
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatchDepth
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackRequest
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackResult
import xyz.block.trailblaze.scripting.callback.JsScriptingInvocationRegistry
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

@Serializable
internal data class HostTestGreeting(val greeting: String, val length: Int)

@Serializable
@TrailblazeHostFunctionClass(name = "test_greet", result = HostTestGreeting::class)
internal data class HostTestGreet(val name: String, val shout: Boolean = false) : TrailblazeHostFunction<HostTestGreeting> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext): HostTestGreeting {
    val greeting = "hello $name".let { if (shout) it.uppercase() else it }
    return HostTestGreeting(greeting, greeting.length)
  }
}

@Serializable
@TrailblazeHostFunctionClass(name = "test_refuse", result = HostTestGreeting::class)
internal data class HostTestRefuse(val reason: String) : TrailblazeHostFunction<HostTestGreeting> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext): HostTestGreeting =
    throw TrailblazeHostFunctionException("refused: $reason")
}

@Serializable
@TrailblazeHostFunctionClass(name = "test_crash", result = HostTestGreeting::class)
internal class HostTestCrash : TrailblazeHostFunction<HostTestGreeting> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext): HostTestGreeting =
    error("kaboom")
}

/** Annotated, but doesn't implement [TrailblazeHostFunction] — the registry must skip it. */
@Serializable
@TrailblazeHostFunctionClass(name = "test_not_a_function", result = HostTestGreeting::class)
internal class HostTestNotAFunction

/** Implements and is annotated, but isn't @Serializable — the registry must skip it. */
@TrailblazeHostFunctionClass(name = "test_no_serializer", result = HostTestGreeting::class)
internal class HostTestNoSerializer : TrailblazeHostFunction<HostTestGreeting> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext) = HostTestGreeting("", 0)
}

@Serializable
internal data class HostTestOptions(val shout: Boolean = false)

@Serializable
@TrailblazeHostFunctionClass(name = "test_nested", result = HostTestGreeting::class)
internal data class HostTestNested(val options: HostTestOptions = HostTestOptions()) :
  TrailblazeHostFunction<HostTestGreeting> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext) = HostTestGreeting("nested", 6)
}

/** Reports the callback depth it runs at, as `length`. */
@Serializable
@TrailblazeHostFunctionClass(name = "test_depth", result = HostTestGreeting::class)
internal class HostTestDepth : TrailblazeHostFunction<HostTestGreeting> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext) =
    HostTestGreeting("depth", currentCoroutineContext()[JsScriptingCallbackDispatchDepth]?.depth ?: 0)
}

/** A result whose serializer fails with the value in its message. */
@Serializable(with = HostTestUnencodableSerializer::class)
internal data class HostTestUnencodable(val secret: String)

internal object HostTestUnencodableSerializer : KSerializer<HostTestUnencodable> {
  override val descriptor = PrimitiveSerialDescriptor("HostTestUnencodable", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: HostTestUnencodable) =
    throw IllegalStateException("cannot encode ${value.secret}")

  override fun deserialize(decoder: Decoder) = HostTestUnencodable(decoder.decodeString())
}

@Serializable
@TrailblazeHostFunctionClass(name = "test_unencodable", result = HostTestUnencodable::class)
internal class HostTestUnencodableResult : TrailblazeHostFunction<HostTestUnencodable> {
  override suspend fun invoke(context: TrailblazeToolExecutionContext) = HostTestUnencodable("hunter2")
}

class TrailblazeHostFunctionTest {

  @After fun cleanup() {
    JsScriptingInvocationRegistry.clearForTest()
  }

  private val pkg = "xyz.block.trailblaze.scripting.host"

  private fun descriptor(id: String, simpleName: String) = "id: $id\nclass: $pkg.$simpleName\n"

  /** A resource source holding exactly [files], keyed by path under `trails/config/trailmaps/`. */
  private fun registryOf(files: Map<String, String>) = TrailblazeHostFunctionRegistry(
    object : ConfigResourceSource {
      override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
        error("registry must discover recursively")

      override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> =
        files.filterKeys { it.endsWith(suffix) }
    },
  )

  private val registry = registryOf(
    mapOf(
      "greetings/host/test_greet.host.yaml" to descriptor("test_greet", "HostTestGreet"),
      "greetings/host/test_refuse.host.yaml" to descriptor("test_refuse", "HostTestRefuse"),
      "greetings/host/test_crash.host.yaml" to descriptor("test_crash", "HostTestCrash"),
      "greetings/host/test_nested.host.yaml" to descriptor("test_nested", "HostTestNested"),
      "greetings/host/test_depth.host.yaml" to descriptor("test_depth", "HostTestDepth"),
      "greetings/host/test_unencodable.host.yaml" to descriptor("test_unencodable", "HostTestUnencodableResult"),
    ),
  )

  private val sessionId = SessionId("host-function-test")

  private val context = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 2400,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    // A host function is not a tool. Reaching the nested tool executor would mean the call was
    // logged as a step and dropped the cached screen, so it fails the test outright.
    nestedToolExecutor = { tool -> error("host function call dispatched a tool: $tool") },
  )

  private fun call(name: String, argsJson: String) = runBlocking {
    TrailblazeHostFunctionDispatcher.call(name, argsJson, context, registry)
  }

  @Test
  fun `registry finds descriptors under a trailmap's host directory`() {
    assertThat(registry.all.keys).containsOnly(
      "test_greet",
      "test_refuse",
      "test_crash",
      "test_nested",
      "test_depth",
      "test_unencodable",
    )
    assertThat(registry.resolve("test_greet")?.trailmapId).isEqualTo("greetings")
  }

  @Test
  fun `registry skips descriptors that are misplaced, mismatched, or not host functions`() {
    val registry = registryOf(
      mapOf(
        "greetings/tools/test_greet.host.yaml" to descriptor("test_greet", "HostTestGreet"),
        "greetings/host/wrong_id.host.yaml" to descriptor("wrong_id", "HostTestRefuse"),
        "greetings/host/test_not_a_function.host.yaml" to descriptor("test_not_a_function", "HostTestNotAFunction"),
        "greetings/host/missing.host.yaml" to descriptor("missing", "NoSuchClass"),
        "greetings/host/test_no_serializer.host.yaml" to descriptor("test_no_serializer", "HostTestNoSerializer"),
        "greetings/host/test_crash.host.yaml" to descriptor("test_crash", "HostTestCrash"),
      ),
    )
    assertThat(registry.all.keys).containsOnly("test_crash")
  }

  @Test
  fun `success returns the result encoded with its serializer`() {
    val outcome = call("test_greet", """{"name":"sam","shout":true}""")
    assertThat(outcome).isEqualTo(
      TrailblazeHostFunctionDispatcher.Outcome.Success(
        buildJsonObject {
          put("greeting", "HELLO SAM")
          put("length", 9)
        },
      ),
    )
  }

  @Test
  fun `an argument the function does not declare fails the call`() {
    val outcome = call("test_greet", """{"name":"sam","nmae":"typo"}""")
    val failure = outcome as TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertThat(failure.message).contains("does not accept 'nmae'")
    assertThat(failure.message).contains("Accepted: name, shout")
  }

  @Test
  fun `an argument a nested object does not declare fails the call`() {
    val failure = call("test_nested", """{"options":{"shuot":true}}""") as TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertThat(failure.message).contains("'shuot'")
  }

  @Test
  fun `a decode failure does not repeat the arguments`() {
    val failure = call("test_greet", """{"name":{"password":"hunter2"}}""") as TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertThat(failure.message).contains("could not decode its arguments")
    assertThat(failure.message).doesNotContain("hunter2")
  }

  @Test
  fun `a result that fails to encode fails the call without repeating it`() {
    val failure = call("test_unencodable", "{}") as TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertThat(failure.message).contains("could not encode its result")
    assertThat(failure.message).doesNotContain("hunter2")
  }

  @Test
  fun `a missing required argument fails the call`() {
    val outcome = call("test_greet", "{}")
    assertThat(outcome).isInstanceOf(TrailblazeHostFunctionDispatcher.Outcome.Failure::class)
  }

  @Test
  fun `TrailblazeHostFunctionException reaches the script as its message alone`() {
    assertThat(call("test_refuse", """{"reason":"no"}"""))
      .isEqualTo(TrailblazeHostFunctionDispatcher.Outcome.Failure("refused: no"))
  }

  @Test
  fun `any other exception fails the call with its class and message`() {
    val failure = call("test_crash", "{}") as TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertThat(failure.message).contains("IllegalStateException: kaboom")
  }

  @Test
  fun `an unknown name lists the registered functions`() {
    val failure = call("test_nope", "{}") as TrailblazeHostFunctionDispatcher.Outcome.Failure
    assertThat(failure.message).contains("test_crash, test_depth, test_greet")
  }

  private fun dispatchHost(depth: Int, name: String, argsJson: String) = runBlocking {
    val handle = JsScriptingInvocationRegistry.register(
      sessionId = sessionId,
      toolRepo = TrailblazeToolRepo(TrailblazeToolSet.DynamicTrailblazeToolSet("empty", emptySet())),
      executionContext = context,
      depth = depth,
    )
    JsScriptingCallbackDispatcher.dispatch(
      JsScriptingCallbackRequest(
        sessionId = sessionId.value,
        invocationId = handle.invocationId,
        action = JsScriptingCallbackAction.CallHost(name, argsJson),
      ),
      hostFunctions = registry,
    ) as JsScriptingCallbackResult.CallHostResult
  }

  @Test
  fun `the subprocess callback runs a host function and returns its value`() {
    val result = dispatchHost(depth = 0, name = "test_greet", argsJson = """{"name":"ada"}""")
    assertThat(result.success).isEqualTo(true)
    assertThat(result.value).isEqualTo(
      buildJsonObject {
        put("greeting", JsonPrimitive("hello ada"))
        put("length", 9)
      },
    )
  }

  @Test
  fun `the subprocess callback runs a host function one level deeper than its caller`() {
    val result = dispatchHost(depth = 2, name = "test_depth", argsJson = "{}")
    assertThat(result.value).isEqualTo(
      buildJsonObject {
        put("greeting", JsonPrimitive("depth"))
        put("length", 3)
      },
    )
  }

  @Test
  fun `the subprocess callback refuses a host call at the recursion cap`() {
    val result = dispatchHost(
      depth = JsScriptingInvocationRegistry.MAX_CALLBACK_DEPTH,
      name = "test_greet",
      argsJson = """{"name":"ada"}""",
    )
    assertThat(result.success).isEqualTo(false)
    assertThat(result.errorMessage).contains("reentrance depth")
  }

  @Test
  fun `the subprocess callback carries a host function failure as its message`() {
    val result = dispatchHost(depth = 0, name = "test_refuse", argsJson = """{"reason":"no"}""")
    assertThat(result.success).isEqualTo(false)
    assertThat(result.errorMessage).isEqualTo("refused: no")
    assertThat(result.value).isNull()
  }
}
