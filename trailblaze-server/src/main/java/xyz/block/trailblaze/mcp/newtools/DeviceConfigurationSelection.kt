package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * Which of a trail's `config.devices:` CONFIGURATION entries a run binds — a named entry with an
 * inner `devices:` map, e.g. a paired seller/buyer setup.
 *
 * Selection is the ONLY way a configuration's recording legs resolve: `UnifiedTrailAdapter` matches
 * the selected name exactly, ahead of the device's classifier chain, because configuration names are
 * invisible to classifier lineage. Decode a two-device trail without it and every configuration-keyed
 * step lowers with no recording — which the deterministic MCP executor, having no LLM to fall back
 * on, reports as "No recording for this step" on a trail that is fully recorded.
 */
internal sealed interface DeviceConfigurationSelection {
  /**
   * The name to decode with, or null for a trail that declares no configuration (an ordinary
   * single-device trail — the default, and unchanged).
   */
  data class Selected(val name: String?, val implicit: Boolean) : DeviceConfigurationSelection

  /** The caller named a configuration the trail does not declare. */
  data class Undeclared(val requested: String, val declared: Set<String>) : DeviceConfigurationSelection

  /** More than one configuration is declared and the caller named none. */
  data class Ambiguous(val declared: Set<String>) : DeviceConfigurationSelection
}

/**
 * Resolves the configuration to decode [yaml] with.
 *
 * A caller's explicit name wins. With none, a trail declaring exactly one configuration binds it
 * implicitly — an agent that says `trail(action=RUN, name="pos-pair-refund")` means the one pairing
 * that trail describes, and making it name the configuration too is ceremony over a choice with a
 * single option.
 *
 * Ambiguity is refused rather than guessed. Picking the first of several would replay a different
 * device set than the caller has bound, and the failure would surface as unrelated steps failing on
 * the wrong screen.
 *
 * An undecodable trail passes the caller's choice straight through: the decode that follows raises
 * the real parse error, so this never turns a malformed-YAML report into a configuration one.
 */
internal fun selectDeviceConfiguration(
  yaml: String,
  requested: String?,
  trailblazeYaml: TrailblazeYaml = TrailblazeYaml.Default,
): DeviceConfigurationSelection {
  val declared = declaredConfigurationNames(yaml, trailblazeYaml)
    ?: return DeviceConfigurationSelection.Selected(name = requested, implicit = false)
  if (requested != null) {
    return if (requested in declared) {
      DeviceConfigurationSelection.Selected(name = requested, implicit = false)
    } else {
      DeviceConfigurationSelection.Undeclared(requested = requested, declared = declared)
    }
  }
  return when (declared.size) {
    0 -> DeviceConfigurationSelection.Selected(name = null, implicit = false)
    1 -> DeviceConfigurationSelection.Selected(name = declared.first(), implicit = true)
    else -> DeviceConfigurationSelection.Ambiguous(declared)
  }
}

/** The message an MCP caller sees for a selection that can't proceed, or null when it can. */
internal fun DeviceConfigurationSelection.errorMessage(): String? = when (this) {
  is DeviceConfigurationSelection.Selected -> null

  is DeviceConfigurationSelection.Undeclared -> if (declared.isEmpty()) {
    "This trail declares no multi-device configuration, so it cannot bind '$requested'. " +
      "Run it without deviceConfiguration."
  } else {
    "This trail declares no configuration named '$requested'. Declared: ${declared.sorted().joinToString()}."
  }

  is DeviceConfigurationSelection.Ambiguous ->
    "This trail declares more than one multi-device configuration " +
      "(${declared.sorted().joinToString()}) — name the one to run, e.g. " +
      "trail(action=RUN, deviceConfiguration=\"${declared.sorted().first()}\"). " +
      "Running without one would replay a different device set than the session bound."
}

/**
 * The configuration names [yaml] declares, or null when it cannot be decoded at all — a distinction
 * the caller needs, because "declares nothing" refuses a requested name while "cannot be read" must
 * defer to the real decode for the parse error.
 */
private fun declaredConfigurationNames(yaml: String, trailblazeYaml: TrailblazeYaml): Set<String>? =
  runCatching {
    when (val document = trailblazeYaml.decodeTrailDocument(yaml)) {
      is TrailDocument.Unified -> document.trail.config.multiDeviceConfigurationNames
    }
  }.getOrNull()
