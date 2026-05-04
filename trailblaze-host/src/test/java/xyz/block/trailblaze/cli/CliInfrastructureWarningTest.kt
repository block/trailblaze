package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the user-facing wording of the workspace-mismatch and content-drift banners.
 *
 * These warnings are the load-bearing affordance of the cwd-vs-daemon coordination —
 * silent regression here means a binary-only adopter querying the wrong daemon never
 * sees the diagnostic. Tests cover the banner *building* (pure functions returning
 * `List<String>`); emission via Console.error and the quiet-mode gate are wired in
 * production callers but kept out of the unit-test surface so we don't have to mock
 * Console state.
 */
class CliInfrastructureWarningTest {

  // ---------------------------------------------------------------------------------------
  // Anchor mismatch
  // ---------------------------------------------------------------------------------------

  @Test
  fun `anchor mismatch banner names both anchors and the headline`() {
    val lines = buildAnchorMismatchBanner(
      daemonAnchor = "/repo/examples/playwright-native/trails/config/trailblaze.yaml",
      cwdAnchor = "/repo/examples/android-sample-app/trails/config/trailblaze.yaml",
      daemonTargets = setOf("playwrightsample"),
      cwdTargets = setOf("sampleapp"),
    )
    val joined = lines.joinToString("\n")
    assertTrue("WORKSPACE MISMATCH" in joined, "Headline missing in: $joined")
    assertTrue("/repo/examples/playwright-native/" in joined, "Daemon anchor missing.")
    assertTrue("/repo/examples/android-sample-app/" in joined, "Cwd anchor missing.")
  }

  @Test
  fun `anchor mismatch banner shows targets gained vs lost when sets differ`() {
    val lines = buildAnchorMismatchBanner(
      daemonAnchor = "/d/trails/config/trailblaze.yaml",
      cwdAnchor = "/c/trails/config/trailblaze.yaml",
      daemonTargets = setOf("alpha", "beta"),
      cwdTargets = setOf("beta", "gamma"),
    )
    val joined = lines.joinToString("\n")
    // Gained-by-restart = only-in-cwd = ["gamma"]
    assertTrue("+ gamma" in joined, "Expected '+ gamma' (gained by restart): $joined")
    // Currently-shown = only-in-daemon = ["alpha"]
    assertTrue("- alpha" in joined, "Expected '- alpha' (currently in daemon): $joined")
    // beta is in both — shouldn't appear in either diff line
    assertFalse("+ beta" in joined, "beta should not be marked as gained: $joined")
    assertFalse("- beta" in joined, "beta should not be marked as lost: $joined")
  }

  @Test
  fun `anchor mismatch banner explains the no-diff case when target sets are identical`() {
    // Same targets but different anchors — packs/tools/toolsets may still resolve from
    // different files. The banner should explicitly call this out instead of leaving a
    // confusingly-empty diff section.
    val lines = buildAnchorMismatchBanner(
      daemonAnchor = "/d/trails/config/trailblaze.yaml",
      cwdAnchor = "/c/trails/config/trailblaze.yaml",
      daemonTargets = setOf("foo", "bar"),
      cwdTargets = setOf("foo", "bar"),
    )
    val joined = lines.joinToString("\n")
    assertTrue(
      "Target lists are identical, but workspace anchors differ" in joined,
      "Expected explanation for identical-target case: $joined",
    )
    assertFalse("+ " in joined, "No '+' lines should appear when sets are identical")
    assertFalse("- " in joined, "No '-' lines should appear when sets are identical")
  }

  @Test
  fun `anchor mismatch banner sorts gained and lost target lists alphabetically`() {
    // Stable ordering across runs and machines so reviewers can diff banner snapshots.
    val lines = buildAnchorMismatchBanner(
      daemonAnchor = "/d/trails/config/trailblaze.yaml",
      cwdAnchor = "/c/trails/config/trailblaze.yaml",
      daemonTargets = setOf("zelda", "yoshi"),
      cwdTargets = setOf("xander", "wallaby"),
    )
    // Expect cwd-only sorted: wallaby, xander
    val gainedIdxWallaby = lines.indexOfFirst { it.contains("+ wallaby") }
    val gainedIdxXander = lines.indexOfFirst { it.contains("+ xander") }
    assertTrue(gainedIdxWallaby >= 0 && gainedIdxXander > gainedIdxWallaby)
    // Expect daemon-only sorted: yoshi, zelda
    val lostIdxYoshi = lines.indexOfFirst { it.contains("- yoshi") }
    val lostIdxZelda = lines.indexOfFirst { it.contains("- zelda") }
    assertTrue(lostIdxYoshi >= 0 && lostIdxZelda > lostIdxYoshi)
  }

  @Test
  fun `anchor mismatch banner ends with restart instructions`() {
    val lines = buildAnchorMismatchBanner(
      daemonAnchor = "/d/x.yaml",
      cwdAnchor = "/c/x.yaml",
      daemonTargets = emptySet(),
      cwdTargets = emptySet(),
    )
    val joined = lines.joinToString("\n")
    assertTrue("trailblaze app --stop" in joined, "Restart command missing.")
    assertTrue("Restart is not automatic" in joined, "Restart-rationale line missing.")
  }

  // ---------------------------------------------------------------------------------------
  // Content drift
  // ---------------------------------------------------------------------------------------

  @Test
  fun `content drift banner names the anchor and lists what counts as drift`() {
    val lines = buildContentDriftBanner("/repo/examples/foo/trails/config/trailblaze.yaml")
    val joined = lines.joinToString("\n")
    assertTrue("WORKSPACE CONTENT DRIFT" in joined, "Headline missing.")
    assertTrue("/repo/examples/foo/trails/config/trailblaze.yaml" in joined, "Anchor missing.")
    // The banner enumerates what's covered so users know edits to non-pack.yaml files
    // (tool YAMLs, scripts) are also caught — that distinction matters for muscle-memory.
    listOf("packs", "tool YAMLs", "scripts", "toolsets", "providers").forEach { term ->
      assertTrue(term in joined, "Expected coverage term '$term' in body: $joined")
    }
  }

  @Test
  fun `content drift banner ends with restart instructions and rationale`() {
    val lines = buildContentDriftBanner("/anchor.yaml")
    val joined = lines.joinToString("\n")
    assertTrue("trailblaze app --stop" in joined, "Restart command missing.")
    assertTrue("Restart is not automatic" in joined, "Restart-rationale line missing.")
  }

  @Test
  fun `content drift banner is shorter than anchor mismatch banner`() {
    // Anchor mismatch is the more elaborate banner (includes target diff). Content drift
    // is intentionally simpler — only one path involved. Pin that as a sanity check so a
    // future expansion of the drift banner notices it's outgrowing its job.
    val drift = buildContentDriftBanner("/anchor.yaml")
    val mismatch = buildAnchorMismatchBanner(
      daemonAnchor = "/d.yaml",
      cwdAnchor = "/c.yaml",
      daemonTargets = setOf("a", "b", "c"),
      cwdTargets = setOf("d", "e", "f"),
    )
    assertTrue(
      drift.size < mismatch.size,
      "Content drift banner should stay shorter than mismatch banner. " +
        "drift=${drift.size}, mismatch=${mismatch.size}",
    )
  }

  // ---------------------------------------------------------------------------------------
  // Empty-target sanity check (regression guard for the conditional branches)
  // ---------------------------------------------------------------------------------------

  @Test
  fun `anchor mismatch banner with one-sided differences omits the empty side`() {
    // Daemon has only one target, cwd has none — the "Targets the daemon currently
    // shows" section should appear, but the "Targets you would gain" section should
    // not (no targets to gain).
    val lines = buildAnchorMismatchBanner(
      daemonAnchor = "/d.yaml",
      cwdAnchor = "/c.yaml",
      daemonTargets = setOf("foo"),
      cwdTargets = emptySet(),
    )
    val joined = lines.joinToString("\n")
    assertEquals(0, lines.count { it.startsWith("    + ") }, "Should be no gained targets.")
    assertTrue("- foo" in joined, "Daemon-only 'foo' should be listed as currently-shown.")
  }
}
