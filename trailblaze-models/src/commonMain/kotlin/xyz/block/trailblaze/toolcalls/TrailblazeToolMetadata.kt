package xyz.block.trailblaze.toolcalls

/**
 * Per-instance metadata override for a [TrailblazeTool], shaped 1:1 with the load-bearing
 * fields of [TrailblazeToolClass].
 *
 * Class-annotated tools don't need this — their [TrailblazeToolClass] annotation is read
 * via reflection by the resolver helpers. The hook exists so tools whose backing class is
 * shared across many instances (today: [xyz.block.trailblaze.config.YamlDefinedTrailblazeTool],
 * which has *one* `@TrailblazeToolClass` for the shared class but *N* per-instance configs)
 * can still report differing metadata per instance.
 *
 * ## Null semantics
 *
 * `null` on any field means "fall through to the class annotation default" — same null
 * semantics as [xyz.block.trailblaze.config.ToolYamlConfig.isForLlm] etc. The resolver
 * helpers ([getIsRecordableFromAnnotation], [requiresHostInstance]) treat them that way:
 * each field is consulted with `?.let { return it }` and only short-circuits when non-null.
 *
 * As a corollary, a `TrailblazeToolMetadata(null, null, null)` returned from
 * [TrailblazeTool.toolMetadata] is observationally equivalent to returning `null` from the
 * hook — both fall straight through to the class annotation. Implementations don't need
 * to distinguish between "no override" and "explicitly all-default" — pick whichever is
 * more natural for the source.
 */
data class TrailblazeToolMetadata(
  val isForLlm: Boolean? = null,
  val isRecordable: Boolean? = null,
  val requiresHost: Boolean? = null,
  val isVerification: Boolean? = null,
)
