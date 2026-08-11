package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.yaml.TrailYamlValidator
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * Repo-wide gate: no trail anywhere in the git checkout may hand a device a recording leg whose
 * selector dialect that device's driver cannot match. [SelectorDialectLint] holds the rule and the
 * evidence for why that combination fails on every run.
 *
 * ## Why this exists alongside the `trailblaze check` phase
 *
 * `CheckCommand.runSelectorDialectLintPhase` applies the same lint, but only to a **workspace**'s
 * `trails/` directory, and only where a built CLI + bun + registry access are available. This test
 * walks the **git root** with nothing but a JVM, so it covers every trail in the repo — fixtures,
 * scratch trails, and anything not shaped as a workspace — and it runs in plain `./gradlew check`
 * whether or not the CLI-side step is reachable. Same rule, cheaper and wider net.
 *
 * Deliberately mirrors [xyz.block.trailblaze.yaml.TrailYamlValidationTest]'s discovery
 * ([TrailYamlValidator.findAllTrailYamlFiles]) so the two repo-wide trail gates always see the same
 * file set. Unparseable files are skipped here: that test owns the parse error, and double-reporting
 * one broken file as two failures just makes triage worse.
 */
class SelectorDialectCorpusTest {

  @Test
  fun `no trail resolves a recording leg its driver cannot match`() {
    val gitRoot = GitUtils.getGitRootViaCommand()?.let(::File)
      ?: error("Failed to determine git repository root")
    val yaml = createTrailblazeYaml()

    var unifiedCount = 0
    val unparseable = mutableListOf<String>()
    val findings = mutableListOf<SelectorDialectLint.Finding>()
    val allFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)
    allFiles.forEach { file ->
      val rel = file.relativeTo(gitRoot).path
      val doc = runCatching { yaml.decodeTrailDocument(file.readText()) }.getOrNull()
      if (doc == null) {
        unparseable.add(rel)
        return@forEach
      }
      val unified = (doc as? TrailDocument.Unified)?.trail ?: return@forEach
      unifiedCount++
      SelectorDialectLint.lint(rel, unified)?.let { findings.add(it) }
    }

    // Say out loud what was NOT covered. A silent skip reads as "the corpus is clean" when it may
    // only mean the file never reached the lint.
    Console.log(
      "Selector-dialect gate: ${allFiles.size} trail file(s) discovered, $unifiedCount unified and " +
        "linted, ${unparseable.size} unparseable (skipped — TrailYamlValidationTest owns those), " +
        "${findings.size} finding(s)",
    )
    if (unparseable.isNotEmpty()) {
      Console.log("Selector-dialect gate: skipped unparseable -> ${unparseable.take(20).joinToString()}")
    }
    if (findings.isNotEmpty()) {
      fail(SelectorDialectLint.renderFailures(findings))
    }
  }
}
