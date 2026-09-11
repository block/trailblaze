package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.cli.CheckCommand
import xyz.block.trailblaze.cli.CliPathUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.yaml.TrailYamlValidator
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * Repo-wide gate: no trail anywhere in the git checkout may name a target its own workspace cannot
 * resolve. [TrailTargetLint] holds the rule and the evidence for why an unregistered id is invisible
 * without a gate — it degrades to the workspace default and the run still reports PASSED.
 *
 * ## Why this exists alongside the `trailblaze check` phase
 *
 * Same split as [DevicePinCorpusTest]: `CheckCommand.runTrailLintPhase` applies the same lint, but
 * only to one **workspace**'s trails and only where a built CLI is available. This test walks the
 * **git root** with nothing but a JVM, so it covers every workspace in the repo at once — including
 * fixtures, scratch trails, and example workspaces nobody runs `check` against — and it runs in
 * plain `./gradlew check`.
 *
 * ## One resolvable set PER WORKSPACE, not per repo
 *
 * The correctness hinge. A checkout can hold many trailmap roots — the git root registers one set,
 * each example workspace and each module's bundled resources register their own. Validating every
 * trail against a single union would pass trails that really do degrade; validating them all against
 * the git root's set would fail dozens of perfectly good trails in the nested workspaces. So each
 * trail is resolved against the nearest enclosing workspace, found by the same walk-up the CLI uses
 * ([CliPathUtils.findWorkspaceRoot]), against the ids `trailblaze check`'s own trail-target phase
 * resolves — [CheckCommand.listAllWorkspaceTargetIds] plus classpath manifests, shared through one
 * [TrailTargetLint.TargetIdResolver] so the two lanes cannot disagree.
 *
 * A trail outside every workspace falls back to the git root's set rather than being skipped: it is
 * still a trail someone can run, and the git root is the workspace a run from the repo would pick up.
 *
 * ## Depends on the checkout being staged
 *
 * Trailmaps this repo materializes at Gradle configuration time count as registered — they are on
 * disk by the time any Gradle-run test executes, and a checkout where staging failed configures no
 * projects at all. Running this test outside Gradle against an unstaged tree reports those ids as
 * unresolved; the failure message names them, which is the same signal.
 */
class TrailTargetCorpusTest {

  @Test
  fun `no trail names a target its own workspace cannot resolve`() {
    val gitRoot = GitUtils.getGitRootViaCommand()?.let(::File)
      ?: error("Failed to determine git repository root")

    val checkCommand = CheckCommand()
    // The same memoized resolver `trailblaze check` uses, so the two lanes cannot disagree about
    // which ids a given workspace registers.
    val resolver = TrailTargetLint.TargetIdResolver(checkCommand::listAllWorkspaceTargetIds)
    val workspaceRoots = mutableSetOf<File>()

    val yaml = createTrailblazeYaml()
    val findings = mutableListOf<TrailTargetLint.Finding>()
    val allFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)
    var unparseable = 0
    allFiles.forEach { file ->
      val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
      val unified = try {
        when (val doc = yaml.decodeTrailDocument(text)) {
          is TrailDocument.Unified -> doc.trail
        }
      } catch (_: Throwable) {
        // Unparseable trail — TrailYamlValidationTest owns that error. Double-reporting one broken
        // file as two failures only makes triage worse. Throwable, not Exception: decodeTrailDocument
        // surfaces unknown-tool failures as non-Exception Throwables.
        unparseable++
        return@forEach
      }
      val workspaceRoot = CliPathUtils.findWorkspaceRoot(file.toPath())?.toFile() ?: gitRoot
      workspaceRoots.add(workspaceRoot)
      TrailTargetLint.lint(
        trailRelPath = file.relativeTo(gitRoot).path,
        workspaceLabel = workspaceRoot.relativeTo(gitRoot).path.ifEmpty { "." },
        trail = unified,
        registeredTargetIds = resolver.forWorkspace(workspaceRoot),
        trailmapsDir = CliPathUtils.workspaceTrailmapsDir(workspaceRoot.toPath())
          .toFile().relativeTo(gitRoot).path,
      )?.let(findings::add)
    }

    Console.log(
      "Trail-target gate: ${allFiles.size} trail file(s) discovered across " +
        "${workspaceRoots.size} workspace(s), $unparseable unparseable (not gated here), " +
        "${findings.size} with an unresolvable target",
    )

    if (findings.isNotEmpty()) {
      fail(TrailTargetLint.renderFailures(findings))
    }
  }
}
