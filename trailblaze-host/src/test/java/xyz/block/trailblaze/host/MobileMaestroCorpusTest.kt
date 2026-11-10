package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.yaml.TrailYamlValidator

/**
 * Repo-wide gate: no trail or scripted tool may call `mobile_maestro`. The tool is not part of the
 * public tool surface — it has no descriptor and no TypeScript type — and survives only as the
 * framework's internal host-to-device wire. Use the typed tools (`tapOnPoint`, `pressBack`,
 * `scrollUntilTextIsVisible`, ...) instead.
 *
 * Scans every trail ([TrailYamlValidator.findAllTrailYamlFiles], the same file set the other
 * repo-wide trail gates use) for a `mobile_maestro:` tool key, and every git-tracked non-test
 * TypeScript source under any `trailmaps/` tree (bundled resources and examples included, at any
 * depth) for the name in any form, `ctx.tools.mobile_maestro(...)` included. Comment lines are
 * ignored. Trailmaps staged from another repo's pin are gitignored and not scanned: that repo's own
 * typecheck rejects the call, since the tool has no TypeScript binding.
 */
class MobileMaestroCorpusTest {

  private val toolKey = Regex("""^\s*(?:-\s+)?["']?mobile_maestro["']?\s*:""")
  private val scriptMention = Regex("""\bmobile_maestro\b""")

  @Test
  fun `no trail or scripted tool calls mobile_maestro`() {
    val gitRoot = GitUtils.getGitRootViaCommand()?.let(::File)
      ?: error("Failed to determine git repository root")

    val offenders = mutableListOf<String>()

    TrailYamlValidator.findAllTrailYamlFiles(gitRoot).forEach { file ->
      val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
      if (text.lineSequence().any { !it.trimStart().startsWith("#") && toolKey.containsMatchIn(it) }) {
        offenders += file.relativeTo(gitRoot).path
      }
    }

    trackedTrailmapScripts(gitRoot).forEach { path ->
      val text = runCatching { gitRoot.resolve(path).readText() }.getOrNull() ?: return@forEach
      if (text.lineSequence().any { !it.isCommentLine() && scriptMention.containsMatchIn(it) }) {
        offenders += path
      }
    }

    if (offenders.isNotEmpty()) {
      fail(
        "`mobile_maestro` is not a public tool. Replace it with typed tools in:\n" +
          offenders.sorted().joinToString("\n") { "  - $it" },
      )
    }
  }

  /** Git-tracked `.ts` sources under a `trailmaps/` directory, minus tests, declarations and generated output. */
  private fun trackedTrailmapScripts(gitRoot: File): List<String> {
    val process = ProcessBuilder("git", "ls-files", "-z", "--", "*.ts")
      .directory(gitRoot)
      .redirectErrorStream(false)
      .start()
    val paths = process.inputStream.bufferedReader().readText().split('\u0000').filter { it.isNotEmpty() }
    check(process.waitFor() == 0) { "git ls-files failed in $gitRoot" }
    return paths.filter { path ->
      ("/$path").contains("/trailmaps/") &&
        !path.endsWith(".test.ts") &&
        !path.endsWith(".d.ts") &&
        "/generated/" !in "/$path" &&
        "/node_modules/" !in "/$path"
    }
  }

  private fun String.isCommentLine(): Boolean {
    val line = trimStart()
    return line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
  }
}
