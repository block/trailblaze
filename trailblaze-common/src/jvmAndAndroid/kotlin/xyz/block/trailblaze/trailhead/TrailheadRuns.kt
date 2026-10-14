package xyz.block.trailblaze.trailhead

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.trailhead.TrailheadEventLog.putIfNotNull
import xyz.block.trailblaze.util.Console

/** Where a [TrailheadRun] is in its trail. */
enum class TrailheadPhase {
  /** The trailhead step is running. */
  TRAILHEAD,

  /** The trailhead returned; trail steps are running. */
  TRAIL,

  /** The verdict is fixed and `teardown` is running. Notes are no longer collected. */
  TEARDOWN,
}

/** One started invocation of a trailhead tool that defines `teardown`. */
class TrailheadInvocation internal constructor(
  val tool: TeardownCapableTool,
  /** The resolved args the trailhead ran with; `teardown` receives the same. */
  val args: JsonObject,
  val key: TrailheadMatchKey,
  /** What the handler received as `ctx.previousRun`. */
  val previousRun: PreviousRun?,
  /**
   * The device binding the trailhead ran on in a multi-device trail, or null for one device.
   * `teardown` runs only while that device is still the active one.
   */
  val deviceName: String? = null,
) {
  @Volatile var reusedReason: String? = null
    internal set
}

/**
 * The trailhead state of one trail in one session: the trail markers, the notes tools wrote, and
 * the trailhead invocations `teardown` must follow. Opened by the runner when the trailhead step
 * starts and closed after `teardown`; nothing in it carries over to the next trail.
 */
class TrailheadRun internal constructor(
  val sessionId: SessionId,
  trailheadToolNames: Set<String>,
  /** Off by default; set by the runner for the lane, never by the trail author. */
  val reuseEnabled: Boolean,
  val trailheadStartMs: Long,
) {
  private val _trailheadToolNames: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    .apply { addAll(trailheadToolNames) }

  /** The tools the trail's trailhead steps declare. Only these receive `previous`. */
  val trailheadToolNames: Set<String> get() = _trailheadToolNames

  @Volatile var phase: TrailheadPhase = TrailheadPhase.TRAILHEAD
    private set
  @Volatile var trailStartMs: Long? = null
    private set
  @Volatile var trailEndMs: Long? = null
    private set

  /** Set once teardown has dropped what earlier trails handed on; until then the runner drops them all. */
  @Volatile internal var handoffsDropped: Boolean = false

  private val _notes = CopyOnWriteArrayList<TrailheadNote>()
  val notes: List<TrailheadNote> get() = _notes.toList()

  private val _invocations = CopyOnWriteArrayList<TrailheadInvocation>()
  val invocations: List<TrailheadInvocation> get() = _invocations.toList()

  internal fun addInvocation(invocation: TrailheadInvocation) {
    _invocations += invocation
  }

  internal fun addNote(note: TrailheadNote) {
    _notes += note
  }

  fun markTrailStart(timeMs: Long = System.currentTimeMillis()) {
    if (phase == TrailheadPhase.TRAILHEAD) {
      if (trailStartMs == null) trailStartMs = timeMs
      phase = TrailheadPhase.TRAIL
    }
  }

  /** A later trailhead step in the same trail (one per device in a multi-device trail). */
  internal fun addTrailheadStep(toolNames: Set<String>) {
    _trailheadToolNames += toolNames
    if (phase == TrailheadPhase.TRAIL) phase = TrailheadPhase.TRAILHEAD
  }

  fun markTrailEnd(timeMs: Long = System.currentTimeMillis()) {
    trailEndMs = timeMs
    phase = TrailheadPhase.TEARDOWN
  }
}

/**
 * The open [TrailheadRun] per session, and the hooks scripted tools call into it. Process-wide
 * because tools reach it from their execution context, not from the runner.
 */
object TrailheadRuns {
  private val bySession = ConcurrentHashMap<SessionId, TrailheadRun>()

  /** Largest `state` a hand-off keeps; a bigger one is dropped with a warning. */
  const val MAX_STATE_BYTES: Int = 16 * 1024

  /**
   * Host runners: `true` lets a trailhead receive the previous trail's hand-off. Read by the
   * process that runs the trail (for `trailblaze run`, the daemon). On-device runners read the
   * [REUSE_INSTRUMENTATION_ARG] instrumentation arg instead.
   */
  const val REUSE_ENV_VAR: String = "TRAILBLAZE_TRAILHEAD_REUSE"
  const val REUSE_INSTRUMENTATION_ARG: String = "trailblaze.trailheadReuse"

  /** True only for `true` (case-insensitive); unset and every other value leave reuse off. */
  fun reuseEnabled(raw: String?): Boolean {
    val value = raw?.trim() ?: return false
    if (value.equals("true", ignoreCase = true)) return true
    if (value.isNotEmpty()) Console.log("[Teardown] ignoring trailhead reuse value '$value'; only 'true' turns it on")
    return false
  }

  fun reuseEnabledFromEnv(env: (String) -> String? = System::getenv): Boolean = reuseEnabled(env(REUSE_ENV_VAR))

  /**
   * Open the session's run as a trailhead step starts, replacing any run left open by a trail that
   * never finished. [sameTrail] — the run an earlier trailhead step of this same trail opened — gains
   * this step's tools instead, while it is still the session's run.
   */
  fun open(
    sessionId: SessionId,
    trailheadToolNames: Set<String>,
    reuseEnabled: Boolean,
    sameTrail: TrailheadRun? = null,
  ): TrailheadRun {
    if (sameTrail != null && bySession[sessionId] === sameTrail && sameTrail.phase != TrailheadPhase.TEARDOWN) {
      sameTrail.addTrailheadStep(trailheadToolNames)
      return sameTrail
    }
    val run = TrailheadRun(sessionId, trailheadToolNames, reuseEnabled, System.currentTimeMillis())
    bySession[sessionId] = run
    return run
  }

  fun current(sessionId: SessionId): TrailheadRun? = bySession[sessionId]

  fun close(run: TrailheadRun) {
    bySession.remove(run.sessionId, run)
  }

  /**
   * Called by a capable tool before its handler runs. When this is a top-level invocation of one of
   * the trail's trailhead tools, consumes this device's stored hand-off (match or not, before any
   * trailhead code runs), decides what the handler receives as `previousRun`, logs it, and returns the
   * invocation. Returns null for every other call.
   */
  suspend fun beginInvocation(
    context: TrailblazeToolExecutionContext,
    tool: TeardownCapableTool,
    resolvedArgs: JsonObject,
  ): TrailheadInvocation? {
    val run = runFor(context) ?: return null
    if (run.phase != TrailheadPhase.TRAILHEAD) return null
    if (tool.trailheadToolName !in run.trailheadToolNames) return null
    if (context.nestedDispatchDepth.get() > 0) return null

    val deviceId = deviceIdOf(context)
    val stored = TrailheadHandoffStore.consume(deviceId, tool.trailheadToolName)
    if (!tool.definesTeardown()) {
      TrailheadEventLog.append(context, TrailheadEventStream.Type.PREVIOUS_RUN) {
        put("tool", tool.trailheadToolName)
        put("previousRun", JsonNull)
        put("reason", "this trailhead defines no teardown")
      }
      return null
    }
    val key = TrailheadMatchKey(
      toolName = tool.trailheadToolName,
      codeVersion = tool.trailheadCodeVersion,
      argDigests = TrailheadMatchKey.digestArgs(resolvedArgs),
      deviceId = deviceId,
      deviceSettings = deviceSettingsOf(context),
    )
    val absentReason: String? = when {
      !run.reuseEnabled -> "reuse is off for this lane"
      stored == null -> "no result from a previous run of this trailhead on this device (first run " +
        "here, a trail in between that did not hand one on, or a crash or kill)"
      else -> stored.key.mismatchReason(key)
        ?: if (stored.result.reusable) null else "the last run was not reusable: ${stored.result.reason}"
    }
    val previousRun = if (absentReason == null && stored != null) {
      PreviousRun(sessionId = stored.sessionId.value, reason = stored.result.reason, state = stored.result.state)
    } else {
      null
    }
    TrailheadEventLog.append(context, TrailheadEventStream.Type.TRAILHEAD_START, run.trailheadStartMs) {
      put("tool", tool.trailheadToolName)
    }
    TrailheadEventLog.append(context, TrailheadEventStream.Type.PREVIOUS_RUN) {
      put("tool", tool.trailheadToolName)
      if (previousRun != null) {
        put("previousRun", TrailblazeJsonInstance.encodeToJsonElement(previousRun))
      } else {
        put("previousRun", JsonNull)
        put("reason", absentReason)
      }
    }
    return TrailheadInvocation(tool, resolvedArgs, key, previousRun, deviceNameOf(context))
      .also { run.addInvocation(it) }
  }

  /** The `ctx.previousRun` a capable tool puts on its envelope for [invocation], or null. */
  fun envelopeFor(invocation: TrailheadInvocation?): JsonObject? {
    val previousRun = invocation?.previousRun ?: return null
    return TrailblazeJsonInstance.encodeToJsonElement(previousRun).jsonObject
  }

  /**
   * Called by a capable tool after its handler returned or threw, with the result's `_meta`. Records
   * notes from any tool, and for a trailhead [invocation] logs what the handler did with the
   * previous setup: reused it, tried to and failed ([failed] or a catch-up that threw), or ran in full.
   */
  /**
   * Why a trailhead [invocation] whose handler returned normally still failed: its
   * `previousRun.reuse()` catch-up threw (and the handler caught it) or had not finished. Null
   * when it did no reuse or the reuse finished. Check before accepting the handler's result.
   */
  fun reuseFailure(invocation: TrailheadInvocation?, resultMeta: JsonObject?): String? {
    if (invocation == null) return null
    val reuse = reuseOf(resultMeta) ?: return null
    if ((reuse["ok"] as? JsonPrimitive)?.booleanOrNull == true) return null
    val error = (reuse["error"] as? JsonPrimitive)?.contentOrNull
    return if (error != null) {
      "the trailhead carried on after its previousRun.reuse() catch-up failed: $error"
    } else {
      "the trailhead returned before its previousRun.reuse() catch-up finished"
    }
  }

  private fun reuseOf(resultMeta: JsonObject?): JsonObject? =
    ((resultMeta?.get("trailblaze") as? JsonObject)?.get(TeardownWire.META_KEY_TRAILHEAD) as? JsonObject)
      ?.get("reuse") as? JsonObject

  fun completeInvocation(
    context: TrailblazeToolExecutionContext,
    toolName: String,
    invocation: TrailheadInvocation?,
    resultMeta: JsonObject?,
    failed: Boolean = false,
  ) {
    val report = (resultMeta?.get("trailblaze") as? JsonObject)?.get(TeardownWire.META_KEY_TRAILHEAD) as? JsonObject
    val run = runFor(context)
    if (run != null && run.phase != TrailheadPhase.TEARDOWN) {
      (report?.get("notes") as? JsonArray)?.forEach { raw -> parseNote(raw, toolName)?.let { note ->
        run.addNote(note)
        TrailheadEventLog.append(context, TrailheadEventStream.Type.NOTE, note.timestamp) {
          put("tool", note.tool)
          putIfNotNull("invalidates", note.invalidates)
          note.created?.let { put("created", buildJsonObject { put("kind", it.kind); put("id", it.id) }) }
        }
      } }
    }
    if (invocation == null) return
    val reuse = reuseOf(resultMeta)
    val reuseReason = (reuse?.get("reason") as? JsonPrimitive)?.contentOrNull
    // A catch-up still unresolved when the handler returned counts as failed: nothing confirmed it.
    val reused = reuseReason != null && !failed && (reuse?.get("ok") as? JsonPrimitive)?.booleanOrNull == true
    invocation.reusedReason = reuseReason?.takeIf { reused }
    TrailheadEventLog.append(context, TrailheadEventStream.Type.SETUP) {
      put("tool", toolName)
      if (reuseReason == null) {
        put("setup", TrailheadEventStream.Setup.FULL_SETUP)
      } else {
        put("setup", if (reused) TrailheadEventStream.Setup.REUSED else TrailheadEventStream.Setup.REUSE_FAILED)
        put("reason", reuseReason)
        (reuse?.get("startedAtMs") as? JsonPrimitive)?.longOrNull?.let { put("reuseStartedAtMs", it) }
        putIfNotNull("error", (reuse?.get("error") as? JsonPrimitive)?.contentOrNull)
        invocation.previousRun?.let { put("previousRunSessionId", it.sessionId) }
      }
      if (failed) put("failed", true)
    }
  }

  private fun parseNote(raw: kotlinx.serialization.json.JsonElement, toolName: String): TrailheadNote? {
    val obj = raw as? JsonObject ?: return null
    val now = System.currentTimeMillis()
    (obj["invalidates"] as? JsonPrimitive)?.contentOrNull?.let {
      return TrailheadNote(invalidates = it, tool = toolName, timestamp = now)
    }
    val created = obj["created"] as? JsonObject ?: return null
    val kind = (created["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
    val id = (created["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    return TrailheadNote(created = TrailheadNote.Created(kind, id), tool = toolName, timestamp = now)
  }

  private fun runFor(context: TrailblazeToolExecutionContext): TrailheadRun? {
    val sessionId = runCatching { context.sessionProvider.invoke().sessionId }.getOrNull() ?: return null
    return bySession[sessionId]
  }

  /** The active device binding's name when the trail binds more than one device, else null. */
  internal fun deviceNameOf(context: TrailblazeToolExecutionContext): String? =
    context.deviceBindings?.takeIf { it.names.size > 1 }?.activeName

  internal fun deviceIdOf(context: TrailblazeToolExecutionContext): String =
    TrailheadHandoffStore.keyOf(context.trailblazeDeviceInfo.trailblazeDeviceId)

  private fun deviceSettingsOf(context: TrailblazeToolExecutionContext): String {
    val info = context.trailblazeDeviceInfo
    return "driver=${info.trailblazeDriverType.yamlKey};locale=${info.locale};app=${context.appId}"
  }

  /**
   * Apply the forced-`false` rules to what `teardown` returned and store the hand-off for the
   * next trail on this device. Returns the result as stored.
   */
  internal fun recordResult(
    context: TrailblazeToolExecutionContext,
    run: TrailheadRun,
    invocation: TrailheadInvocation,
    outcome: TrailOutcome,
    raw: JsonObject?,
    failure: String?,
  ): TeardownResult {
    val returned = raw?.let { decodeResult(it) }
    val oversized = returned?.state?.let { it.toString().toByteArray(Charsets.UTF_8).size > MAX_STATE_BYTES } == true
    val state = returned?.state?.takeUnless { oversized }
    if (oversized) {
      Console.error("[Teardown] ${invocation.tool.trailheadToolName}: state is over $MAX_STATE_BYTES bytes; dropped")
    }
    val forcedBecause: String? = when {
      failure != null -> "teardown failed: $failure"
      returned == null -> "teardown returned no result"
      outcome != TrailOutcome.PASSED -> "the trail ${outcome.wireName()}"
      else -> TeardownReuseVetoes.vetoReason(run.sessionId)
        ?: run.notes.firstNotNullOfOrNull { it.invalidates }?.let { "a step invalidated the setup: $it" }
        ?: if (oversized) "its state is over ${MAX_STATE_BYTES / 1024} KB and was dropped" else null
    }
    val result = when {
      returned == null -> TeardownResult(reusable = false, reason = forcedBecause ?: "no result")
      forcedBecause != null && returned.reusable ->
        TeardownResult(reusable = false, reason = "$forcedBecause (teardown said: ${returned.reason})", state = state)
      else -> returned.copy(state = state)
    }
    TrailheadHandoffStore.put(StoredHandoff(invocation.key, result, run.sessionId))
    TrailheadEventLog.append(context, TrailheadEventStream.Type.TEARDOWN) {
      put("tool", invocation.tool.trailheadToolName)
      put("outcome", outcome.wireName())
      put("reusable", result.reusable)
      put("reason", result.reason)
      putIfNotNull("forcedBecause", forcedBecause?.takeIf { returned?.reusable == true || returned == null })
      result.state?.let { put("state", it) }
    }
    return result
  }

  private fun decodeResult(raw: JsonObject): TeardownResult? = runCatching {
    TrailblazeJsonInstance.decodeFromJsonElement(TeardownResult.serializer(), raw)
  }.getOrNull()?.takeIf { it.reason.isNotBlank() }

  /** Test seam. */
  internal fun clear() = bySession.clear()
}

internal fun TrailOutcome.wireName(): String =
  TrailblazeJsonInstance.encodeToJsonElement(TrailOutcome.serializer(), this).let { (it as JsonPrimitive).content }
