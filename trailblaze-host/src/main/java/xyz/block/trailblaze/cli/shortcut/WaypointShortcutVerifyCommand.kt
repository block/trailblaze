package xyz.block.trailblaze.cli.shortcut

import xyz.block.trailblaze.cli.DeviceResolution
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.cli.resolveDeviceOrErrorBlocking

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.cli.yaml.TrailblazeNodeSelectorYamlEmitter
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.WaypointLoader
import java.io.File
import java.util.concurrent.Callable

/**
 * `trailblaze waypoint shortcut verify` — empirical replay of a proposed shortcut on a
 * connected emulator. Generates a throwaway trail YAML from the shortcut definition,
 * invokes `./trailblaze run` on it, and returns the trail's exit code.
 *
 * The generated trail's shape:
 *
 * ```
 * - config:
 *     id: "<shortcut-id>-verify"
 *     driver: ANDROID_ONDEVICE_ACCESSIBILITY
 * - prompts:
 *     - step: Confirm starting waypoint <from>
 *       postcondition:
 *         waypoint: "<from>"
 *     - step: Run shortcut body
 *       postcondition:
 *         waypoint: "<to>"
 *       recording:
 *         tools:
 *           <tools from shortcut yaml, inlined verbatim>
 * ```
 *
 * The caller (the pipeline, a hand-run author session) is responsible for putting the
 * device into the `from` waypoint state before invoking verify — v1 deliberately does
 * not try to discover the right trailhead automatically. Looking up the right trailhead
 * for an arbitrary `from` is itself an unsolved problem ("which trailhead trail lands
 * at `myapp/android/drawer_open`?"); the pipeline's bootstrap script handles the
 * trailhead choice externally so this command stays a thin shortcut-execution wrapper.
 *
 * See `docs/internal/devlog/2026-05-19-waypoint-pack-shortcuts.md`.
 */
@Command(
  name = "verify",
  mixinStandardHelpOptions = true,
  description = [
    "Empirical replay of a proposed shortcut against a connected emulator.",
    "Generates a throwaway trail YAML and runs it via `trailblaze run`.",
    "Returns 0 if the post-condition waypoint matches, non-zero otherwise.",
  ],
)
class WaypointShortcutVerifyCommand : Callable<Int> {

  @Option(
    names = ["--yaml"],
    paramLabel = "<path>",
    description = ["Path to the shortcut YAML to verify (a `*.shortcut.yaml`)."],
    required = true,
  )
  lateinit var yamlPath: File

  @Option(
    names = ["--device"],
    paramLabel = "<id>",
    description = [
      "Device id to run against (forwarded to `trailblaze run --device`).",
      "Defaults to \$TRAILBLAZE_DEVICE.",
    ],
  )
  var deviceId: String? = null

  @Option(
    names = ["--driver"],
    paramLabel = "<id>",
    description = [
      "Driver to use in the generated trail config. Default: ANDROID_ONDEVICE_ACCESSIBILITY.",
      "Pick a different value when verifying an iOS shortcut (v1 ships Android-first).",
    ],
  )
  var driverId: String = "ANDROID_ONDEVICE_ACCESSIBILITY"

  @Option(
    names = ["--max-attempts"],
    paramLabel = "<n>",
    description = [
      "Total attempts before declaring failure. Default: 1 (no retries).",
      "Trail-runtime exit codes are not distinguishable today between infrastructure",
      "and test-side failures (picocli SOFTWARE=1 covers both driver-init failures and",
      "post-condition mismatches), so v1 defaults to no retries. Override to 2+ only",
      "when investigating known transient infra flake; a flaky shortcut is a bad",
      "shortcut and the default policy reflects that.",
    ],
  )
  var maxAttempts: Int = 1

  @Option(
    names = ["--trail-out"],
    paramLabel = "<path>",
    description = [
      "Write the generated trail YAML to <path> for debugging.",
      "Default: ./.waypoints_shortcut/verify/<shortcut-id>.trail.yaml",
    ],
  )
  var trailOut: File? = null

  @Option(
    names = ["--trailblaze-bin"],
    paramLabel = "<path>",
    description = [
      "Override the `trailblaze` binary used for the inner trail run. Default:",
      "./trailblaze (so framework changes are picked up). CI sets this to the",
      "installed-distribution binary to mirror the end-user code path.",
    ],
  )
  var trailblazeBin: String = "./trailblaze"

  @Option(
    names = ["--timeout-seconds"],
    paramLabel = "<s>",
    description = [
      "Per-attempt timeout in seconds for the inner `trailblaze run` subprocess.",
      "Default: 600 (10 min). A wedged trail run (device disconnect, ADB hung, runtime",
      "stuck on settle) is destroyed and returns exit code 124 so the outer bootstrap can",
      "move on. Without a timeout, a single stuck replay would block the rest of v1's",
      "sequential top-K loop indefinitely.",
    ],
  )
  var timeoutSeconds: Int = 600

  override fun call(): Int {
    // Resolve --device through the shared four-tier chain so the inner `trailblaze run`
    // subprocess gets a real device id rather than an empty arg + cryptic "Device ''
    // not found" failure mid-pipeline. The chain (flag → TRAILBLAZE_DEVICE env →
    // autodetect-single-connected-device → error envelope) is the same one every other
    // CLI device command uses. `resolveDeviceOrErrorBlocking` emits the right envelope
    // on miss; we propagate the matching exit code (MISUSE for 0/multiple-devices,
    // INFRA_FAILED for daemon-unreachable).
    val resolvedDeviceId = when (val r = resolveDeviceOrErrorBlocking(flag = deviceId, verb = "Shortcut verify")) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return r.exitCodeFallback()
    }
    if (!yamlPath.isFile) {
      Console.error("--yaml must be an existing file: ${yamlPath.absolutePath}")
      return TrailblazeExitCode.MISUSE.code
    }
    if (maxAttempts < 1) {
      Console.error("--max-attempts must be >= 1, got $maxAttempts")
      return TrailblazeExitCode.MISUSE.code
    }
    if (timeoutSeconds < 1) {
      Console.error("--timeout-seconds must be >= 1, got $timeoutSeconds")
      return TrailblazeExitCode.MISUSE.code
    }
    val raw = yamlPath.readText()
    val cfg = try {
      WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), raw)
    } catch (e: Exception) {
      Console.error("Failed to parse shortcut YAML ${yamlPath.absolutePath}: ${e.message}")
      return TrailblazeExitCode.MISUSE.code
    }
    // Run the schema validator the runtime would otherwise apply at load time. Catches
    // malformed `from`/`to` shapes, missing required fields, mode conflicts, etc., with
    // the same actionable diagnostics other entry points produce. Skipping this turns
    // simple schema mistakes into hard-to-trace replay failures later.
    try {
      cfg.validate()
    } catch (e: Exception) {
      Console.error("Shortcut YAML failed schema validation: ${e.message}")
      return TrailblazeExitCode.MISUSE.code
    }
    val shortcut = cfg.shortcut ?: run {
      Console.error("File ${yamlPath.absolutePath} is not a shortcut yaml (no `shortcut:` block).")
      return TrailblazeExitCode.MISUSE.code
    }

    val toolsBlock = extractToolsBlock(raw) ?: run {
      Console.error("Could not locate `tools:` block in ${yamlPath.absolutePath}.")
      return TrailblazeExitCode.MISUSE.code
    }

    val trailYaml = buildTrailYaml(
      shortcutId = cfg.id,
      from = shortcut.from,
      to = shortcut.to,
      toolsBlock = toolsBlock,
    )
    val trailFile = trailOut ?: File(".waypoints_shortcut/verify/${cfg.id}.trail.yaml")
    try {
      trailFile.parentFile?.mkdirs()
      trailFile.writeText(trailYaml)
    } catch (e: Exception) {
      // Mirrors the typed-USAGE pattern used by the yaml parse + cfg.validate() paths
      // above. Disk-full, permission revocation between mkdirs() and writeText(), or a
      // parent-dir race would otherwise crash the verify command with a raw stack
      // trace — surface a clear message instead.
      Console.error("Failed to write generated trail YAML to ${trailFile.absolutePath}: ${e.message}")
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    Console.log("Wrote generated trail to ${trailFile.absolutePath}.")

    // Simple retry policy: any non-zero exit code is a failure, retry up to
    // maxAttempts. v1 deliberately doesn't try to distinguish infrastructure-vs-test
    // failures by exit code — picocli's SOFTWARE=1 covers both, so any classification
    // would be wrong. Default `maxAttempts=1` means no retries; callers investigating
    // known transient flake can override.
    var attempt = 0
    var lastExit = -1
    while (attempt < maxAttempts) {
      attempt++
      Console.log("Verify attempt $attempt/$maxAttempts...")
      val exit = runTrail(trailFile, resolvedDeviceId)
      lastExit = exit
      if (exit == 0) {
        Console.log("Verify PASSED on attempt $attempt.")
        return 0
      }
      Console.log("Verify attempt $attempt FAILED (exit=$exit).")
    }
    Console.error("Verify exhausted $maxAttempts attempt(s); last exit=$lastExit.")
    return lastExit
  }

  private fun runTrail(trailFile: File, resolvedDeviceId: String): Int {
    val cmd = listOf(trailblazeBin, "run", "--device", resolvedDeviceId, trailFile.absolutePath)
    Console.log("Running: ${cmd.joinToString(" ")} (timeout ${timeoutSeconds}s)")
    return runSubprocessWithTimeout(cmd, timeoutSeconds)
  }

  /**
   * The subprocess timeout/destroy/destroyForcibly cascade — extracted from
   * [runTrail] so it can be unit-tested with a `sleep` command (no `./trailblaze`
   * binary needed). Returns the process's exit value on a clean wait, or
   * [TIMEOUT_EXIT_CODE] on timeout. Visible as `internal` for the test.
   *
   * The grace periods (5s post-destroy, 5s post-destroyForcibly) are chosen so a
   * well-behaved subprocess shutting down on SIGTERM has time to flush logs before
   * we escalate, but a wedged process still loses its agent slot in a bounded
   * window (~10s after the initial timeout).
   */
  internal fun runSubprocessWithTimeout(cmd: List<String>, timeoutSecs: Int): Int {
    val process = ProcessBuilder(cmd)
      .inheritIO()
      .start()
    val finished = process.waitFor(timeoutSecs.toLong(), java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
      // A wedged inner `trailblaze run` (device disconnect, ADB hung, runtime stuck on
      // settle) would otherwise block the whole pipeline. Destroy the process, give it a
      // brief grace period, then escalate to destroyForcibly() so the JVM doesn't keep
      // a zombie around. Return a non-zero exit so the bootstrap moves on to the next
      // proposal.
      Console.error("Verify subprocess exceeded ${timeoutSecs}s timeout; destroying.")
      process.destroy()
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly()
        // If destroyForcibly also doesn't return within 5s the process is likely stuck
        // in native code or has a zombie state the JVM can't reach. Log loudly so an
        // SRE seeing a stranded process on the Buildkite agent has a breadcrumb.
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
          Console.error(
            "Subprocess still alive after destroyForcibly grace period; " +
              "may leak a process on this agent.",
          )
        }
      }
      return TIMEOUT_EXIT_CODE
    }
    return process.exitValue()
  }

  /**
   * Generated trail YAML — same shape as the hand-authored example trails in
   * `trails/clock/...`. Inlines the shortcut's `tools:` block verbatim
   * (with reindentation to fit the trail's nested `recording.tools:` position) so
   * we don't need a round-trip through `ToolYamlConfig` re-serialization.
   */
  private fun buildTrailYaml(
    shortcutId: String,
    from: String,
    to: String,
    toolsBlock: String,
  ): String {
    val reindentedTools = reindent(toolsBlock, addPrefix = "        ")
    // Route every interpolated scalar through TrailblazeNodeSelectorYamlEmitter.yamlQuote
    // so a backslash or double-quote in `shortcutId` / `from` / `to` / `driverId`
    // produces well-formed YAML. Without this, a `"` in any value breaks the generated
    // trail's parse step and the failure surfaces as a cryptic kaml diagnostic at
    // replay-time instead of a clear error here. Waypoint ids are slug-like in practice
    // but `--driver` is a CLI input we don't shape-check, and `shortcutId` is `cfg.id`
    // from a user-editable yaml — both can legally carry quote/backslash characters.
    //
    // The `step:` descriptive lines deliberately do NOT interpolate `from` / `to` —
    // those values appear (yamlQuote-wrapped) on the `waypoint:` postcondition lines
    // below, which are the load-bearing assertions. Folding them into a free-text
    // `step:` scalar too would mean every interpolation site has to be wrapped, with
    // no benefit beyond duplicating what the postcondition already says.
    return buildString {
      appendLine("# Auto-generated by `trailblaze waypoint shortcut verify` — do not edit.")
      appendLine("- config:")
      appendLine("    id: ${TrailblazeNodeSelectorYamlEmitter.yamlQuote("$shortcutId-verify")}")
      appendLine("    driver: ${TrailblazeNodeSelectorYamlEmitter.yamlQuote(driverId)}")
      appendLine("- prompts:")
      appendLine("    - step: Confirm the starting waypoint matches the shortcut's `from`")
      appendLine("      postcondition:")
      appendLine("        waypoint: ${TrailblazeNodeSelectorYamlEmitter.yamlQuote(from)}")
      appendLine("    - step: Run shortcut body and assert post-condition")
      appendLine("      postcondition:")
      appendLine("        waypoint: ${TrailblazeNodeSelectorYamlEmitter.yamlQuote(to)}")
      appendLine("      recording:")
      appendLine("        tools:")
      append(reindentedTools)
      if (!reindentedTools.endsWith("\n")) appendLine()
    }
  }

  /**
   * Slice everything that comes after the top-level `tools:` line in [raw]. The
   * generated shortcut YAML always emits `tools:` as the last top-level block, so a
   * suffix slice is correct. For hand-edited files this is robust as long as the
   * convention holds.
   */
  internal fun extractToolsBlock(raw: String): String? {
    val lines = raw.lines()
    val idx = lines.indexOfFirst { it.trimEnd() == "tools:" }
    if (idx < 0) return null
    val body = lines.drop(idx + 1)
    // Drop trailing blank lines so the inlined block stays tight.
    return body.dropLastWhile { it.isBlank() }.joinToString("\n")
  }

  /**
   * Re-indents [block]'s lines so each one starts with [addPrefix]. Blank lines stay
   * blank (no trailing whitespace) — keeps the generated YAML lint-clean.
   */
  internal fun reindent(block: String, addPrefix: String): String =
    block.lines().joinToString("\n") { if (it.isBlank()) "" else "$addPrefix$it" }

  companion object {
    /**
     * Distinct exit code returned when [runTrail] kills a wedged subprocess via the
     * timeout path. Chosen outside picocli's reserved range (0=OK, 1=SOFTWARE, 2=USAGE)
     * so a downstream consumer that wants to distinguish "timeout" from a regular
     * non-zero exit can do so. Not used for retry classification — the simplified
     * retry policy (devlog 2026-05-19) treats any non-zero as failure.
     */
    const val TIMEOUT_EXIT_CODE: Int = 124
  }
}
