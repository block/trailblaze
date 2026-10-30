package xyz.block.trailblaze.cli

import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runs the TypeScript surveys CLI (`sdks/typescript/src/surveys/cli.ts`) under bun, for
 * `trailblaze survey` and `trailblaze report --surveys`. Surveys are TypeScript a workspace
 * imports, so the runner that loads them is too; this only finds it and hands it the arguments.
 *
 * A source checkout runs its own `cli.ts`, which needs no `node_modules`. An installed CLI has no
 * SDK tree, so it runs the same code from the SDK bundle this JAR ships (`dist/surveys.js`,
 * extracted to the app's cache), through a one-line entry written next to that cache.
 */
internal object SurveyCli {

  /** The command line that starts the surveys CLI, or null with the reason already printed. */
  fun entryArgv(): List<String>? {
    if (!CliPathUtils.isCommandOnPath("bun")) {
      Console.error("Error: bun is not on PATH. Surveys are TypeScript and run under bun (https://bun.sh).")
      return null
    }
    ScriptedToolDefinitionAnalyzer.resolveSdkDir()
      ?.let { File(it, "src/surveys/cli.ts") }
      ?.takeIf { it.isFile }
      ?.let { return listOf("bun", it.absolutePath) }
    val cache = File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), TrailblazeDesktopUtil.SDK_CACHE_SUBDIR)
    // Extracting can print (it names the files it removes), and stdout is the surveys CLI's: with
    // `--format json` a stray line there would break the document.
    val bundle = Console.runJsonOutput { WorkspaceTypeScriptSetup.frameworkSdkRuntimeEntry(cache) }
      ?.let { File(it.parentFile, "surveys.js") }
      ?.takeIf { it.isFile }
    if (bundle == null) {
      Console.error("Error: this build ships no surveys runtime (dist/surveys.js), so it cannot run surveys.")
      return null
    }
    // Beside the SDK, not in it: extraction deletes whatever it did not ship.
    val entry = File(cache.parentFile, "survey-cli/survey-cli.mjs")
    val text = "import { runSurveyCli } from ${jsString(bundle.toURI().toString())};\n" +
      "process.exitCode = await runSurveyCli(process.argv.slice(2));\n"
    if (!entry.isFile || entry.readText() != text) {
      // Written whole and moved into place, so a run starting at the same moment never reads half of it.
      entry.parentFile.mkdirs()
      val tmp = File.createTempFile("survey-cli", ".mjs", entry.parentFile)
      tmp.writeText(text)
      Files.move(tmp.toPath(), entry.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    return listOf("bun", entry.absolutePath)
  }

  /**
   * Runs the surveys CLI with [args] from [cwd], which decides the workspace whose surveys load.
   * Stderr is the caller's; stdout is too unless [stdout] names a file to write it to. Returns a
   * [TrailblazeExitCode]: see [exitCodeOf], or [TrailblazeExitCode.INFRA_FAILED] when it could not start.
   */
  fun run(args: List<String>, cwd: File, stdout: File? = null): Int {
    return try {
      // Inside the try: writing the launcher can fail too (a full disk, an unwritable cache).
      val argv = entryArgv() ?: return TrailblazeExitCode.INFRA_FAILED.code
      val builder = ProcessBuilder(argv + args)
        .directory(cwd)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
      if (stdout == null) builder.redirectOutput(ProcessBuilder.Redirect.INHERIT) else builder.redirectOutput(stdout)
      exitCodeOf(builder.start().waitFor())
    } catch (e: Exception) {
      Console.error("Error: could not start the surveys CLI: ${e.message ?: e.javaClass.simpleName}")
      TrailblazeExitCode.INFRA_FAILED.code
    }
  }

  /**
   * The surveys CLI's status as a [TrailblazeExitCode]. It exits 2 for input that names nothing (an
   * unknown `--survey`, no workspace, no sessions) and 1 when a survey or label phase threw — code
   * that failed, not a check that came out false.
   */
  internal fun exitCodeOf(status: Int): Int = when (status) {
    0 -> TrailblazeExitCode.SUCCESS.code
    2 -> TrailblazeExitCode.MISUSE.code
    else -> TrailblazeExitCode.INFRA_FAILED.code
  }

  private fun jsString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
