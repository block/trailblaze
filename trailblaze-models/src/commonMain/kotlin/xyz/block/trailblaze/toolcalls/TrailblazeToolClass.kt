package xyz.block.trailblaze.toolcalls

/**
 * Annotation for Trailblaze tools that defines their execution characteristics.
 *
 * @property name The unique name of the tool used for serialization and LLM selection.
 * @property surfaceToLlm Whether the LLM agent toolbox advertises this tool at session start.
 *   Set to false for implementation-detail tools that use unstable identifiers (e.g., node IDs)
 *   or that we don't want the LLM picking spontaneously (e.g., text-based selectors that are
 *   brittle when authored by the LLM but fine when authored explicitly by a scripted-tool
 *   author). Independent of [surfaceToScriptedTools] — a tool can be hidden from the LLM agent
 *   yet still emitted into per-pack `client.d.ts` for typed scripted-tool authoring.
 * @property surfaceToScriptedTools Whether per-pack `client.d.ts` codegen emits a typed binding
 *   for this tool (i.e. whether scripted-tool TS authors can call `client.tools.<name>(...)`).
 *   Set to false for tools that should remain invisible to scripted-tool authoring — typically
 *   internal dispatcher implementation details. Independent of [surfaceToLlm]: hiding a tool
 *   from the LLM agent does NOT imply hiding it from the scripted-tool surface.
 * @property isRecordable Whether this tool can appear in trail recordings. Set to false for
 *   wrapper tools that delegate to more precise tools.
 * @property requiresHost Whether this tool requires host-side execution (e.g., ADB commands,
 *   USB hardware access like cbot). Tools with requiresHost=true are excluded from on-device
 *   agents and can only run from a host JVM process.
 * @property isVerification Whether this tool is read-only and self-validates a condition (e.g.,
 *   `assertVisible`, `web_verify_text_visible`). Verification tools never mutate the device, and
 *   their successful execution is the assertion verdict. Used by `blaze(hint=VERIFY)` to decide
 *   whether the LLM-recommended tool may be executed: only verification tools are allowed,
 *   anything else short-circuits to a failed assertion (its success would be unrelated to whether
 *   the assertion holds, and it could side-effect the device).
 * @property trailheadTo Non-empty value marks this tool as a *trailhead* — a deterministic
 *   bootstrap step that lands the device at a known waypoint (e.g. `square/checkout/library`).
 *   Used by the recording UI's trailhead picker to surface the tool as a startable entry point
 *   and by the navigation graph to draw a dashed entry edge into the destination waypoint.
 *   Empty string (the default) means "this is a regular tool, not a trailhead". The same
 *   metadata can also live in a sibling `*.trailhead.yaml` file pairing the class with a `to:`
 *   block — discovery merges both sources, so a class can self-declare here without a
 *   companion YAML, or the YAML can stay authoritative if the trailhead is YAML-only.
 * @property prefersHostSideForCallback Dual-mode composition primitive whose `Success.message`
 *   payload is the contract for scripted-tool authors that compose this tool via
 *   `client.callTool(...)`. The on-device-RPC return path (`RunYamlResponse`) doesn't carry
 *   per-tool `Success.message`, so routing through RPC would silently discard the message a
 *   TS author's handler reads several frames down (`JSON.parse(undefined)` failure mode).
 *   When this is `true`, `HostOnDeviceRpcTrailblazeAgent` short-circuits the dispatch to the
 *   host-side actual (e.g. dadb-backed `AndroidDeviceCommandExecutor`) instead of routing
 *   over RPC. Mutually independent from [requiresHost]: a tool can have an on-device actual
 *   AND a host-side actual, and this flag picks the host one for callback paths only.
 *   Replaces the prior hard-coded allowlist in `HostOnDeviceRpcTrailblazeAgent` so adding a
 *   new dual-mode primitive only requires the annotation, not a parallel edit to the agent.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TrailblazeToolClass(
  val name: String,
  val surfaceToLlm: Boolean = true,
  val surfaceToScriptedTools: Boolean = true,
  val isRecordable: Boolean = true,
  val requiresHost: Boolean = false,
  val isVerification: Boolean = false,
  val trailheadTo: String = "",
  val prefersHostSideForCallback: Boolean = false,
)
