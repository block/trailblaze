package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

/**
 * Kotlin-side bridge to invoke any framework tool by its `@TrailblazeToolClass(name=...)` name,
 * with the args supplied as a `@Serializable` data class. Pairs with the TS surface
 * `ctx.tools.<name>(args)` — same registry on both sides, same wire shape, just a Kotlin
 * front-end for composition inside a Kotlin tool's `execute(...)`.
 *
 * Motivating use case: incremental Kotlin → TS migration of a multi-step launch-app
 * orchestration. As individual steps land as framework tools that are TS-callable
 * (`android_grantPermissions`, `android_pushFile`, `mobile_clearAppData`, …), the
 * still-Kotlin orchestrator wraps them via this bridge during the transition. When the
 * orchestrator itself flips to TS, the composing call sites stay identical — same tool
 * names, same args shapes, just the caller language changes.
 *
 * ## Why a typed-args overload, not a JSON string
 *
 * The underlying [TrailblazeToolRepo.toolCallToTrailblazeToolUnfiltered] takes a JSON
 * string. Hand-formatting JSON at every call site is error-prone: a typo in a property
 * name silently dispatches with the wrong args, escaping a string is easy to forget, and
 * the IDE gives no help on the target tool's schema. The typed overload lets authors
 * mirror the TS args interface as a Kotlin `@Serializable` data class (duplication is
 * acceptable today — a future codegen step can derive one from the other), and the
 * framework's [TrailblazeJsonInstance] handles the wire encoding. Wrong field name →
 * compile error. Missing required field → compile error.
 *
 * ## Failure modes
 *
 *  - **`toolRepo` not wired** on the context. Test fixtures and any production dispatcher
 *    that pre-dates this hook will hit this. The error message names the missing field
 *    and points at the producer contract on
 *    [TrailblazeToolExecutionContext.toolRepo]'s kdoc.
 *  - **Unknown tool name**. The repo's unfiltered lookup searches session-registered
 *    classes, YAML-defined tools, and the global classpath registry; if all three miss,
 *    we fail loudly with the searched name so the caller can spot a typo or a missing
 *    `@TrailblazeToolClass` registration.
 *  - **Resolved tool is not executable**. Some framework tools are pure data
 *    (declarative-only — they're meant to be re-encoded into a recorded trail, not
 *    `.execute()`d directly). Catching this with a clear error rather than a confusing
 *    `ClassCastException` matters because the failure mode otherwise points at the cast
 *    site, not at the tool author's intent.
 */
@OptIn(InternalSerializationApi::class)
suspend inline fun <reified T : Any> TrailblazeToolExecutionContext.invokeFrameworkTool(
  toolName: String,
  args: T,
): TrailblazeToolResult {
  val repo = toolRepo ?: error(
    "TrailblazeToolExecutionContext.invokeFrameworkTool(\"$toolName\", ...) requires the " +
      "context's `toolRepo` field to be populated. Production host runners thread the " +
      "session's TrailblazeToolRepo through; unit test fixtures need to construct the " +
      "context with `toolRepo = repo` explicitly. See TrailblazeToolExecutionContext.toolRepo " +
      "kdoc for the producer contract.",
  )
  val argsJson = TrailblazeJsonInstance.encodeToString(T::class.serializer(), args)
  return invokeFrameworkToolByJson(repo, toolName, argsJson)
}

/**
 * JSON-string overload — kept on the surface for the rare caller that genuinely has a
 * pre-serialized args payload (most authors should reach for the typed overload above).
 * Same lookup + dispatch semantics; same failure-mode contract.
 */
suspend fun TrailblazeToolExecutionContext.invokeFrameworkTool(
  toolName: String,
  argsJson: String,
): TrailblazeToolResult {
  val repo = toolRepo ?: error(
    "TrailblazeToolExecutionContext.invokeFrameworkTool(\"$toolName\", ...) requires the " +
      "context's `toolRepo` field to be populated. See TrailblazeToolExecutionContext.toolRepo " +
      "kdoc for the producer contract.",
  )
  return invokeFrameworkToolByJson(repo, toolName, argsJson)
}

/**
 * Internal dispatch helper shared by both [invokeFrameworkTool] overloads. Kept private to the
 * package so the typed + JSON entry points stay the only public surface — a caller that
 * imports this directly is bypassing the typed-args contract on purpose, which is rarely
 * what authors want.
 */
@PublishedApi
internal suspend fun TrailblazeToolExecutionContext.invokeFrameworkToolByJson(
  repo: TrailblazeToolRepo,
  toolName: String,
  argsJson: String,
): TrailblazeToolResult {
  val tool = repo.toolCallToTrailblazeToolUnfiltered(toolName, argsJson)
    ?: error(
      "Unknown framework tool: \"$toolName\". The repo's unfiltered lookup searched " +
        "session-registered class-backed tools, YAML-defined tools, and the global " +
        "classpath registry; no match. Confirm the target tool's `@TrailblazeToolClass(name=...)` " +
        "annotation value matches the requested name, and that the tool's module is on the " +
        "session's classpath.",
    )
  // When the context carries a `nestedToolExecutor` (production agents always do — Maestro
  // wires its own `runTrailblazeTools` recursive entry point, Playwright wraps with its
  // driver-specific dispatch, etc.), route through it so the call goes through the same
  // logging / recording / driver-binding path a top-level tool dispatch hits. This matches
  // the `JsScriptingCallbackDispatcher` contract — TS `ctx.tools.<name>(args)` already
  // routes through `nestedToolExecutor`, and the whole point of this bridge is to be the
  // Kotlin-side equivalent. Falling back to `executable.execute(this)` only when the
  // executor is null keeps unit-test fixtures (which build raw contexts without an agent
  // attached) working with the same minimal setup.
  val nested = nestedToolExecutor
  if (nested != null) {
    // Pass the un-cast `tool` so a `TrailblazeTool` that isn't an `ExecutableTrailblazeTool`
    // still gets a chance to dispatch via the executor — it may handle declarative-only
    // tools (e.g. wrapping them into a recorded trail entry without execute()-ing).
    return nested.invoke(tool)
  }
  val executable = tool as? ExecutableTrailblazeTool
    ?: error(
      "Framework tool \"$toolName\" resolved as ${tool::class.simpleName} which is not an " +
        "ExecutableTrailblazeTool — declarative-only tools (data containers for recorded " +
        "trails) can't be dispatched via invokeFrameworkTool when no `nestedToolExecutor` " +
        "is wired on the context. If you need to compose this tool from Kotlin, either " +
        "(a) re-implement it as ExecutableTrailblazeTool, or (b) wire the context's " +
        "`nestedToolExecutor` (production agents already do this).",
    )
  return executable.execute(this)
}
