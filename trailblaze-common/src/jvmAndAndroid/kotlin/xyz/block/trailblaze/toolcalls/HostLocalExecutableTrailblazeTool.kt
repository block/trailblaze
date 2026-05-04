package xyz.block.trailblaze.toolcalls

/**
 * Marker for [ExecutableTrailblazeTool]s that run **on the host JVM** rather than being routed
 * to a device / browser / cloud driver.
 *
 * The trail-execution path (see `BaseTrailblazeAgent.runTrailblazeTools`) short-circuits these
 * straight to `execute(context)` — bypassing the usual driver-specific dispatch in each agent's
 * `executeTool` override. Motivating case: subprocess MCP tools (Decision 038) — the Kotlin
 * client proxies each call over stdio to a spawned Node/bun subprocess; the device driver has
 * no role in that round-trip, and some agents (e.g. the on-device RPC agent) actively refuse
 * to dispatch tools they don't recognize.
 *
 * Prefer this marker over a class-level `@TrailblazeToolClass(requiresHost = true)` annotation
 * when the tool is constructed dynamically (no class-level annotation is available on instances
 * built by a runtime serializer).
 *
 * Implementations expose their [advertisedToolName] so the base agent can emit a
 * [xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog] for the host-local call
 * — dynamically-constructed tools have no class-level `@TrailblazeToolClass` to read the name
 * from, so it flows through the marker instead.
 */
interface HostLocalExecutableTrailblazeTool : ExecutableTrailblazeTool, InstanceNamedTrailblazeTool {
  /** The tool name that shows up in session logs for this execution. */
  val advertisedToolName: String

  /**
   * Surfaces [advertisedToolName] under the [InstanceNamedTrailblazeTool] contract so that
   * the canonical encoder ([toOtherTrailblazeToolPayload]) and any path that reads instance
   * name from the marker interface (rather than the class-level annotation) see the same
   * dynamic name. Without this, encoding a HostLocal tool through the JSON contextual
   * serializer would fall through to `class.simpleName` and produce a name that differs from
   * what `getToolNameFromAnnotation` returns for the same instance.
   */
  override val instanceToolName: String get() = advertisedToolName
}
