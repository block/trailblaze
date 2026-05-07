package xyz.block.trailblaze.toolcalls

/**
 * Annotation for Trailblaze tools that defines their execution characteristics.
 *
 * @property name The unique name of the tool used for serialization and LLM selection.
 * @property isForLlm Whether the LLM can select this tool. Set to false for implementation-detail
 *   tools that use unstable identifiers (e.g., node IDs).
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
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TrailblazeToolClass(
  val name: String,
  val isForLlm: Boolean = true,
  val isRecordable: Boolean = true,
  val requiresHost: Boolean = false,
  val isVerification: Boolean = false,
  val trailheadTo: String = "",
)
