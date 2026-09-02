package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool

/**
 * Renders the session-scoped context an LLM needs to address a multi-device cast.
 *
 * Read this once per prompt step, never once per session: [SessionDeviceBindings.activeName]
 * changes when a recorded or AI-issued `switchDevice` handover runs. Within the same step the
 * model already knows it issued the switch; the next step's prompt reflects the new active device.
 *
 * [handoverToolAdvertised] governs whether the section states the handover contract. It must be
 * whether `switchDevice` is really in this session's advertised surface, not whether the session
 * bound devices: the two normally agree, but a target's `excluded_tools:` can drop the tool from a
 * session that bound a cast, and describing a handover the model cannot perform reads as a
 * capability it will try to use and fail. The roster alone is still worth rendering there — it
 * explains what the devices in recorded steps are.
 *
 * A device bound without probed info simply contributes no classifier detail. The name, role and
 * target are what an LLM picks a handover destination by; classifiers only ever narrowed the
 * description, so their absence costs the model nothing it needs.
 */
fun SessionDeviceBindings.renderMultiDevicePromptSection(
  handoverToolAdvertised: Boolean,
): String = buildString {
  appendLine("## Multi-device session")
  appendLine()
  appendLine("This session controls these named devices:")
  names.forEach { name ->
    val bound = requireNotNull(deviceFor(name))
    append("- `")
    append(name)
    append('`')
    val details = buildList {
      bound.description?.takeIf(String::isNotBlank)?.let(::add)
      bound.trailblazeDeviceInfo?.classifiers
        ?.takeIf(List<*>::isNotEmpty)
        ?.joinToString(prefix = "classifiers: ", separator = ", ")
        ?.let(::add)
      bound.targetId?.takeIf(String::isNotBlank)?.let { add("target: $it") }
    }
    if (details.isNotEmpty()) append(": ${details.joinToString("; ")}")
    appendLine()
  }
  appendLine()
  append("The currently active device is `$activeName`. Every screen observation and tool call acts on it.")
  if (handoverToolAdvertised) {
    appendLine()
    appendLine()
    appendLine(
      "Call `${SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME}` with the `name:` of another " +
        "device above to hand the session over to it. Everything after the handover — screen " +
        "observations included — acts on that device until the next handover.",
    )
    append(
      "You only ever see the active device's screen, so switch before acting on another device, " +
        "and issue the switch as its own step rather than batching actions for two devices together.",
    )
  }
}
