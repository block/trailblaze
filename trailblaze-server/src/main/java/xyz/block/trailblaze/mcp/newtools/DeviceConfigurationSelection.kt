package xyz.block.trailblaze.mcp.newtools

import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig

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
   * The name to decode with, or null for a single-device run. [unboundConfigurations] is what the
   * trail declares but this run leaves unbound — a mixed trail run without companions — so the
   * caller can say why a trail with a configuration ran single-device.
   */
  data class Selected(
    val name: String?,
    val implicit: Boolean,
    val unboundConfigurations: Set<String> = emptySet(),
  ) : DeviceConfigurationSelection

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
 * single option. A trail that ALSO declares ordinary single-device entries binds it implicitly only
 * when [bindsCompanionDevices] — the session has bound devices beyond the one it runs on — and
 * otherwise runs single-device on its classifier legs, the same choice a daemon run makes from its
 * bindings ([UnifiedTrailConfig.implicitMultiDeviceConfigurationName]).
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
  bindsCompanionDevices: Boolean = false,
): DeviceConfigurationSelection {
  val config = unifiedConfigOrNull(yaml, trailblazeYaml)
    ?: return DeviceConfigurationSelection.Selected(name = requested, implicit = false)
  val declared = config.multiDeviceConfigurationNames
  if (requested != null) {
    return if (requested in declared) {
      DeviceConfigurationSelection.Selected(name = requested, implicit = false)
    } else {
      DeviceConfigurationSelection.Undeclared(requested = requested, declared = declared)
    }
  }
  if (config.requiresExplicitMultiDeviceConfiguration(bindsCompanionDevices)) {
    return DeviceConfigurationSelection.Ambiguous(declared)
  }
  val implicit = config.implicitMultiDeviceConfigurationName(bindsCompanionDevices)
  return DeviceConfigurationSelection.Selected(
    name = implicit,
    implicit = implicit != null,
    unboundConfigurations = if (implicit == null) declared else emptySet(),
  )
}

/**
 * The line explaining why a trail that declares a configuration runs single-device, or null when
 * it doesn't. Without it "why didn't my pair run?" has no answer in the output.
 */
internal fun DeviceConfigurationSelection.Selected.singleDeviceFallbackMessage(): String? =
  unboundConfigurations.takeIf { it.isNotEmpty() }?.let { names ->
    "Running single-device: this session has no companion devices bound, so the trail's device " +
      "configuration ${names.sorted()} is not used. Bind its devices with " +
      "device(action=BIND, name=…, deviceId=…) to run it multi-device."
  }

/**
 * Whether [sessionContext] has bound devices beyond the one a run starts on — the MCP counterpart of
 * a daemon run's device bindings, and so what decides whether a mixed trail binds its configuration.
 */
internal fun bindsCompanionDevices(sessionContext: TrailblazeMcpSessionContext?): Boolean =
  sessionContext?.advertisesMultiDeviceTools() == true

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
 * [yaml]'s unified `config:`, or null when it cannot be decoded at all — a distinction the caller
 * needs, because "declares nothing" refuses a requested name while "cannot be read" must defer to
 * the real decode for the parse error.
 */
private fun unifiedConfigOrNull(yaml: String, trailblazeYaml: TrailblazeYaml): UnifiedTrailConfig? =
  runCatching {
    when (val document = trailblazeYaml.decodeTrailDocument(yaml)) {
      is TrailDocument.Unified -> document.trail.config
    }
  }.getOrNull()
