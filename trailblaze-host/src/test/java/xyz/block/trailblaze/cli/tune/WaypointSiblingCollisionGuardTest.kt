package xyz.block.trailblaze.cli.tune

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import java.io.File

/**
 * The sibling-collision guard is the load-bearing safety net for both `waypoint tune`
 * (refinement, via [WaypointSiblingCollisionGuard.check]) and `waypoint propose`
 * (detection, via [WaypointSiblingCollisionGuard.checkOverlap] with
 * `definitionBefore = null`). These tests pin the contractual paths for both modes.
 *
 * **Refinement path** (`check(proposal, ...)`):
 *  1. Proposal that newly matches a step with **no sibling** overlap → safe.
 *  2. Proposal that newly matches a step a **sibling already matches** → collision
 *     flagged.
 *  3. Proposal whose `definitionBefore` already matched the step → not counted as
 *     newly-matched, no false-positive collision.
 *  4. Sibling list containing the proposal's own waypoint → guard self-filters.
 *
 * **Detection path** (`checkOverlap(definitionBefore = null, ...)`):
 *  5. New-waypoint definition whose screen a sibling **also matches** → collision.
 *  6. New-waypoint definition whose screen no sibling matches → safe.
 *  7. Siblings list containing the new waypoint itself → guard self-filters.
 */
class WaypointSiblingCollisionGuardTest {

  // (1) Safe path: A loosens to match step S; no other waypoint matches S.
  @Test
  fun `proposal newly matches step that no sibling matches - safe`() {
    val def = waypoint("myapp/home", required = listOf(textEntry("StaleHeading")))
    val mutated = waypoint("myapp/home", required = emptyList())
    val proposal = dropProposal(File("home.waypoint.yaml"), def, mutated, def.required.single())
    val sibling = waypoint("myapp/settings", required = listOf(textEntry("Settings")))
    val step = sessionStep("s1", "s1/a", screenStateOf("Home Header"))

    val verdict = WaypointSiblingCollisionGuard.check(
      proposal = proposal,
      siblings = listOf(sibling),
      sessions = listOf(step),
    )

    assertTrue(verdict.safe, "no sibling matches the newly-matched screen; should be safe")
    assertEquals(1, verdict.newlyMatchedStepIds.size, "the proposal newly matched one step")
  }

  // (2) Collision path: A loosens, newly matches step S, sibling B already matches S.
  @Test
  fun `proposal newly matches step that sibling already matches - collision`() {
    val def = waypoint("myapp/home", required = listOf(textEntry("StaleHeading")))
    val mutated = waypoint("myapp/home", required = emptyList())
    val proposal = dropProposal(File("home.waypoint.yaml"), def, mutated, def.required.single())
    // Sibling matches any screen with the heading "Home Header" — and our test screen
    // does carry that heading.
    val sibling = waypoint("myapp/home-also", required = listOf(textEntry("Home Header")))
    val step = sessionStep("s1", "s1/a", screenStateOf("Home Header"))

    val verdict = WaypointSiblingCollisionGuard.check(
      proposal = proposal,
      siblings = listOf(sibling),
      sessions = listOf(step),
    )

    assertFalse(verdict.safe, "sibling matches the newly-matched screen; should collide")
    assertEquals(1, verdict.collidingSteps.size)
    assertEquals("myapp/home-also", verdict.collidingSteps.single().siblingWaypointId)
  }

  // (3) No newly-matched: definitionBefore already matched the step → guard reports zero
  //     newly-matched and no collision, even if a sibling also matches.
  @Test
  fun `proposal whose definitionBefore already matched the step is not counted as newly-matched`() {
    val sharedReq = textEntry("Home Header")
    val def = waypoint("myapp/home", required = listOf(sharedReq))
    val mutated = waypoint("myapp/home", required = listOf(sharedReq), forbidden = emptyList())
    // No-op proposal: before matches, after matches. The guard must not treat this as a
    // collision — without the "before-then-after" gate, every sibling-overlap on
    // existing-match screens would explode into spurious collisions.
    val proposal = dropProposal(File("home.waypoint.yaml"), def, mutated, sharedReq)
    val sibling = waypoint("myapp/home-also", required = listOf(textEntry("Home Header")))
    val step = sessionStep("s1", "s1/a", screenStateOf("Home Header"))

    val verdict = WaypointSiblingCollisionGuard.check(
      proposal = proposal,
      siblings = listOf(sibling),
      sessions = listOf(step),
    )

    assertTrue(
      verdict.safe,
      "definitionBefore already matched; sibling overlap on existing-match screens isn't a collision",
    )
    assertTrue(verdict.newlyMatchedStepIds.isEmpty())
  }

  // (4) Self-filter contract: even if the caller forgets to exclude the proposal's own
  //     waypoint from `siblings`, the guard internally skips siblings with `sibling.id ==
  //     proposal.waypointId`. Pin that defensive behavior so a future refactor doesn't
  //     turn a caller bug into a silent false-positive collision.
  @Test
  fun `guard internally filters siblings matching the proposals own waypoint`() {
    val def = waypoint("myapp/home", required = listOf(textEntry("StaleHeading")))
    val mutated = waypoint("myapp/home", required = emptyList())
    val proposal = dropProposal(File("home.waypoint.yaml"), def, mutated, def.required.single())
    val step = sessionStep("s1", "s1/a", screenStateOf("Home Header"))

    // Pass the proposal's own definitionAfter as a "sibling" — same id. Without the
    // guard's internal id-filter this would look like a self-collision.
    val verdict = WaypointSiblingCollisionGuard.check(
      proposal = proposal,
      siblings = listOf(mutated),
      sessions = listOf(step),
    )

    assertTrue(
      verdict.safe,
      "guard must self-filter siblings with the same id as the proposal's waypoint",
    )
  }

  // ---------------- checkOverlap with definitionBefore = null (detection path) ----------------

  // The detection pipeline (#3095) calls `checkOverlap(definitionBefore = null, ...)` —
  // a brand-new waypoint has no prior definition to diff, so "newly-matched" collapses
  // to "matched by definitionAfter at all." The empty-def trick fails here (empty def
  // matches every screen, so the diff path's skip-clause silently no-ops the bleed
  // check), which is why the null branch exists. Pin the contract.

  @Test
  fun `checkOverlap with null before flags collision when sibling also matches the new waypoint screen`() {
    val newDef = waypoint("myapp/new", required = listOf(textEntry("Accept")))
    val sibling = waypoint("myapp/home", required = listOf(textEntry("Accept")))
    val step = sessionStep("s1", "s1/a", screenStateOf("Accept"))

    val verdict = WaypointSiblingCollisionGuard.checkOverlap(
      waypointId = "myapp/new",
      definitionBefore = null,
      definitionAfter = newDef,
      siblings = listOf(sibling),
      sessions = listOf(step),
    )

    assertFalse(verdict.safe, "sibling matches the same screen — should collide")
    assertEquals(1, verdict.newlyMatchedStepIds.size)
    assertEquals(1, verdict.collidingSteps.size)
    assertEquals("myapp/home", verdict.collidingSteps.single().siblingWaypointId)
  }

  @Test
  fun `checkOverlap with null before is safe when no sibling matches the proposal's screen`() {
    val newDef = waypoint("myapp/new", required = listOf(textEntry("Accept")))
    val sibling = waypoint("myapp/home", required = listOf(textEntry("Reject")))
    val step = sessionStep("s1", "s1/a", screenStateOf("Accept"))

    val verdict = WaypointSiblingCollisionGuard.checkOverlap(
      waypointId = "myapp/new",
      definitionBefore = null,
      definitionAfter = newDef,
      siblings = listOf(sibling),
      sessions = listOf(step),
    )

    assertTrue(verdict.safe)
    assertEquals(1, verdict.newlyMatchedStepIds.size, "step matched by proposal counted")
  }

  @Test
  fun `checkOverlap with null before self-filters when siblings list includes the new waypoint`() {
    // Caller passing the full pack (incl. the new waypoint) must not produce a self-
    // collision pseudo-failure. The id-filter inside the loop is the contract.
    val newDef = waypoint("myapp/new", required = listOf(textEntry("Accept")))
    val step = sessionStep("s1", "s1/a", screenStateOf("Accept"))

    val verdict = WaypointSiblingCollisionGuard.checkOverlap(
      waypointId = "myapp/new",
      definitionBefore = null,
      definitionAfter = newDef,
      siblings = listOf(newDef),
      sessions = listOf(step),
    )

    assertTrue(verdict.safe, "same-id sibling must be filtered out by the guard")
  }

  // ---------------- fixtures ----------------

  private fun waypoint(
    id: String,
    required: List<WaypointSelectorEntry> = emptyList(),
    forbidden: List<WaypointSelectorEntry> = emptyList(),
  ): WaypointDefinition = WaypointDefinition(id = id, required = required, forbidden = forbidden)

  private fun textEntry(text: String, minCount: Int = 1): WaypointSelectorEntry =
    WaypointSelectorEntry(
      selector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^$text$"),
      ),
      minCount = minCount,
    )

  private fun dropProposal(
    file: File,
    before: WaypointDefinition,
    after: WaypointDefinition,
    affected: WaypointSelectorEntry,
  ): WaypointTuner.Proposal = WaypointTuner.Proposal(
    waypointId = before.id,
    kind = WaypointTuner.ProposalKind.DROP_REQUIRED,
    rationale = "test",
    supportSessions = 1,
    supportSteps = 1,
    sourceFile = file,
    definitionBefore = before,
    definitionAfter = after,
    affectedEntry = affected,
  )

  private fun sessionStep(sessionId: String, stepId: String, screen: ScreenState) =
    WaypointSiblingCollisionGuard.SessionStep(sessionId, stepId, screen)

  /**
   * Minimal `ScreenState` whose tree contains a single text node. Only the
   * `trailblazeNodeTree` is consulted by `WaypointMatcher`; the rest are stubbed to
   * satisfy the interface's abstract members.
   */
  private fun screenStateOf(text: String): ScreenState = object : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val annotatedScreenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeNodeTree: TrailblazeNode = TrailblazeNode(
      nodeId = 1,
      children = listOf(
        TrailblazeNode(
          nodeId = 2,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
        ),
      ),
      driverDetail = DriverNodeDetail.AndroidAccessibility(),
    )
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val pageContextSummary: String? = null
  }
}
