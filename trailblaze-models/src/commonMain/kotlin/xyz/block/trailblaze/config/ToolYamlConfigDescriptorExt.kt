package xyz.block.trailblaze.config

import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Builds a [TrailblazeToolDescriptor] from a `tools:` mode [ToolYamlConfig], without reflection.
 *
 * For Kotlin-backed (`class:` mode) tools, the descriptor comes from reflecting the class's
 * constructor parameters + `@LLMDescription` annotations (see `KClass.toKoogToolDescriptor`). For
 * YAML-defined tools there is no class to reflect — the description and parameter shape come
 * straight out of the YAML.
 *
 * Required parameters land in [TrailblazeToolDescriptor.requiredParameters]; non-required ones
 * (defaulted or optional) land in [TrailblazeToolDescriptor.optionalParameters].
 */
fun ToolYamlConfig.toTrailblazeToolDescriptor(): TrailblazeToolDescriptor {
  require(mode == ToolYamlConfig.Mode.TOOLS) {
    "toTrailblazeToolDescriptor() is only valid for 'tools:' mode YAML configs; tool '$id' is in $mode mode."
  }
  val required = parameters.filter { it.required }.map { it.toParameterDescriptor() }
  val optional = parameters.filter { !it.required }.map { it.toParameterDescriptor() }
  return TrailblazeToolDescriptor(
    name = id,
    description = description?.trim()?.takeIf { it.isNotBlank() },
    requiredParameters = required,
    optionalParameters = optional,
  )
}

private fun TrailblazeToolParameterConfig.toParameterDescriptor(): TrailblazeToolParameterDescriptor =
  TrailblazeToolParameterDescriptor(
    name = name,
    type = type,
    description = description?.trim()?.takeIf { it.isNotBlank() },
  )
