package xyz.block.trailblaze.toolcalls

/**
 * Annotation for Trailblaze tools that defines their execution characteristics.
 *
 * @property name The unique name of the tool used for serialization and LLM selection.
 * @property isForLlm Whether the LLM can select this tool. Set to false for implementation-detail
 *   tools that use unstable identifiers (e.g., node IDs).
 * @property isRecordable Whether this tool can appear in trail recordings. Set to false for
 *   wrapper tools that delegate to more precise tools.
 * @property isDeprecated Whether this tool is being phased out. Deprecated tools are automatically
 *   excluded from LLM selection but can still replay from existing recordings.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TrailblazeToolClass(
  val name: String,
  val isForLlm: Boolean = true,
  val isRecordable: Boolean = true,
)
