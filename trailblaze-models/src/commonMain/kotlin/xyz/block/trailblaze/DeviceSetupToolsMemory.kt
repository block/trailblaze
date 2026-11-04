package xyz.block.trailblaze

/**
 * Where a run's setup tool calls (`trailblaze run --device-setup`, a lane's `execution.deviceSetupTools`) ride
 * into each trail: memory, the one per-trail channel every runner already seeds. The runner reads
 * them once per trail and runs them before the trailhead.
 */
object DeviceSetupToolsMemory {
  /** The setup tool calls, a JSON array of single-key `{toolName: args}` objects. */
  const val DEVICE_SETUP_TOOLS_MEMORY_KEY = "trailblaze.deviceSetupTools"

  /** The setup tool calls the run sent, or null when it sent none. */
  fun read(memory: AgentMemory): String? = memory.variables[DEVICE_SETUP_TOOLS_MEMORY_KEY]?.takeIf { it.isNotBlank() }
}
