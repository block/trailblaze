package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The permission state is the daemon-side truth of which spawned-CLI tool calls a human has decided
 * on. These pin its observable contract - what a resolution hands back to the awaiting caller and
 * which tools become pre-approved - without a live daemon, proxy, or CLI.
 */
class ExternalAgentPermissionsTest {

  @Test
  fun registeringSurfacesThePendingRequestAndAwaitingBlocksUntilResolved() {
    val state = ExternalAgentPermissionState()
    val req = state.register(toolName = "tap", inputJson = """{"x":1}""", toolUseId = "tu-1")

    val snapshot = state.pendingSnapshot()
    assertEquals(1, snapshot.size)
    assertEquals("tap", snapshot.single().toolName)
    assertEquals(req.id, snapshot.single().id)
    assertFalse(req.deferred.isCompleted)
  }

  @Test
  fun allowResolvesWithThePassThroughInputAndClearsThePending() {
    val state = ExternalAgentPermissionState()
    val req = state.register(toolName = "tap", inputJson = """{"x":1}""", toolUseId = null)

    val resolved = state.resolve(req.id, "allow")

    assertEquals(PermissionOutcome.ALLOW, resolved?.outcome)
    val decision = runBlocking { req.deferred.await() }
    assertTrue(decision is PermissionDecision.Allow)
    assertEquals("""{"x":1}""", decision.updatedInputJson)
    assertTrue(state.pendingSnapshot().isEmpty())
  }

  @Test
  fun denyResolvesWithADenyDecision() {
    val state = ExternalAgentPermissionState()
    val req = state.register(toolName = "tap", inputJson = null, toolUseId = null)

    val resolved = state.resolve(req.id, "deny")

    assertEquals(PermissionOutcome.DENY, resolved?.outcome)
    assertTrue(runBlocking { req.deferred.await() } is PermissionDecision.Deny)
  }

  @Test
  fun allowAlwaysPreApprovesThatToolForLaterRequests() {
    val state = ExternalAgentPermissionState()
    assertFalse(state.isPreApproved("tap"))
    val req = state.register(toolName = "tap", inputJson = null, toolUseId = null)

    val resolved = state.resolve(req.id, "allow_always")

    assertEquals(PermissionOutcome.ALLOW_ALWAYS, resolved?.outcome)
    assertTrue(state.isPreApproved("tap"))
    // A different tool is still gated.
    assertFalse(state.isPreApproved("inputText"))
  }

  @Test
  fun resolvingAnUnknownIdReturnsNull() {
    val state = ExternalAgentPermissionState()
    assertNull(state.resolve("perm-missing", "allow"))
  }

  @Test
  fun enablingAutoApprovePreApprovesEverythingAndAllowsWhatWasPending() {
    val state = ExternalAgentPermissionState()
    val req = state.register(toolName = "tap", inputJson = """{"x":1}""", toolUseId = null)

    val drained = state.setAutoApprove(true)

    assertTrue(state.autoApprove)
    assertTrue(state.isPreApproved("anything"))
    assertEquals(1, drained.size)
    assertEquals(PermissionOutcome.AUTO_ALLOW, drained.single().outcome)
    val decision = runBlocking { req.deferred.await() }
    assertEquals("""{"x":1}""", (decision as PermissionDecision.Allow).updatedInputJson)
    assertTrue(state.pendingSnapshot().isEmpty())
  }

  @Test
  fun disablingAutoApproveDrainsNothingAndStopsPreApproving() {
    val state = ExternalAgentPermissionState()
    state.setAutoApprove(true)

    val drained = state.setAutoApprove(false)

    assertTrue(drained.isEmpty())
    assertFalse(state.autoApprove)
    assertFalse(state.isPreApproved("tap"))
  }

  @Test
  fun failAllPendingDeniesEveryOutstandingRequestWithTheGivenMessage() {
    val state = ExternalAgentPermissionState()
    val a = state.register(toolName = "tap", inputJson = null, toolUseId = null)
    val b = state.register(toolName = "inputText", inputJson = null, toolUseId = null)

    val drained = state.failAllPending("the run ended before this was approved")

    assertEquals(2, drained.size)
    assertTrue(drained.all { it.outcome == PermissionOutcome.RUN_ENDED })
    assertTrue(runBlocking { a.deferred.await() } is PermissionDecision.Deny)
    assertTrue(runBlocking { b.deferred.await() } is PermissionDecision.Deny)
    assertTrue(state.pendingSnapshot().isEmpty())
  }

  @Test
  fun pendingSnapshotIsOrderedOldestFirst() {
    val state = ExternalAgentPermissionState()
    val first = state.register(toolName = "tap", inputJson = null, toolUseId = null)
    Thread.sleep(2)
    val second = state.register(toolName = "inputText", inputJson = null, toolUseId = null)

    assertEquals(listOf(first.id, second.id), state.pendingSnapshot().map { it.id })
  }
}
