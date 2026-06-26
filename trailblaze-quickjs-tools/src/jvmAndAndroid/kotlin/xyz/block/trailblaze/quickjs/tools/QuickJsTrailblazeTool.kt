package xyz.block.trailblaze.quickjs.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolMetadata
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Executable form of a tool advertised by a QuickJS bundle. Implements
 * [HostLocalExecutableTrailblazeTool] so [BaseTrailblazeAgent.runTrailblazeTools][xyz.block.trailblaze.BaseTrailblazeAgent]
 * short-circuits to [execute] and skips the driver-specific path that would otherwise route
 * the tool through Maestro / Playwright.
 *
 * No MCP framing here — [execute] simply asks [host] to dispatch by name with the typed
 * args + a small ctx envelope built from [QuickJsToolCtxEnvelope]. The data classes in
 * [QuickJsToolEnvelopes] are the single source of truth for envelope field names; a
 * future change that grows the `ctx` shape edits the data class instead of every
 * `put("…", …)` call site.
 */
class QuickJsTrailblazeTool(
  /** The host this tool's bundle was loaded into. Stable for the session. */
  internal val host: QuickJsToolHost,
  /** The tool name the bundle registered. Same name the LLM sees. */
  internal val advertisedName: ToolName,
  /** LLM-supplied args, decoded from the tool-call's `arguments` JSON. */
  internal val args: JsonObject,
  /**
   * Session-scoped binding for this tool's host. When non-null, [execute] sets
   * [SessionScopedHostBinding.activeContext] for the duration of the QuickJS evaluation so
   * nested `client.callTool(...)` calls from inside the bundle can resolve the session context.
   *
   * Required for the koog dispatch path (LLM agent → [buildKoogTool] argsSerializer →
   * [execute] directly). Without it, the QuickJS `asyncFunction` callback fires on
   * [kotlinx.coroutines.Dispatchers.Default] where the [ToolExecutionContextThreadLocal] is
   * null and [SessionScopedHostBinding.activeContext] was never set, causing every nested
   * `trailblaze.call(...)` to return "no execution context installed".
   *
   * The [ContextSettingScriptedTool][xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration]
   * wrapper (used by the `tools:` trail-item / [decodeToolCall] path) sets [activeContext]
   * from outside; when [binding] is non-null here, both paths set it and the outer wrapper's
   * finally-clear is a harmless no-op after the inner finally already cleared it.
   */
  internal val binding: SessionScopedHostBinding? = null,
  /**
   * 1:1 with the scripted tool's declared `isRecordable`. `false` surfaces a per-instance
   * [toolMetadata] override so the recording gate
   * ([getIsRecordableFromAnnotation][xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation],
   * which consults `toolMetadata?.isRecordable` first) keeps the invocation out of the replayable
   * `.trail.yaml`. Carried on the tool itself — NOT a wrapper — so the decoded instance stays a
   * [QuickJsTrailblazeTool]: `SessionScopedHostBinding`'s same-host re-entry guard keys off that
   * exact type, and a wrapper would let a same-bundle compose bypass the guard and deadlock the
   * host's non-reentrant `evalMutex`. Default `true`.
   */
  internal val isRecordable: Boolean = true,
) : HostLocalExecutableTrailblazeTool, RawArgumentTrailblazeTool {

  constructor(host: QuickJsToolHost, advertisedName: ToolName, args: JsonObject) :
    this(host, advertisedName, args, null)

  override val advertisedToolName: String get() = advertisedName.toolName

  // `null` when recordable (default) preserves the prior behavior — the recording gate falls
  // through to the (absent) class annotation's `true` default. Only a `false` config surfaces an
  // override that flips the recorded bit.
  override val toolMetadata: TrailblazeToolMetadata?
    get() = if (isRecordable) null else TrailblazeToolMetadata(isRecordable = false)

  // Surface the LLM-supplied args as `rawToolArguments` so `toLogPayload()` writes them
  // into the `TrailblazeToolLog.raw` field verbatim — otherwise this class-backed (but
  // not `@Serializable`) tool falls through `encodeAsRawJsonOrEmpty()` and emits an empty
  // `raw`, corrupting recording reconstruction. Same shape `SubprocessTrailblazeTool` and
  // `BundleTrailblazeTool` use for the same reason.
  override val rawToolArguments: JsonObject get() = args

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // Resolve ${key}/{{key}} memory tokens in the recorded args before they reach the JS engine.
    // The AI path interpolates upstream (AgentUiActionExecutor.mapToTrailblazeTool); recorded-replay
    // decodes args verbatim and never did, so a recorded `email: ${userEmail}` reached the
    // bundle as the literal token and was typed as "undefined". Idempotent on the AI path (already-
    // resolved args carry no tokens). rawToolArguments stays un-interpolated so replaying a tokenized
    // trail keeps the token in a re-recording (on the AI path, args were already resolved upstream).
    val resolvedArgs = (toolExecutionContext.memory.interpolateVariablesInJson(args) as? JsonObject) ?: args
    val ctx = buildCtxEnvelope(toolExecutionContext)
    // Install the session context on the binding so nested client.callTool() calls from inside
    // the JS bundle can resolve it via SessionScopedHostBinding.callFromBundle. The QuickJS
    // asyncFunction callback fires on Dispatchers.Default (a different thread) so
    // ToolExecutionContextThreadLocal is unreliable there; @Volatile activeContext is the only
    // mechanism that crosses the thread boundary safely. Clear in finally so a failed dispatch
    // doesn't leak the context into a subsequent unrelated dispatch on the same binding.
    binding?.activeContext = toolExecutionContext
    return try {
      val resultJson = host.callTool(advertisedName.toolName, resolvedArgs, ctx)
      resultJson.toTrailblazeToolResult(toolName = advertisedName.toolName)
    } catch (e: CancellationException) {
      // Coroutine cancellation must propagate — swallowing breaks structured concurrency
      // for session teardown, agent abort, etc. Same pattern the legacy BundleTrailblazeTool
      // uses; catch order matters because CancellationException extends Exception.
      throw e
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, this)
    } finally {
      binding?.activeContext = null
    }
  }

  private fun buildCtxEnvelope(toolExecutionContext: TrailblazeToolExecutionContext): JsonObject {
    val sessionId = toolExecutionContext.sessionProvider.invoke().sessionId
    val deviceInfo = toolExecutionContext.trailblazeDeviceInfo
    // ctx.target is populated only when the host runner pre-resolved a target at session
    // start (see TrailblazeToolExecutionContext.resolvedTarget). Web/scratch sessions and
    // unit-test fixtures leave it null, and that's the right shape — the JSON omits the
    // `target` key entirely so JS authors can `if (ctx.target) { ... }` cleanly.
    val target = toolExecutionContext.resolvedTarget?.let { resolved ->
      QuickJsTargetContext(
        id = resolved.id,
        appIds = resolved.appIds,
        appId = toolExecutionContext.appId,
      )
    }
    // NON-sensitive memory snapshot — mirrors the subprocess envelope's filter in
    // `TrailblazeContextEnvelope` (`if (k !in sensitiveKeys) put(...)`). Values seeded via
    // AgentMemory.rememberSensitive (merchant passwords, PINs) are WITHHELD from the bundle ctx
    // on both dispatch paths, matching the existing contract that keeps secrets out of the LLM
    // context, the scripting envelope, and logs. A TS tool that needs a credential passes the
    // `{{token}}` through to a Kotlin device-command tool, which interpolates it against full
    // memory inside its own execute() (the same pattern as InputTextTrailblazeTool et al.) — so
    // plaintext never enters the JS heap. `filterKeys` builds the non-sensitive map directly off the
    // live ConcurrentHashMap (weakly-consistent iteration, no full intermediate copy) so a sensitive
    // value is never even copied into a throwaway map en route to the envelope.
    val agentMemory = toolExecutionContext.memory
    val nonSensitiveMemory =
      agentMemory.variables.filterKeys { it !in agentMemory.sensitiveKeys }
    val ctx = QuickJsToolCtxEnvelope(
      sessionId = sessionId.value,
      device = QuickJsDeviceContext(
        platform = deviceInfo.trailblazeDriverType.platform.name,
        driverType = deviceInfo.trailblazeDriverType.yamlKey,
        driver = deviceInfo.trailblazeDriverType.yamlKey,
        instanceId = deviceInfo.trailblazeDeviceId.instanceId,
      ),
      target = target,
      memory = nonSensitiveMemory,
    )
    // The host expects the ctx as a JsonObject (it embeds it inline as a JS literal),
    // so encode-to-element rather than encode-to-string — saves a parse-back trip.
    return QuickJsToolEnvelopeJson.encodeToJsonElement(
      QuickJsToolCtxEnvelope.serializer(),
      ctx,
    ).jsonObject
  }
}

/**
 * Maps the SDK's tool-result envelope to a [TrailblazeToolResult]. Decodes through the
 * typed [QuickJsToolResultEnvelope] — the field names live in [QuickJsToolEnvelopes],
 * not as ad-hoc string keys here.
 *
 * **Malformed-envelope handling:** an envelope that fails strict deserialization (e.g.,
 * a bare `{}` or `{ result: 42 }` with no `content` array, or one whose content list
 * holds non-object entries) is reported as [TrailblazeToolResult.Error.ExceptionThrown]
 * rather than a silent `Success(null)`. Author bugs in a bundle handler that returned
 * the wrong shape would otherwise hide as no-op trail passes — the LLM sees nothing
 * wrong, trail logs show success, and the broken handler keeps shipping. Treating
 * "shape doesn't match" as a structural error makes the bug loud at the boundary.
 * [QuickJsToolEnvelopeJson]'s `ignoreUnknownKeys` keeps forward-compatible extra fields
 * harmless — only required-field mismatches surface as errors.
 */
internal fun JsonObject.toTrailblazeToolResult(toolName: String? = null): TrailblazeToolResult {
  // We need the parser to be lenient on extra fields but strict on shape — `content`
  // must exist for a Success. Decode into the typed envelope; if `content` was missing
  // entirely we'll see an empty list (the data class default), which we treat as a
  // structural error per the kdoc above.
  val envelope = try {
    QuickJsToolEnvelopeJson.decodeFromJsonElement(
      QuickJsToolResultEnvelope.serializer(),
      this,
    )
  } catch (e: SerializationException) {
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "QuickJS tool ${toolLabel(toolName)}returned a malformed envelope: ${e.message}",
    )
  }
  if (envelope.isError) {
    val rendered = envelope.renderContent()
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = rendered.ifBlank { "QuickJS tool returned isError without text content" },
    )
  }
  // A missing `content` decodes to an empty list (data-class default). Distinguish "the
  // bundle returned an empty content array on purpose" (Success with null message —
  // legitimate for void-shaped tools) from "the bundle didn't return a content key at
  // all" (structural error). The presence check looks at the raw JsonObject because
  // the data-class default has already collapsed both into the same in-memory shape.
  if ("content" !in this) {
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "QuickJS tool ${toolLabel(toolName)}returned an envelope without `content` — " +
        "the handler must return `{ content: [...] }` (and optionally `isError: true`).",
    )
  }
  val rendered = envelope.renderContent()
  return TrailblazeToolResult.Success(
    message = rendered.ifBlank { null },
    // `structuredContent` flows verbatim — a bundle handler that returns a typed value
    // (via `trailblaze.tool<I, O>({ handler })` or by populating the field explicitly) lands
    // here and gets forwarded to the scripted caller's `client.tools.<name>(...)` unwrap.
    structuredContent = envelope.structuredContent,
  )
}

private fun toolLabel(toolName: String?): String = toolName?.let { "'$it' " }.orEmpty()

/**
 * Concatenates every text-typed content part with newlines. Non-text parts surface as a
 * `<type content>` placeholder so the LLM sees that *something* was returned. Mirrors
 * the previous JsonObject-based renderer; the only change is that the parts are now
 * typed [QuickJsContentPart]s rather than ad-hoc JsonObject lookups.
 */
private fun QuickJsToolResultEnvelope.renderContent(): String =
  content.joinToString(separator = "\n") { part ->
    if (part.type == "text") part.text.orEmpty() else "<${part.type} content>"
  }
