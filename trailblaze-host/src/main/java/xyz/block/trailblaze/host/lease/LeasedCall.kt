package xyz.block.trailblaze.host.lease

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.logs.client.SessionSecrets
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.sensitiveLiteralValues

/**
 * What a borrower's call brings with it to the lending machine: the borrower's memory, as the tool
 * runs against it, and the secrets the borrower's session has handled. The lender's dispatch reads
 * the call it is running ([current]) so the tool sees that memory, its writes go back to the
 * borrower, and nothing this machine logs for the call repeats a secret.
 */
class LeasedCall(
  val memory: AgentMemory,
  private val secrets: Collection<String>,
) {
  /** Masks the borrower's secrets in everything written for [sessionId], the session the call runs in. */
  fun registerSecrets(sessionId: SessionId) {
    SessionSecrets.register(sessionId, secrets + sensitiveLiteralValues(memory))
  }

  /** The memory to seed a run with that has no memory of its own to share: plain values, then sensitive ones. */
  fun memorySeeds(): Pair<Map<String, String>, Map<String, String>> =
    memory.variables.toMap().entries.partition { it.key !in memory.sensitiveKeys }
      .let { (plain, sensitive) -> plain.associate { it.key to it.value } to sensitive.associate { it.key to it.value } }

  companion object {
    private val active = ThreadLocal<LeasedCall?>()

    /** The borrower's call this coroutine is running, if any. */
    fun current(): LeasedCall? = active.get()

    suspend fun <T> running(call: LeasedCall, block: suspend () -> T): T =
      withContext(active.asContextElement(call)) { block() }
  }
}
