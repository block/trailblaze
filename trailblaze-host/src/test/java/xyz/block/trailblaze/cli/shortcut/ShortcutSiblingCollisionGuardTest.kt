package xyz.block.trailblaze.cli.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the set-membership shortcut collision guard. Confirms:
 *  1. Self-edges (from == to) are rejected as "not a useful shortcut."
 *  2. Pre-existing shortcuts in the pack take precedence over auto-proposed duplicates.
 *  3. Two new proposals sharing a (from, to) → higher-support wins, lower dropped.
 *  4. Empty existing-shortcut set + non-colliding proposals → all survive.
 */
class ShortcutSiblingCollisionGuardTest {

  @Test
  fun `self-edges are rejected`() {
    val p = proposal("pack/foo", "pack/foo", supportSessions = 5, key = "k1")
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(p),
      existingShortcuts = emptyList(),
    )
    assertTrue(verdict.survived.isEmpty())
    assertEquals(1, verdict.rejections.size)
    assertTrue(verdict.rejections.first().reason.contains("self-edge"))
  }

  @Test
  fun `pre-existing shortcut blocks new proposal on same (from,to)`() {
    val p = proposal("pack/from", "pack/to", supportSessions = 8, key = "k1")
    val existing = ShortcutSiblingCollisionGuard.ExistingShortcut(
      from = "pack/from", to = "pack/to", variant = null,
    )
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(p),
      existingShortcuts = listOf(existing),
    )
    assertTrue(verdict.survived.isEmpty())
    assertEquals(1, verdict.rejections.size)
    assertTrue(verdict.rejections.first().reason.contains("pre-existing"))
  }

  @Test
  fun `cross-proposal collision keeps higher-support proposal`() {
    val winner = proposal("pack/from", "pack/to", supportSessions = 10, key = "k-winner")
    val loser = proposal("pack/from", "pack/to", supportSessions = 4, key = "k-loser")
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(loser, winner), // intentionally reverse-ordered
      existingShortcuts = emptyList(),
    )
    assertEquals(1, verdict.survived.size)
    assertEquals("k-winner", verdict.survived.first().proposalKey)
    assertEquals(1, verdict.rejections.size)
    assertEquals("k-loser", verdict.rejections.first().proposal.proposalKey)
    assertTrue(verdict.rejections.first().reason.contains("cross-proposal"))
  }

  @Test
  fun `cross-proposal ties broken by proposalKey for determinism`() {
    val a = proposal("pack/from", "pack/to", supportSessions = 5, key = "a")
    val b = proposal("pack/from", "pack/to", supportSessions = 5, key = "b")
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(b, a),
      existingShortcuts = emptyList(),
    )
    assertEquals(1, verdict.survived.size)
    // Lexicographically lower key wins on tie.
    assertEquals("a", verdict.survived.first().proposalKey)
  }

  @Test
  fun `non-colliding proposals all survive`() {
    val p1 = proposal("pack/a", "pack/b", supportSessions = 5, key = "k1")
    val p2 = proposal("pack/c", "pack/d", supportSessions = 6, key = "k2")
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(p1, p2),
      existingShortcuts = emptyList(),
    )
    assertEquals(2, verdict.survived.size)
    assertTrue(verdict.rejections.isEmpty())
    // Higher-support first (descending order is part of the contract).
    assertEquals("k2", verdict.survived[0].proposalKey)
    assertEquals("k1", verdict.survived[1].proposalKey)
  }

  @Test
  fun `guard drops proposal when existingShortcuts contains the same (from,to,null)`() {
    // Documents the contract: if the existing set ALREADY lists (from, to, variant=null),
    // the proposal collides and is rejected. This is the same path as the basic
    // pre-existing test but explicitly pins behavior for the "shortcut already exists in
    // the pack from a prior auto-PR that landed" scenario, distinguishing it from
    // cross-proposal collisions inside the same run.
    val p = proposal("pack/from", "pack/to", supportSessions = 5, key = "k1")
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(p),
      existingShortcuts = listOf(
        ShortcutSiblingCollisionGuard.ExistingShortcut(
          from = "pack/from", to = "pack/to", variant = null,
        ),
      ),
    )
    assertTrue(verdict.survived.isEmpty())
    assertEquals(1, verdict.rejections.size)
    assertTrue(verdict.rejections.first().reason.contains("pre-existing"))
  }

  @Test
  fun `existing variant non-null does not block variant-null proposal`() {
    // An existing shortcut with variant="manual" is distinct from a new variant=null
    // proposal — the runtime can disambiguate by variant, so we let the new one through.
    val p = proposal("pack/from", "pack/to", supportSessions = 5, key = "k1")
    val existing = ShortcutSiblingCollisionGuard.ExistingShortcut(
      from = "pack/from", to = "pack/to", variant = "manual",
    )
    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = listOf(p),
      existingShortcuts = listOf(existing),
    )
    assertEquals(1, verdict.survived.size, "variant=null proposal coexists with variant=manual existing")
    assertTrue(verdict.rejections.isEmpty())
  }

  // ---------------- fixtures ----------------

  private fun proposal(
    from: String,
    to: String,
    supportSessions: Int,
    key: String,
  ): ShortcutProposer.Proposal = ShortcutProposer.Proposal(
    fromWaypointId = from,
    toWaypointId = to,
    toolBody = ShortcutProposer.ToolBody.PressBack,
    supportSessions = supportSessions,
    supportSteps = supportSessions,
    actionFingerprint = "fp-$key",
    proposalKey = key,
    rationale = "test fixture",
  )
}
