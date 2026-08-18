package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.KnownTargetWorkspace

/**
 * Tests for [KnownTargetShadowLint] — the `trailblaze check`/`compile` warning that fires when a
 * workspace trailmap's id captures a target the known-target-workspaces registry homes in a
 * different repo. Pure-function coverage; the CLI wiring (warning surfaces on stderr, exit code
 * stays 0, `trailblaze check:` label routing) is pinned end-to-end in [CompileCommandTest] and
 * [CheckCommandTest].
 */
class KnownTargetShadowLintTest {

  /**
   * Trailmaps keyed by id, with a directory named after the id. The directory only matters to the
   * staged-copy predicate, which these tests stub — the cases that care about it pass their own.
   */
  private fun trailmaps(vararg ids: String): List<KnownTargetShadowLint.WorkspaceTrailmap> =
    ids.map { KnownTargetShadowLint.WorkspaceTrailmap(id = it, directory = File("/workspace/$it")) }

  private val registry = listOf(
    KnownTargetWorkspace(
      repo = "git@github.com:example-org/alpha-trails.git",
      targets = listOf("alpha", "alphaLite"),
    ),
    KnownTargetWorkspace(
      repo = "https://github.com/example-org/beta-trails",
      targets = listOf("beta"),
    ),
  )

  @Test
  fun `warns when a workspace trailmap id is homed in a different repo`() {
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha", "myOwnThing"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { setOf("example-org/consumer-repo") },
      commandLabel = "compile",
    )
    assertEquals(1, warnings.size, "Only the registered id should warn; got: $warnings")
    assertTrue(
      warnings.single().contains("'alpha'") && warnings.single().contains("example-org/alpha-trails"),
      "The warning must name the colliding trailmap id AND the registered home repo so the " +
        "author knows who owns the id; got: ${warnings.single()}",
    )
  }

  @Test
  fun `suppresses the warning when the registered home matches a workspace remote`() {
    // The home repo overriding its own bundled trailmap is the documented workflow, not a finding.
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { setOf("example-org/alpha-trails") },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `an https remote suppresses an ssh-registered record for the same repository`() {
    // Both sides normalize through [KnownTargetWorkspace.shortNameForRepo], so the record's SSH
    // spelling and a checkout's HTTPS remote agree on `org/repo`. Compare the two spellings the
    // way the production remote probe does — by normalizing the raw URL.
    val fromHttpsRemote =
      KnownTargetWorkspace.shortNameForRepo("https://github.com/example-org/alpha-trails")
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { setOf(fromHttpsRemote) },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `a staged copy of the home repo's trailmap is not a finding`() {
    // A git-ignored trailmap directory is content a build materialized (a pinned clone of the home
    // repo checked out into the workspace), not source someone authored here. Warning on it reports
    // the pinning mechanism working as designed, on every CI run — the noise that trains people to
    // ignore the warning that matters.
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha"),
      records = registry,
      isStagedCopy = { true },
      workspaceRepoShortNames = { setOf("example-org/consumer-repo") },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `a staged copy is skipped without suppressing an authored sibling`() {
    // The predicate is per-trailmap, so one staged copy can't silence a genuine collision alongside
    // it — the case that matters in a repo carrying both.
    val staged = File("/workspace/alpha")
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha", "beta"),
      records = registry,
      isStagedCopy = { it == staged },
      workspaceRepoShortNames = { setOf("example-org/consumer-repo") },
      commandLabel = "compile",
    )
    assertEquals(1, warnings.size, "Only the authored trailmap should warn; got: $warnings")
    assertTrue(
      warnings.single().contains("'beta'") && !warnings.single().contains("'alpha'"),
      "The authored 'beta' must warn and the staged 'alpha' must not; got: ${warnings.single()}",
    )
  }

  @Test
  fun `an all-staged collision set never pays for the remote probe`() {
    // Staged-copy filtering runs before the git remote probe, so the common CI case (every
    // collision is a pinned clone) costs no subprocess.
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha", "beta"),
      records = registry,
      isStagedCopy = { true },
      workspaceRepoShortNames = { error("remote probe must not run when every collision is staged") },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `the staged-copy probe is lazy — never invoked without a collision`() {
    KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("gamma", "delta"),
      records = registry,
      isStagedCopy = { error("staged-copy probe must not run without a collision") },
      workspaceRepoShortNames = { error("remote probe must not run without a collision") },
      commandLabel = "compile",
    )
  }

  @Test
  fun `a single-label host normalizes the same across url spellings`() {
    // Regression: the parser used to infer the host by looking for a dot, so `https://git/org/repo`
    // kept `git` as the org (`git/org/repo`) while the equivalent `git@git:org/repo` gave
    // `org/repo`. On an intranet host like that, a checkout of the registered repo compared unequal
    // to its own record and warned in its own home repo.
    val ssh = KnownTargetWorkspace.shortNameForRepo("git@git:org/repo.git")
    val https = KnownTargetWorkspace.shortNameForRepo("https://git/org/repo.git")
    assertEquals("org/repo", ssh)
    assertEquals(ssh, https, "Both spellings name the same repository, so both must normalize alike")
  }

  @Test
  fun `url forms with userinfo, ports and nested groups normalize to the full path after the host`() {
    // The authority is parsed structurally (everything up to the first `/`), so a port or embedded
    // userinfo can't leak into the org, and a nested group keeps every segment — a truncated org
    // would point people at a repo that doesn't exist under the name shown.
    assertEquals(
      "org/repo",
      KnownTargetWorkspace.shortNameForRepo("https://user@host:8080/org/repo.git"),
    )
    assertEquals(
      "org/repo",
      KnownTargetWorkspace.shortNameForRepo("ssh://git@github.com/org/repo.git"),
    )
    assertEquals(
      "org/group/repo",
      KnownTargetWorkspace.shortNameForRepo("git@host:org/group/repo.git"),
    )
    // A bare `org/repo` (no scheme, no separator) has no host to drop and must survive intact.
    assertEquals("org/repo", KnownTargetWorkspace.shortNameForRepo("org/repo"))
    // Degenerate values stay blank so the loader can reject the record rather than render "lives in ".
    assertEquals("", KnownTargetWorkspace.shortNameForRepo("/"))
    assertEquals("", KnownTargetWorkspace.shortNameForRepo("git@host:"))
  }

  @Test
  fun `repo match is case-insensitive`() {
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { setOf("Example-Org/Alpha-Trails") },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `id match is case-insensitive, mirroring how target ids resolve everywhere else`() {
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("ALPHA"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { setOf("example-org/consumer-repo") },
      commandLabel = "compile",
    )
    assertEquals(1, warnings.size, "A case-variant id still captures the target, so it must warn")
  }

  @Test
  fun `an unidentifiable workspace repo still warns`() {
    // Not a git checkout / no remotes / no `git` — exactly when the author needs the pointer.
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("beta"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { emptySet() },
      commandLabel = "compile",
    )
    assertEquals(1, warnings.size)
    assertTrue(warnings.single().contains("example-org/beta-trails"))
  }

  @Test
  fun `unregistered ids and an empty registry produce nothing`() {
    val noCollision = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("gamma"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { emptySet() },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), noCollision)

    val noRegistry = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha"),
      records = emptyList(),
      isStagedCopy = { false },
      workspaceRepoShortNames = { emptySet() },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), noRegistry, "No registry on the classpath must behave as today: silence")
  }

  @Test
  fun `the git probe is lazy — never invoked unless an id actually collides`() {
    // The provider costs a `git` subprocess in production, so a collision-free compile (the
    // overwhelmingly common case) must not pay for it.
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("gamma", "delta"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { error("workspaceRepoShortNames must not be probed without a collision") },
      commandLabel = "compile",
    )
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `warnings carry the routed command label`() {
    val warnings = KnownTargetShadowLint.warningsFor(
      workspaceTrailmaps = trailmaps("alpha"),
      records = registry,
      isStagedCopy = { false },
      workspaceRepoShortNames = { emptySet() },
      commandLabel = "check",
    )
    assertTrue(
      warnings.single().startsWith("trailblaze check: Warning:"),
      "The user typed `trailblaze check`, so the warning must read `trailblaze check:`; " +
        "got: ${warnings.single()}",
    )
  }
}
