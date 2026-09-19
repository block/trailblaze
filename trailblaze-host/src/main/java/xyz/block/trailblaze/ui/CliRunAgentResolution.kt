package xyz.block.trailblaze.ui

import xyz.block.trailblaze.mcp.AgentImplementation

internal sealed interface CliRunAgentResolution {
  data class Resolved(val agentImplementation: AgentImplementation) : CliRunAgentResolution

  data class Unrecognized(val message: String) : CliRunAgentResolution
}

/** Resolves the agent for a daemon-delegated run, preserving the daemon's saved fallback. */
internal fun resolveRunAgentImplementation(
  requestedAgent: String?,
  savedAgent: AgentImplementation?,
): CliRunAgentResolution {
  if (requestedAgent == null) {
    return CliRunAgentResolution.Resolved(savedAgent ?: AgentImplementation.DEFAULT)
  }
  val agentImplementation =
    runCatching { AgentImplementation.valueOf(requestedAgent.uppercase()) }.getOrNull()
      ?: return CliRunAgentResolution.Unrecognized(
        "unknown agent implementation '$requestedAgent'; valid agent implementations: " +
          AgentImplementation.entries.joinToString { it.name },
      )
  return CliRunAgentResolution.Resolved(agentImplementation)
}
