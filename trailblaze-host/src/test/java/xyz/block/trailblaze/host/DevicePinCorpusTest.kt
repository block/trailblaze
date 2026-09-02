package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.yaml.TrailYamlValidator

/**
 * Repo-wide gate: no trail anywhere in the git checkout may declare a device driver outside the
 * classifier it belongs to — `config.driver:` beside `config.devices:`, or a `devices:` entry keyed
 * `driver`. [DevicePinLint] holds both rules and the evidence for why either shape silently unpins
 * the device.
 *
 * ## Why this exists alongside the `trailblaze check` phase
 *
 * `CheckCommand.runTrailLintPhase` applies the same lint, but only to a **workspace**'s `trails/`
 * directory, and only where a built CLI is available. This test walks the **git root** with nothing
 * but a JVM, so it covers every trail in the repo — fixtures, scratch trails, and anything not
 * shaped as a workspace — and it runs in plain `./gradlew check`. Same rule, cheaper and wider net.
 * It is what replaced the `session_driver_beside_devices` half of `scripts/migrate_device_form.py`.
 *
 * Deliberately mirrors [SelectorDialectCorpusTest]'s and
 * [xyz.block.trailblaze.yaml.TrailYamlValidationTest]'s discovery
 * ([TrailYamlValidator.findAllTrailYamlFiles]) so the repo-wide trail gates always see the same
 * file set.
 *
 * ## Only the fatal rule is gated here
 *
 * The deprecated bare-string device form is counted and logged, not failed. Its repo-wide ratchet
 * is still `scripts/migrate_device_form.py --check`, which stays until the decode-only branch in
 * [xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinitionMapSerializer] is deleted. Two
 * absolute gates on one rule is exactly the drift worth avoiding — so this one reports the
 * inventory and lets the Python own the failure.
 */
class DevicePinCorpusTest {

  @Test
  fun `no trail declares a device driver outside the classifier it pins`() {
    val gitRoot = GitUtils.getGitRootViaCommand()?.let(::File)
      ?: error("Failed to determine git repository root")

    val findings = mutableListOf<DevicePinLint.Finding>()
    val allFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)
    allFiles.forEach { file ->
      val rel = file.relativeTo(gitRoot).path
      val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
      DevicePinLint.lint(rel, text)?.let { findings.add(it) }
    }

    val deprecated = findings.filter { it.legacyDriverForms.isNotEmpty() }
    Console.log(
      "Device-pin gate: ${allFiles.size} trail file(s) discovered, " +
        "${findings.count { it.isFatal }} mis-indented pin(s), " +
        "${deprecated.sumOf { it.legacyDriverForms.size }} deprecated bare-string entr(y/ies) in " +
        "${deprecated.size} file(s) (reported, not gated here — see the class kdoc)",
    )
    if (deprecated.isNotEmpty()) {
      Console.log(DevicePinLint.renderDeprecationWarnings(deprecated))
    }

    val fatal = findings.filter { it.isFatal }
    if (fatal.isNotEmpty()) {
      fail(DevicePinLint.renderFatalFailures(fatal))
    }
  }
}
