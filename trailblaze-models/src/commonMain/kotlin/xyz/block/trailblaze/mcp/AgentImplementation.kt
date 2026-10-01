package xyz.block.trailblaze.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * The agent that ran a session, as recorded in session logs and progress events.
 *
 * There is one agent, so this is a label rather than a choice. It stays a type because session
 * logs and the on-device RPC wire format carry it, and older logs name agents that have since
 * been removed.
 */
@Serializable
enum class AgentImplementation {
  /**
   * The Koog `strategy { }` graph that owns the agent reasoning loop, with Trailblaze owning the
   * domain (screen state, drivers, deterministic replay).
   *
   * Also decodes the removed `MULTI_AGENT_V3` and `TRAILBLAZE_RUNNER` values, so logs and
   * payloads written before their removal still load.
   */
  @OptIn(ExperimentalSerializationApi::class)
  @JsonNames("MULTI_AGENT_V3", "TRAILBLAZE_RUNNER")
  KOOG_STRATEGY_GRAPH,
  ;

  companion object {
    /**
     * Decodes a wire name, mapping any name this build no longer knows (a removed agent, or an
     * empty field from a sender that omitted it) to [KOOG_STRATEGY_GRAPH]. The on-device RPC
     * carries the name as a plain string, so a device and host built at different versions can
     * disagree about which names exist.
     */
    fun fromWireName(name: String?): AgentImplementation =
      entries.firstOrNull { it.name == name } ?: KOOG_STRATEGY_GRAPH
  }
}
