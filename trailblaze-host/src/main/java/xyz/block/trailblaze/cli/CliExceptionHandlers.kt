package xyz.block.trailblaze.cli

import picocli.CommandLine

/**
 * Install picocli exception handlers that translate parameter-parse and
 * execution failures into the project's structured-error envelope.
 *
 * Picocli's defaults dump a usage banner + the raw `e.toString()` to stderr,
 * which surfaces things like `java.net.SocketTimeoutException: connect timed
 * out` to end users. The Trailblaze policy is:
 *
 *  - Parameter errors (unknown flag, missing required arg, malformed value)
 *    → exit code [TrailblazeExitCode.MISUSE] (3), structured `✗ … / reason /
 *    hint` envelope, NO stack trace, no usage banner dump (the per-command
 *    `--help` is one keystroke away and reproducing it on every typo is noisy).
 *  - Execution exceptions thrown out of a `Callable<Int>.call()` body
 *    → exit code [TrailblazeExitCode.INFRA_FAILED] (2), structured envelope,
 *    NO stack trace. The action lambdas that *want* a more specific envelope
 *    keep emitting their own (e.g. daemon-unreachable, device-bind-failed)
 *    before throwing; this handler is the last line of defense for anything
 *    that slipped through.
 *
 * Stack traces are debug-mode territory. Hidden under a future `--verbose`
 * flag if desired — for now, the env-var
 * `TRAILBLAZE_CLI_PRINT_STACK_TRACES=1` flips on raw stack-trace emission so
 * a maintainer triaging an unfamiliar exception can opt back in without
 * code changes.
 */
internal fun installTrailblazeExceptionHandlers(commandLine: CommandLine) {
  commandLine.parameterExceptionHandler = CommandLine.IParameterExceptionHandler { ex, _ ->
    reportCliError(
      verb = "Command parse",
      reason = ex.message ?: "invalid arguments",
      hint = "run `${commandLine.commandName} --help` to see the supported flags",
    )
    if (printStackTraces()) ex.printStackTrace(System.err)
    TrailblazeExitCode.MISUSE.code
  }
  commandLine.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { ex, _, _ ->
    reportCliError(
      verb = "Command",
      reason = describeThrowableForUser(ex),
    )
    if (printStackTraces()) ex.printStackTrace(System.err)
    TrailblazeExitCode.INFRA_FAILED.code
  }
}

private fun printStackTraces(): Boolean {
  val flag = System.getenv("TRAILBLAZE_CLI_PRINT_STACK_TRACES") ?: return false
  return flag == "1" || flag.equals("true", ignoreCase = true)
}
