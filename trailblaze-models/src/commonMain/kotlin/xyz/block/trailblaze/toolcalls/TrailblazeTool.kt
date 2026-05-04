package xyz.block.trailblaze.toolcalls

import kotlin.jvm.JvmInline

/**
 * A marker interface for all Trailblaze commands.
 *
 * All Trailblaze commands should implement this interface and be @[kotlinx.serialization.Serializable].
 *
 * Implementations may override [toolMetadata] to expose per-instance metadata that overrides
 * the class-level [TrailblazeToolClass] annotation. The default is `null`, meaning the
 * annotation is the source of truth — almost every implementation takes this path.
 * `YamlDefinedTrailblazeTool` is the one current exception: it shares a class with all other
 * YAML-defined tools, so it overrides this hook to surface its per-config flags.
 */
interface TrailblazeTool {
  /**
   * Per-instance metadata override. Defaults to `null` so class-annotated tools are
   * unaffected. See [TrailblazeToolMetadata] for the semantics of each field.
   */
  val toolMetadata: TrailblazeToolMetadata? get() = null
}

@JvmInline
value class ToolName(val toolName: String)
