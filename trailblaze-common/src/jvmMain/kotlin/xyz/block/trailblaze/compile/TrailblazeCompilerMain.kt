@file:JvmName("TrailblazeCompilerMain")

package xyz.block.trailblaze.compile

import java.io.File
import kotlin.system.exitProcess

/**
 * Build-time entry point for [TrailblazeCompiler]. Lives in `trailblaze-common`
 * (not the desktop CLI) so the framework JAR's Gradle build can `JavaExec` this
 * main without dragging in `trailblaze-host`'s desktop / MCP / picocli graph.
 *
 * Usage:
 * ```
 * java -cp ... xyz.block.trailblaze.compile.TrailblazeCompilerMain \
 *   --input  <packs-dir> \
 *   --output <targets-dir>
 * ```
 *
 * The user-facing `trailblaze compile` CLI command in `trailblaze-host` wraps
 * the same [TrailblazeCompiler.compile] entry point with picocli; both paths
 * share the core implementation.
 *
 * Exit codes:
 * - `0` — compile succeeded, OR `--help` was requested.
 * - `1` — compile encountered a resolution / parse / reference-validation error.
 * - `2` — argument parsing failed (missing required flag, unknown flag, repeated
 *   flag, bad path).
 */
fun main(args: Array<String>) {
  exitProcess(runCompiler(args))
}

internal fun runCompiler(args: Array<String>): Int {
  return when (val parsed = parseArgs(args)) {
    is ParseResult.Help -> {
      printUsage()
      EXIT_OK
    }
    is ParseResult.UsageError -> {
      System.err.println("trailblaze compile: ${parsed.reason}")
      printUsage()
      EXIT_USAGE
    }
    is ParseResult.Success -> runWithArgs(parsed)
  }
}

private fun runWithArgs(parsed: ParseResult.Success): Int {
  val packsDir = File(parsed.input)
  val outputDir = File(parsed.output)

  if (!packsDir.isDirectory) {
    System.err.println(
      "trailblaze compile: --input does not exist or is not a directory: ${packsDir.absolutePath}",
    )
    return EXIT_USAGE
  }

  val result = TrailblazeCompiler.compile(packsDir = packsDir, outputDir = outputDir)
  if (!result.isSuccess) {
    System.err.println("trailblaze compile: compilation failed:")
    result.errors.forEach { System.err.println("  - $it") }
    return EXIT_COMPILE_ERROR
  }

  println(
    "trailblaze compile: emitted ${result.emittedTargets.size} target(s) to ${outputDir.absolutePath}",
  )
  result.emittedTargets.forEach { println("  - ${it.name}") }
  if (result.deletedOrphans.isNotEmpty()) {
    println(
      "trailblaze compile: cleaned up ${result.deletedOrphans.size} stale target(s):",
    )
    result.deletedOrphans.forEach { println("  - ${it.name}") }
  }
  return EXIT_OK
}

private sealed class ParseResult {
  data class Success(val input: String, val output: String) : ParseResult()
  object Help : ParseResult()
  data class UsageError(val reason: String) : ParseResult()
}

/**
 * Hand-rolled argument parser kept intentionally minimal — picocli is in
 * `trailblaze-host`'s graph but not on this lightweight build-time classpath,
 * and pulling it in just to parse `--input` / `--output` would defeat the
 * purpose of having a separate `Main` for build-time use.
 */
private fun parseArgs(args: Array<String>): ParseResult {
  var input: String? = null
  var output: String? = null
  var i = 0
  while (i < args.size) {
    when (val a = args[i]) {
      "--input", "-i" -> {
        if (input != null) return ParseResult.UsageError("--input was specified more than once")
        input = args.getOrNull(i + 1)
          ?: return ParseResult.UsageError("missing value for $a")
        i += 2
      }
      "--output", "-o" -> {
        if (output != null) return ParseResult.UsageError("--output was specified more than once")
        output = args.getOrNull(i + 1)
          ?: return ParseResult.UsageError("missing value for $a")
        i += 2
      }
      "--help", "-h" -> return ParseResult.Help
      else -> return ParseResult.UsageError("unexpected argument: $a")
    }
  }
  if (input == null || output == null) {
    return ParseResult.UsageError("--input and --output are required")
  }
  return ParseResult.Success(input = input, output = output)
}

private fun printUsage() {
  System.err.println(
    """
    |Usage: trailblaze-compile --input <packs-dir> --output <targets-dir>
    |
    |Compiles every <id>/pack.yaml under --input into a resolved <id>.yaml
    |under --output, one per app pack (a pack with a `target:` block).
    |Library packs (no target) contribute defaults but produce no output.
    |
    |Stale `<id>.yaml` files left in <targets-dir> from a previous compile
    |that no longer correspond to a current pack are deleted automatically
    |(orphan cleanup). Hand-authored YAMLs without the generated-file
    |banner are left alone.
    |
    |Options:
    |  --input,  -i <packs-dir>   Directory containing one <id>/pack.yaml per pack.
    |  --output, -o <targets-dir> Directory to emit resolved <id>.yaml files into.
    |  --help,   -h               Show this message and exit (exit code 0).
    """.trimMargin()
  )
}

private const val EXIT_OK = 0
private const val EXIT_USAGE = 2
private const val EXIT_COMPILE_ERROR = 1
