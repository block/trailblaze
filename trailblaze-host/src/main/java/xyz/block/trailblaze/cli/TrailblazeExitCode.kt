package xyz.block.trailblaze.cli

import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse

/**
 * Canonical exit-code policy for the Trailblaze CLI.
 *
 * Every non-AI command's [java.util.concurrent.Callable.call] should return one of
 * these via [code]. Picocli's own `ExitCode` constants (`SOFTWARE` = 1, `USAGE` = 2)
 * do NOT match this policy — assertion failures and infra failures share `SOFTWARE`
 * in picocli's world, and the conventional `USAGE` = 2 collides with our infra-failure
 * code. Use this enum exclusively in command code so the four cases stay distinguishable
 * to a calling shell or CI step.
 *
 * | code | name              | meaning                                                            |
 * |------|-------------------|--------------------------------------------------------------------|
 * | 0    | [SUCCESS]         | Command completed successfully.                                    |
 * | 1    | [ASSERTION_FAILED]| A recorded assertion (or `verify`-style check) returned false.     |
 * | 2    | [INFRA_FAILED]    | Daemon unreachable, device not found, network timeout, ADB error, |
 * |      |                   | missing dependency, unhandled runtime exception, etc.              |
 * | 3    | [MISUSE]          | Bad flags, missing required arg, malformed input file, unknown    |
 * |      |                   | subcommand.                                                        |
 */
enum class TrailblazeExitCode(val code: Int) {
  SUCCESS(0),
  ASSERTION_FAILED(1),
  INFRA_FAILED(2),
  MISUSE(3),
}

/**
 * Choose the worse of two [TrailblazeExitCode] values when a batch of operations
 * yields heterogeneous outcomes (e.g. `trailblaze run` across several files where
 * one fails its assertions and another can't reach the daemon).
 *
 * Precedence: `INFRA_FAILED > MISUSE > ASSERTION_FAILED > SUCCESS`. An infra
 * failure beats everything else because "we couldn't attempt the work" is a
 * stronger signal to a calling shell than "we attempted it and got a wrong
 * answer." Misuse outranks assertion failure for the same reason (the operator
 * needs to fix their invocation before the assertion-failure verdict is
 * meaningful). Inputs that aren't one of the four canonical codes (legacy paths
 * still returning raw `1` literals, picocli defaults from un-wrapped command
 * lines) are treated as INFRA_FAILED-tier so they can't silently green-light a
 * chained `&& deploy`.
 */
internal fun chooseWorseExitCode(a: Int, b: Int): Int {
  val rankA = exitCodeRank(a)
  val rankB = exitCodeRank(b)
  return if (rankA >= rankB) a else b
}

/**
 * Exit code for a daemon-delegated run whose [CliRunResponse] came back failed.
 *
 * The daemon signals its failure class via [CliRunResponse.errorKind]: a request it
 * REJECTED as invalid ([CliRunResponse.ERROR_KIND_MISUSE], e.g. an unrecognized driver
 * name validated daemon-side) maps to [TrailblazeExitCode.MISUSE], matching the
 * in-process path's exit code for the same mistake. Everything else — including
 * responses from older daemons that don't send the field — stays
 * [TrailblazeExitCode.ASSERTION_FAILED] (the daemon RPC doesn't yet distinguish
 * assertion-vs-infra failures for attempted runs; see the daemon-loop comment in
 * TrailCommand).
 */
internal fun daemonRunFailureExitCode(response: CliRunResponse): TrailblazeExitCode =
  if (response.errorKind == CliRunResponse.ERROR_KIND_MISUSE) {
    TrailblazeExitCode.MISUSE
  } else {
    TrailblazeExitCode.ASSERTION_FAILED
  }

private fun exitCodeRank(code: Int): Int = when (code) {
  TrailblazeExitCode.SUCCESS.code -> 0
  TrailblazeExitCode.ASSERTION_FAILED.code -> 1
  TrailblazeExitCode.MISUSE.code -> 2
  TrailblazeExitCode.INFRA_FAILED.code -> 3
  else -> 3 // unknown non-zero → treat as the worst tier (INFRA_FAILED equivalent)
}
