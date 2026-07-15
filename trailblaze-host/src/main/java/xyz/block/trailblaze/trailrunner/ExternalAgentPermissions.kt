package xyz.block.trailblaze.trailrunner

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Human-approvable permissions for a spawned CLI run.
 *
 * A spawned Claude CLI that hits a tool it can't auto-approve routes the request through Trail
 * Runner (via the MCP proxy's `approval_prompt` interception) instead of dead-ending on "requires
 * approval". This holder owns the per-run permission STATE: which requests are pending a human
 * decision, which tools the human said to always allow, and whether auto-approve is on. The
 * supervisor drives it and emits the transcript events; the state itself is pure enough to unit-test
 * on its own.
 *
 * It lives OUTSIDE the per-turn reset ([MutableExternalAgentRun.beginTurn]) so an `allow_always`
 * grant or an auto-approve toggle persists for the whole run, across resumed conversation turns.
 */
internal class ExternalAgentPermissionState {
  private val pending = ConcurrentHashMap<String, PendingPermissionRequest>()
  private val alwaysAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

  @Volatile private var autoApproveFlag: Boolean = false

  /** Whether every request is auto-allowed without asking the human. */
  val autoApprove: Boolean get() = autoApproveFlag

  /** The requests still waiting on a human decision, oldest first (for the run DTO). */
  fun pendingSnapshot(): List<ExternalAgentPermissionRequestDto> =
    pending.values.sortedBy { it.requestedAtMs }.map { it.dto() }

  /** True when [toolName] should be allowed without asking (auto-approve on, or previously allow_always'd). */
  fun isPreApproved(toolName: String): Boolean = autoApproveFlag || alwaysAllowed.contains(toolName)

  /** Registers a new pending request; the caller awaits [PendingPermissionRequest.deferred]. */
  fun register(toolName: String, inputJson: String?, toolUseId: String?): PendingPermissionRequest {
    val req = PendingPermissionRequest(
      id = "perm-" + UUID.randomUUID().toString().take(8),
      toolName = toolName,
      inputJson = inputJson,
      toolUseId = toolUseId,
      requestedAtMs = System.currentTimeMillis(),
    )
    pending[req.id] = req
    return req
  }

  /**
   * Resolves a pending request by id. Returns the resolved request (for event emission) or null when
   * the id is unknown / already resolved. `allow_always` also adds the tool to the always-allowed
   * set, so its next request short-circuits with no pending entry.
   */
  fun resolve(requestId: String, decision: String): ResolvedPermission? {
    val req = pending.remove(requestId) ?: return null
    val outcome = when (decision) {
      "allow" -> PermissionOutcome.ALLOW
      "allow_always" -> {
        alwaysAllowed.add(req.toolName)
        PermissionOutcome.ALLOW_ALWAYS
      }
      else -> PermissionOutcome.DENY
    }
    req.deferred.complete(outcome.toDecision(req.inputJson))
    return ResolvedPermission(req, outcome)
  }

  /**
   * Turns auto-approve on or off. Enabling immediately allows everything currently pending (the
   * human said "stop asking"), returning those resolutions so the caller can emit decision events.
   */
  fun setAutoApprove(enabled: Boolean): List<ResolvedPermission> {
    autoApproveFlag = enabled
    if (!enabled) return emptyList()
    return drainPending(PermissionOutcome.AUTO_ALLOW)
  }

  /** Denies every pending request (the run ended before the human decided). */
  fun failAllPending(message: String): List<ResolvedPermission> = drainPending(PermissionOutcome.RUN_ENDED, message)

  private fun drainPending(outcome: PermissionOutcome, message: String? = null): List<ResolvedPermission> =
    pending.keys.toList().mapNotNull { id ->
      val req = pending.remove(id) ?: return@mapNotNull null
      req.deferred.complete(
        if (outcome == PermissionOutcome.RUN_ENDED) {
          PermissionDecision.Deny(message ?: "The run ended before this was approved")
        } else {
          PermissionDecision.Allow(req.inputJson)
        },
      )
      ResolvedPermission(req, outcome)
    }
}

/** A request awaiting a human decision; [deferred] completes when it is resolved (or the run ends). */
internal class PendingPermissionRequest(
  val id: String,
  val toolName: String,
  val inputJson: String?,
  val toolUseId: String?,
  val requestedAtMs: Long,
) {
  val deferred = CompletableDeferred<PermissionDecision>()

  fun dto(): ExternalAgentPermissionRequestDto =
    ExternalAgentPermissionRequestDto(id = id, toolName = toolName, inputJson = inputJson, requestedAtMs = requestedAtMs)
}

/** The resolution handed back to the MCP proxy. */
internal sealed interface PermissionDecision {
  /** Allow the tool call; [updatedInputJson] is the input the tool should run with (pass-through). */
  data class Allow(val updatedInputJson: String?) : PermissionDecision

  /** Deny the tool call with a human-facing [message]. */
  data class Deny(val message: String) : PermissionDecision
}

/** How a pending request was resolved (drives the PERMISSION_DECISION transcript event's text). */
internal enum class PermissionOutcome {
  ALLOW,
  ALLOW_ALWAYS,
  DENY,
  AUTO_ALLOW,
  RUN_ENDED,
  ;

  fun toDecision(inputJson: String?): PermissionDecision = when (this) {
    DENY -> PermissionDecision.Deny("Denied by the human in Trail Runner")
    RUN_ENDED -> PermissionDecision.Deny("The run ended before this was approved")
    else -> PermissionDecision.Allow(inputJson)
  }
}

internal data class ResolvedPermission(val request: PendingPermissionRequest, val outcome: PermissionOutcome)
