package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.cli.TrailblazeExitCode.INFRA_FAILED
import xyz.block.trailblaze.cli.TrailblazeExitCode.MISUSE
import xyz.block.trailblaze.cli.TrailblazeExitCode.SUCCESS
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigResolver
import xyz.block.trailblaze.config.project.WorkspaceContentHasher
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Paths

// ---------------------------------------------------------------------------
// Shared error envelopes
// ---------------------------------------------------------------------------

/**
 * Emit the structured "daemon unreachable" envelope. Every command surfaces the same
 * three-line shape (`✗ … / reason / hint`) when the daemon-IPC bridge can't
 * be reached — whether the daemon is genuinely down, the auto-start failed,
 * or a stale daemon couldn't be swapped out.
 */
internal fun reportDaemonUnreachable(reason: String = "Trailblaze daemon is not running") {
  reportCliError(
    verb = "Daemon connection",
    reason = reason,
    hint = "is the Trailblaze daemon running? try `trailblaze app start`",
  )
}


/**
 * Read the persisted `require-steps` gate, fail-open on read failure.
 *
 * Single read site so [requireStepIfConfigured] and the `device connect` OOBE
 * upsell agree on what "the gate is on" means without duplicating the
 * try/catch + null-coalesce dance. Returns `false` when the config is
 * unreadable for any reason; a `Console.log` line records the underlying
 * cause so a user who deliberately set the gate isn't left wondering why it
 * stopped enforcing. The fail-open choice is deliberate — `require-steps` is
 * a quality affordance, not a security boundary, and silently blocking every
 * `tool` / `step` invocation because the settings file got mangled would be
 * worse for the OOBE story than letting a permissive run through. The log
 * line is the audit trail.
 */
internal fun requireStepsEnabled(): Boolean {
  return try {
    CliConfigHelper.readConfig()?.requireSteps == true
  } catch (e: Exception) {
    // Tag matches the user-facing config key (`require-steps`) so a grep on the
    // CLI's diagnostic stream can correlate the log to the setting the user
    // actually typed, rather than the function name behind it.
    Console.log(
      "[require-steps] config read failed (${e::class.simpleName}: ${e.message}); " +
        "defaulting to gate-off. Run `trailblaze config show` to verify your settings."
    )
    false
  }
}

/**
 * Enforce the optional `require-steps` config gate on action commands
 * (`tool`, `step`, `ask`, `verify`).
 *
 * When the user has set `trailblaze config require-steps true`, every action
 * call must carry a non-blank natural-language step (`-s`/`--step`). The
 * description is the durable contract self-heal retries against when the UI
 * changes — relaxing it produces silent slop recordings, which is exactly the
 * anti-pattern Trailblaze is built to prevent.
 *
 * Returns `null` when the gate passes (step provided, or the gate is off);
 * returns a [TrailblazeExitCode.MISUSE] exit code (and emits a teaching error
 * envelope) when the gate fails. Call from a command's `call()` BEFORE
 * dispatching into the daemon so the message reaches the user without
 * polluting the recording.
 */
internal fun requireStepIfConfigured(step: String?, verb: String): Int? {
  if (!step.isNullOrBlank()) return null
  if (!requireStepsEnabled()) return null
  reportCliError(
    verb = verb,
    reason = "missing -s/--step (required by `trailblaze config require-steps true`)",
    hint = "describe what this step does (e.g. -s \"Open Settings\") — Trailblaze " +
      "uses it to retry the step with AI when the UI changes. To relax for ad-hoc " +
      "work: `trailblaze config require-steps false`.",
  )
  return MISUSE.code
}

/**
 * Lightweight classpath scan for target IDs and display names — no full app init needed.
 */
internal fun discoverTargetSummaries(): List<Pair<String, String>> {
  return AppTargetYamlLoader.discoverConfigs()
    .map { it.id to it.displayName }
    .sortedBy { it.second }
}

/**
 * Shared `--target` option description for action commands that bind a device
 * (`tool`, `step`). The exact same wording appears in picocli's `--help`
 * output for both — pre-extraction this string was duplicated verbatim across
 * `StepCommand` and `ToolCommand`, so any wording evolution (e.g. when
 * `TRAILBLAZE_TARGET` was added) had to be applied in two places or the help
 * text drifted between commands.
 *
 * Picocli's `description = [...]` array element must be a compile-time
 * constant for annotation processing, so a `const val` is the only way to
 * share. The string is intentionally a single line — picocli wraps it for
 * help rendering, and the docs generator concatenates `joinToString(" ")`
 * so any embedded line breaks would land in the generated `docs/CLI.md`
 * as literal newlines mid-cell.
 *
 * `SessionCommand` uses a near-twin variant (`session's bound device` /
 * different "list available targets" closer) so it keeps its own const —
 * see [TARGET_OPTION_DESCRIPTION_SESSION].
 */
internal const val TARGET_OPTION_DESCRIPTION: String =
  "Target app ID for this command's bound device. Scoped to the device " +
    "as a daemon-process override (dies on daemon restart or device " +
    "release). Defaults to `\$TRAILBLAZE_TARGET` — typically set via " +
    "`eval \$(trailblaze device connect ... --target X)`. Pass " +
    "`--target=clear` to remove a previously-set override for this " +
    "device. To set a persistent default, use `trailblaze config " +
    "target`. List available targets with `trailblaze toolbox` (no args)."

/**
 * Variant of [TARGET_OPTION_DESCRIPTION] for `session start` — same env-tier
 * + clear-sentinel + persistent-default story, narrower wording because the
 * session command isn't bound to one tool action and doesn't surface the
 * "list available targets with toolbox" pointer.
 */
internal const val TARGET_OPTION_DESCRIPTION_SESSION: String =
  "Target app ID for this session's bound device. Scoped to the device " +
    "as a daemon-process override (dies on daemon restart or device " +
    "release). Defaults to `\$TRAILBLAZE_TARGET` — typically set via " +
    "`eval \$(trailblaze device connect ... --target X)`. Pass " +
    "`--target=clear` to remove a previously-set override. To set a " +
    "persistent default, use `trailblaze config target`."

/**
 * How the effective `--target` value was chosen for one CLI invocation.
 *
 *  - [Explicit] — user passed `--target <name>` on the command line. The CLI should
 *    behave exactly the same as it did before defaulting was introduced (lean output,
 *    no source attribution).
 *  - [EnvVar] — `TRAILBLAZE_TARGET` was exported in the calling shell (typically via
 *    `eval $(trailblaze device connect ... --target X)`). Mirrors the `TRAILBLAZE_DEVICE`
 *    pattern — per-shell pin that survives daemon restart and the session-claim
 *    revocation that wipes the daemon-side per-device override (the "Bug B" from
 *    PR #3463: `SessionTargetRegistry` is keyed on the recording session, which is
 *    torn down whenever a fresh CLI invocation re-claims the device).
 *  - [WorkspaceConfig] — the user configured a target via `trailblaze config target`
 *    (so [xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig.selectedTargetAppId]
 *    is non-null on disk). Surfaced in resolved-target headers as "from workspace config".
 *  - [BuiltinDefault] — none of the above; we fell back to
 *    [xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget].
 *    Surfaced as "built-in default" so the user understands the value isn't pinned.
 *
 * Lives in the infrastructure layer (rather than a per-command file) because the
 * four-way distinction is the same for every command that wants source attribution
 * — toolbox is the first consumer, but future commands (e.g. a `--show-target`
 * variant of `tool`) reuse the same resolution.
 */
internal enum class ResolvedCliTargetSource(
  /**
   * The user-facing phrase that follows "no --target specified;" in resolved-
   * target headers (e.g. `toolbox`'s discovery banner). `null` for [Explicit]
   * because explicit-flag invocations suppress the header entirely — there's
   * no source to attribute.
   *
   * Co-locating the label with the enum constant means a future source (per-
   * device disk cache, MCP-session pin, …) only has to declare its label here
   * once; every command that renders the header (`toolbox` today, future
   * `--show-target` variants of `tool` / `step`) gets the right attribution
   * without per-call-site `when` dispatch.
   */
  val attributionLabel: String?,
) {
  Explicit(attributionLabel = null),
  EnvVar(attributionLabel = "from \$TRAILBLAZE_TARGET"),
  WorkspaceConfig(attributionLabel = "from workspace config"),
  BuiltinDefault(attributionLabel = "built-in default"),
}

/**
 * Resolves the effective `--target` value plus the source it came from, in one
 * place, for any CLI command that wants the four-tier resolution
 * (`flag → TRAILBLAZE_TARGET env → workspace config → built-in default`).
 *
 * Returns the resolved id and the source — callers decide whether to render a
 * header, suppress one, etc. Distinct from [cliWithDaemon]'s `targetAppId` read,
 * which collapses the four-tier resolution into a single non-null id; this
 * helper preserves the distinction between "user explicitly set this" and "we
 * fell back to the framework default" so the CLI surface can communicate it.
 */
internal data class ResolvedCliTarget(
  val id: String,
  val source: ResolvedCliTargetSource,
)

internal fun resolveCliTarget(flag: String?): ResolvedCliTarget {
  // Normalize the explicit flag the same way [resolveCliTargetPin] does
  // (trim + lowercase) so logged ids, session-file comparisons, and daemon
  // arguments stay consistent regardless of which resolver a caller picks.
  // Without this, `toolbox --target SquareApp` would keep the camelcase id
  // here while `tool --target SquareApp` lowercases via the pin resolver —
  // both paths still work because daemon lookup is case-insensitive, but
  // future case-sensitive code or logs would silently disagree.
  normalizeTargetId(flag)?.let {
    return ResolvedCliTarget(it, ResolvedCliTargetSource.Explicit)
  }
  // TRAILBLAZE_TARGET env var — set via `eval $(trailblaze device connect ... --target X)`
  // or directly in the shell, parallel to TRAILBLAZE_DEVICE. Wins over workspace config
  // so per-shell pinning beats per-project default in the multi-terminal case.
  envTrailblazeTarget()?.let {
    return ResolvedCliTarget(it, ResolvedCliTargetSource.EnvVar)
  }
  // readConfigRaw (not getOrCreateConfig) so we can tell "user set it" from "the
  // framework hydrated null → 'default' on read." The hydration is convenient for
  // the daemon path but loses the user-intent signal we need for source attribution.
  val rawSetting = CliConfigHelper.readConfigRaw()?.selectedTargetAppId
  if (rawSetting != null) {
    return ResolvedCliTarget(rawSetting, ResolvedCliTargetSource.WorkspaceConfig)
  }
  return ResolvedCliTarget(
    xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
    ResolvedCliTargetSource.BuiltinDefault,
  )
}

/**
 * Resolves the effective per-device target *pin* — the value an action command
 * (`tool`, `step`, `snapshot`, …) should re-apply via
 * [CliMcpClient.setSessionTargetForBoundDevice] on every invocation so the
 * daemon's per-device override survives session-claim revocation.
 *
 * Two-tier resolution: explicit `--target` flag wins over the `TRAILBLAZE_TARGET`
 * env var. Workspace config and built-in default are **not** consulted because
 * those values are already the daemon-wide fallback — writing them as per-device
 * overrides would be redundant noise on every CLI call.
 *
 * Returns null when neither tier supplies a value, signalling "leave the
 * per-device override alone — daemon-wide fallback will resolve correctly."
 *
 * The literal sentinel `"clear"` (case-insensitive) is **flag-only**, and
 * critically it **short-circuits** the env tier: when the user passes
 * `--target=clear` we MUST NOT fall through to `$TRAILBLAZE_TARGET`. Falling
 * through would silently re-establish the very pin the user is trying to
 * clear — the opposite of the user's stated intent. An env pin of
 * `TRAILBLAZE_TARGET=clear` is independently treated as unset (see
 * [envTrailblazeTarget]) because env-pin clears should be done by
 * `unset TRAILBLAZE_TARGET` instead.
 *
 * Direct callers — including [McpCommand]'s `initialTarget` — get this
 * short-circuit for free. `cliReusableWithDevice` and `SessionStartCommand`
 * additionally translate the flag-clear into an empty-string daemon call so
 * the per-device override is wiped on the daemon side; they wrap their
 * `resolveCliTargetPin` call in `if (isClearRequest) null else …` for clarity,
 * but the short-circuit here is what makes those wraps redundant rather than
 * load-bearing.
 */
internal fun resolveCliTargetPin(flag: String?): String? {
  val normalized = normalizeTargetId(flag)
  // `--target=clear` is the explicit "remove the per-device override" signal.
  // Returning null here (rather than falling through to the env tier) is what
  // keeps the clear deterministic regardless of what `$TRAILBLAZE_TARGET` holds.
  if (normalized == "clear") return null
  if (normalized != null) return normalized
  return envTrailblazeTarget()
}

/**
 * Resolves the payload an action command (`tool`, `step`, `session start`)
 * should pass to [CliMcpClient.setSessionTargetForBoundDevice], collapsing the
 * three callsite-local decisions into one helper:
 *
 *  - `--target=clear` → empty string (daemon contract: empty wipes the override).
 *  - explicit pin via flag or `$TRAILBLAZE_TARGET` → the pin value.
 *  - neither → null (skip the daemon call entirely; leave the existing
 *    per-device override alone, falling back to the daemon-wide default).
 *
 * Pre-PR there were two near-identical `when` blocks in `cliReusableWithDevice`
 * and `SessionStartCommand` computing this same shape from `(isClearRequest,
 * pinnedTarget)` locals. Extracted here so a third action command that wants
 * the same semantics doesn't copy the logic a third time, and so the
 * `clear` → empty-string convention is documented in one place instead of
 * implied by two `when` branches.
 *
 * Returns `Pair(daemonPayload, pinIfAny)`: callers usually only need the
 * `daemonPayload`, but a few also need the resolved pin (e.g. the session-
 * file invalidation check anchors on the pin value, not on the empty-string
 * clear). Splitting both halves into the result avoids re-running the same
 * normalization in the caller.
 */
internal data class CliTargetDaemonCall(
  /** The string to pass to `setSessionTargetForBoundDevice`, or null to skip the call. */
  val payload: String?,
  /** The resolved pin (flag or env), or null if no pin / clear request. */
  val pin: String?,
  /** True when the user requested an explicit `--target=clear`. */
  val isClearRequest: Boolean,
)

internal fun resolveCliTargetDaemonCall(flag: String?): CliTargetDaemonCall {
  val normalizedFlag = normalizeTargetId(flag)
  val isClearRequest = normalizedFlag == "clear"
  val pin = if (isClearRequest) null else resolveCliTargetPin(flag)
  val payload = when {
    isClearRequest -> ""
    pin != null -> pin
    else -> null
  }
  return CliTargetDaemonCall(payload = payload, pin = pin, isClearRequest = isClearRequest)
}

/**
 * Shared normalization for every CLI surface that handles a raw target id —
 * `--target` flags, the `TRAILBLAZE_TARGET` env, and `device connect --target X`
 * exports. Applies the canonical chain: `trim()` → `lowercase()` → blank → null.
 *
 * Pre-refactor this exact expression was open-coded in five places
 * (`resolveCliTarget`, `resolveCliTargetPin`, `resolveCliTargetDaemonCall`,
 * `DeviceConnectCommand.resolvedTarget`, `DeviceRebindCommand.newTarget`).
 * If one site drifts — e.g. forgets the `trim()` — the env-pin / daemon-bind
 * round trip silently disagrees on whitespace and case, which is exactly the
 * regression Copilot caught on the first PR pass. Centralizing here makes
 * adding a sixth caller a single import.
 *
 * Does NOT filter the `"clear"` sentinel — that's flag-specific semantics
 * (see [resolveCliTargetPin]) and the env tier rejects it independently in
 * [envTrailblazeTarget]. Keeping the `"clear"` filter at the calling layer
 * (rather than baking it into the normalizer) keeps this helper reusable for
 * any future target-id surface that wants the canonical normalization without
 * the sentinel handling.
 */
internal fun normalizeTargetId(raw: String?): String? =
  raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

/**
 * Reads `TRAILBLAZE_TARGET` from the caller's shell env with the same
 * normalization the rest of the CLI uses on target ids (via [normalizeTargetId])
 * **plus** the env-tier-only filter that treats the literal `"clear"` as unset
 * (see [resolveCliTargetPin] for why `"clear"` is flag-only).
 *
 * Mirrors the `TRAILBLAZE_DEVICE` env-var pattern from [resolveCliDevice] so
 * env-driven workflows ("`eval $(trailblaze device connect ... --target X)` in
 * the shell, then any number of `trailblaze tool ...`" calls) are deterministic
 * across CLI invocations regardless of the daemon-side `SessionTargetRegistry`
 * lifecycle.
 *
 * Reads via [CliCallerContext.callerEnv] (not `System.getenv` directly) so the
 * value resolves correctly on the daemon-forwarded path (`/cli/exec`) too —
 * `snapshot`, `ask`, and `config` run in the daemon's JVM whose env was
 * captured at `app start` time and never sees an `export TRAILBLAZE_TARGET=…`
 * the user did afterward. Same fix shape as PR #3478 applied to
 * `TRAILBLAZE_DEVICE`; without this, `TRAILBLAZE_TARGET` set via #3473
 * would be invisible to those forwarded subcommands — even though they
 * don't take a `--target` flag themselves, their downstream target
 * resolution (tool-availability gates, scoped tool listing) would silently
 * fall back to the daemon-wide setting instead of honoring the shell pin
 * the user set via `eval $(trailblaze device connect ... --target X)`.
 */
private fun envTrailblazeTarget(): String? =
  normalizeTargetId(CliCallerContext.callerEnv("TRAILBLAZE_TARGET"))?.takeIf { it != "clear" }

/**
 * Resolves the effective device spec for one CLI invocation, with a layered fallback:
 *
 *  1. Explicit `--device <foo>` flag — wins when passed and non-blank
 *  2. `TRAILBLAZE_DEVICE` env var — the per-shell ambient written by
 *     `eval $(trailblaze device connect ...)` so subsequent calls in the same
 *     terminal don't have to repeat the flag
 *  3. `null` — caller decides whether to error (most action commands) or
 *     proceed without a device (a few lookup commands that don't need one)
 *
 * Lives in the infrastructure layer so every device-binding command (`tool`,
 * `step`, `snapshot`, `ask`, `verify`, `session stop/save/end`) resolves
 * identically. The env read goes through [CliCallerContext.callerEnv], which
 * prefers the per-thread caller env (populated by the bash shim's
 * `CliExecRequest.env` on the daemon-forwarded path) before falling back to
 * `System.getenv` for direct-JVM invocations. Without the thread-local check,
 * forwarded subcommands (`snapshot`, `ask`, `config`) would see the daemon's
 * stale captured env and miss every `export TRAILBLAZE_DEVICE=…` the user did
 * after `app start` — the exact bug that made `trailblaze snapshot` fail with
 * "multiple devices connected" right after the user followed the error's own
 * `eval $(trailblaze device connect …)` hint.
 *
 * Note: blank values (empty string, whitespace) are treated as unset on BOTH
 * sources so a malformed `eval $(...)` (e.g. running it after the daemon
 * cleared the binding) doesn't pin the shell to an unusable empty device.
 */
internal fun resolveCliDevice(flag: String?): String? =
  flag?.takeIf { it.isNotBlank() }
    ?: CliCallerContext.callerEnv("TRAILBLAZE_DEVICE")?.takeIf { it.isNotBlank() }

/**
 * Outcome of a connected-device autodetect probe — the tier consulted after
 * [resolveCliDevice] returns null. Sealed so callers can branch deterministically
 * on the four user-visible scenarios and emit a context-specific error envelope
 * (or use the resolved spec) without the autodetect plumbing leaking into the
 * wording.
 */
internal sealed class DeviceAutodetectResult {
  /** Exactly one device connected; safe to use without ambiguity. */
  data class Resolved(val deviceSpec: String) : DeviceAutodetectResult()
  /** No devices connected — caller should advise the user to start one. */
  data object NoDevices : DeviceAutodetectResult()
  /** Multiple devices connected — caller should list them and ask to pick. */
  data class Multiple(val specs: List<String>) : DeviceAutodetectResult()
  /**
   * Daemon was not running or device listing failed. The [alreadyReported]
   * flag distinguishes the two failure modes so the caller doesn't print a
   * second daemon-unreachable envelope on top of one that
   * [connectOrStartDaemonOneShot] already emitted:
   *  - `true`: helper already called [reportDaemonUnreachable] (connect /
   *    auto-start / stale-daemon path). Caller must NOT report again.
   *  - `false`: connected fine but the list call itself failed mid-flight
   *    (e.g. tool returned `isError`, or the transport threw). Caller still
   *    owns the envelope.
   */
  data class DaemonUnreachable(val alreadyReported: Boolean) : DeviceAutodetectResult()
}

/**
 * Queries the daemon for connected devices and reports the autodetect outcome.
 *
 * Opens a short-lived one-shot MCP session (auto-starting the daemon if needed),
 * calls `device` LIST, and classifies the result. Never throws — transport
 * errors yield [DeviceAutodetectResult.DaemonUnreachable] so callers can format
 * a clean envelope without try/catch boilerplate.
 *
 * Used as the **last tier** of device resolution when neither `--device` nor
 * `TRAILBLAZE_DEVICE` supplied one. The intent: a single-device user can run
 * `trailblaze snapshot` (or any other device command) with zero setup —
 * `eval $(trailblaze device connect …)` becomes a multi-device pin rather than
 * a single-device requirement. Multi-terminal isolation is preserved because
 * env-var-pinned shells skip this path entirely (they have a non-null result
 * from [resolveCliDevice] before the autodetect runs).
 *
 * Costs one extra daemon round-trip in the autodetect-success case (oneshot
 * MCP connect → tool call → close). Cheap enough to not warrant caching.
 */
internal suspend fun autodetectSingleConnectedDevice(port: Int): DeviceAutodetectResult {
  // connectOrStartDaemonOneShot already prints its own daemon-unreachable
  // envelope on failure, so flag this branch as already-reported to keep
  // the caller from doubling up the message.
  val client = connectOrStartDaemonOneShot(port)
    ?: return DeviceAutodetectResult.DaemonUnreachable(alreadyReported = true)
  return client.use {
    try {
      val result = it.callTool("device", mapOf("action" to "LIST"))
      if (result.isError) return@use DeviceAutodetectResult.DaemonUnreachable(alreadyReported = false)
      // Filter out the always-present virtual web device. The daemon's device
      // LIST unconditionally includes `web/playwright-native` (a virtual entry
      // provisioned on demand, no hardware connection required — see
      // TrailblazeDeviceManager.loadDevicesSuspendImpl). Counting it toward
      // autodetect would mis-classify the common case "1 emulator + 0 browsers"
      // as `Multiple`, and "0 emulators" as `Resolved(web)` — neither matches
      // user intent. Authoring a web trail explicitly via `--device web` or
      // `eval $(trailblaze device connect web)` still works; we just don't
      // pick it implicitly.
      val devices = with(CliMcpClient) { parseDeviceList(result.content).filterRealDevices() }
      when (devices.size) {
        0 -> DeviceAutodetectResult.NoDevices
        1 -> DeviceAutodetectResult.Resolved(devices.single().toFullyQualifiedDeviceId())
        else -> DeviceAutodetectResult.Multiple(devices.map { it.toFullyQualifiedDeviceId() })
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Never swallow cancellation — propagate so Ctrl+C / structured
      // cancellation surfaces as a cancel, not a "daemon unreachable"
      // false positive.
      throw e
    } catch (_: Exception) {
      DeviceAutodetectResult.DaemonUnreachable(alreadyReported = false)
    }
  }
}

/**
 * Emits the standard stderr notice when device autodetect resolved to a
 * single connected device. Shared by [resolveDeviceWithAutodetect] and the
 * `trail` command (which inlines its own four-tier chain — see [TrailCommand])
 * so the wording stays consistent across both paths.
 *
 * Stderr (not stdout) so the notice survives pipelines like
 * `trailblaze snapshot | jq ...` without corrupting the JSON stream.
 */
internal fun reportAutodetectedDevice(deviceSpec: String) {
  Console.error("Auto-using only connected device: $deviceSpec")
}

/**
 * Outcome of the layered device resolver — distinguishes the cases that map to
 * different [TrailblazeExitCode] values so callers don't conflate misuse
 * (0/multiple devices, no flag, no env) with infrastructure failures (daemon
 * down / list call threw). Per the CLI exit-code policy, mapping daemon failures
 * to MISUSE would be wrong — a stopped daemon isn't a user error.
 */
internal sealed class DeviceResolution {
  data class Resolved(val deviceSpec: String) : DeviceResolution()
  /** User-correctable: 0 devices, 2+ devices, or any other "you told us wrong". */
  data object Misuse : DeviceResolution()
  /** Daemon unreachable / list call failed — exit with [INFRA_FAILED]. */
  data object InfraFailed : DeviceResolution()

  /** Caller-side convenience for `?: return <code>` patterns. */
  fun deviceSpecOrNull(): String? = (this as? Resolved)?.deviceSpec

  /** Map the non-Resolved cases to a `TrailblazeExitCode.code`. */
  fun exitCodeFallback(): Int = when (this) {
    is Resolved -> SUCCESS.code
    Misuse -> MISUSE.code
    InfraFailed -> INFRA_FAILED.code
  }
}

/**
 * Full device-resolution chain for CLI commands that need exactly one device:
 *
 *   1. Explicit `--device` flag (wins)
 *   2. `TRAILBLAZE_DEVICE` env var (per-shell pin via
 *      `eval $(trailblaze device connect <platform>)`)
 *   3. **NEW:** autodetect when exactly one device is connected — closes the
 *      OOBE gap for single-device users (zero setup needed) without breaking
 *      multi-terminal use cases (env-var pinning still wins).
 *
 * On miss, emits the appropriate error envelope and returns a non-Resolved
 * [DeviceResolution] variant so the caller can exit with the right code:
 *  - 0 devices / 2+ devices: [DeviceResolution.Misuse] (→ [MISUSE])
 *  - Daemon down: [DeviceResolution.InfraFailed] (→ [INFRA_FAILED])
 *
 * Emits a one-line stderr notice on autodetect success so the user sees which
 * device was picked.
 */
internal suspend fun resolveDeviceWithAutodetect(
  flag: String?,
  port: Int,
  verb: String = "Command",
): DeviceResolution {
  resolveCliDevice(flag)?.let { return DeviceResolution.Resolved(it) }
  return when (val r = autodetectSingleConnectedDevice(port)) {
    is DeviceAutodetectResult.Resolved -> {
      reportAutodetectedDevice(r.deviceSpec)
      DeviceResolution.Resolved(r.deviceSpec)
    }
    is DeviceAutodetectResult.NoDevices -> {
      reportCliError(
        verb = verb,
        reason = "no devices connected",
        hint = "connect a device first (Android: USB or emulator; iOS: simulator " +
          "via Xcode; web: always available), or run " +
          "`eval \$(trailblaze device connect <platform>)`",
      )
      DeviceResolution.Misuse
    }
    is DeviceAutodetectResult.Multiple -> {
      reportCliError(
        verb = verb,
        reason = "multiple devices connected — --device is required to pick one",
        hint = "available: ${r.specs.joinToString(", ")}. Pass `--device <id>` or " +
          "run `eval \$(trailblaze device connect <id>)` once to pin this shell",
      )
      DeviceResolution.Misuse
    }
    is DeviceAutodetectResult.DaemonUnreachable -> {
      // Only emit the envelope if the underlying helper didn't already do so.
      // `connectOrStartDaemonOneShot` reports itself on connect/start/stale
      // failures; we only own the message for mid-flight list errors.
      if (!r.alreadyReported) {
        reportDaemonUnreachable("daemon device listing failed — cannot autodetect")
      }
      DeviceResolution.InfraFailed
    }
  }
}

/**
 * Synchronous wrapper around [resolveDeviceWithAutodetect] for callers that
 * aren't already inside a coroutine scope. Wraps the suspending resolver in
 * [runBlocking] and reads the daemon port from the standard config helper, so
 * the caller passes only the user-facing context they have ([flag] + [verb]).
 *
 * Use from picocli `call()` methods that aren't entering a `cliReusable`/
 * `cliOneShot` wrapper themselves — e.g. `ToolboxCommand`, `TrailCommand`,
 * `WaypointShortcutVerifyCommand`, the `session` subcommands. The wrappers in
 * [cliReusableWithDevice] / [cliOneShotWithDevice] use the suspending form
 * directly since they're already inside a `runBlocking`.
 */
internal fun resolveDeviceOrErrorBlocking(flag: String?, verb: String): DeviceResolution {
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  return runBlocking { resolveDeviceWithAutodetect(flag = flag, port = port, verb = verb) }
}

/**
 * Resolves the effective device for a session-lifecycle subcommand. Thin alias
 * over [resolveDeviceOrErrorBlocking] — same three-tier chain (flag → env →
 * autodetect-single-connected-device → error envelope) as every other CLI
 * device command. Kept under the session-specific name so the call sites in
 * `session start` / `session stop` / `session end` stay readable.
 */
internal fun requireSessionDevice(flag: String?, verb: String): DeviceResolution =
  resolveDeviceOrErrorBlocking(flag = flag, verb = verb)

/**
 * Returns true when [userDeviceArg] matches the daemon's currently bound device.
 * Two ways to match, both case-insensitive:
 *
 *  - Fully-qualified — `android/emulator-5554` matches a bound `android/emulator-5554`.
 *  - Platform-only — `android` matches any instance of that platform.
 *
 * Pure function with no I/O. Used by both `session stop` (explicit `--device` arg)
 * and `device disconnect` (env-var-pinned device) to gate ownership-sensitive
 * teardown — if the caller's intended device doesn't match what the daemon has
 * bound, the action refuses rather than affecting a session that belongs to a
 * different shell.
 */
internal fun deviceArgMatches(
  userDeviceArg: String,
  boundDevice: xyz.block.trailblaze.devices.TrailblazeDeviceId,
): Boolean {
  if (userDeviceArg.equals(boundDevice.toFullyQualifiedDeviceId(), ignoreCase = true)) return true
  return userDeviceArg.equals(boundDevice.trailblazeDevicePlatform.name, ignoreCase = true)
}

/**
 * Wraps [value] in POSIX single-quotes so it survives `eval $(...)` even when
 * the value contains whitespace, `$`, `*`, or other shell metacharacters.
 * Embedded single-quotes are escaped via the standard `'\''` close-escape-reopen
 * trick. Used to format `export TRAILBLAZE_DEVICE=…` and `unset TRAILBLAZE_DEVICE`
 * lines so named slots like `web/iPhone 14` round-trip through `eval` cleanly.
 *
 * General-purpose: any future command that wants to emit shell-evaluable output
 * should route the value through this helper rather than printing it raw.
 */
internal fun shellSingleQuote(value: String): String =
  "'" + value.replace("'", "'\\''") + "'"

/**
 * Writes a `export NAME='value'` line to **stdout** with proper POSIX quoting via
 * [shellSingleQuote] so `eval $(trailblaze …)` evaluates cleanly even when the
 * value contains whitespace or shell metacharacters.
 *
 * The convention every device-binding command follows:
 *  - **stdout** = the single shell-evaluable line (this helper).
 *  - **stderr** = everything else — status banners, hints, errors — via
 *    [xyz.block.trailblaze.util.Console.error]. Status on stderr is visible to
 *    the user in interactive mode AND survives `eval $(…)` capture without
 *    causing shell-parse errors.
 *
 * Centralizing the stdout-export pattern here keeps a future command from
 * accidentally interleaving status text with the export line on stdout (which
 * would corrupt `eval $(…)`). New commands that want eval-pinnable output go
 * through this helper.
 */
internal fun printShellExport(varName: String, value: String) {
  // A blank var name produces `export =value`, which is broken shell syntax — `eval $(…)`
  // would fail with a parse error and the user would see a cryptic shell message instead
  // of the actual problem. Treating the call as a contract violation (rather than
  // silently emitting garbage) keeps the failure mode loud and obvious.
  require(varName.isNotBlank()) { "varName must not be blank" }
  // Note: empty [value] is intentionally allowed — `export VAR=''` is a valid shell
  // assignment that clears the variable to the empty string. That's semantically
  // distinct from [printShellUnset] (which removes the variable entirely); callers
  // pick the one that matches their intent.
  println("export $varName=${shellSingleQuote(value)}")
}

/**
 * Writes an `unset NAME` line to **stdout** for `eval $(…)` to clear an env var.
 * Symmetric with [printShellExport] — same stdout-only-for-evaluable-output
 * contract. `unset` doesn't take a quoted value, so no [shellSingleQuote] needed.
 */
internal fun printShellUnset(varName: String) {
  require(varName.isNotBlank()) { "varName must not be blank" }
  println("unset $varName")
}

/**
 * Outcome of a `stopBoundSessionIfMatches` call. Callers decide how each
 * outcome surfaces — `session stop` and `device disconnect` differ in their
 * exit-line shape (env-var unset emission, status framing) but agree on the
 * underlying state-machine.
 */
internal sealed class StopBoundSessionResult {
  /** Daemon has no bound device. Local session file likely stale; caller should clear it. */
  data object NoActiveSession : StopBoundSessionResult()

  /**
   * The caller's expected device doesn't match what the daemon currently has bound —
   * stopping would terminate someone else's session. Refuse and surface the bound id
   * so the user can disambiguate.
   */
  data class DeviceMismatch(
    val boundDevice: xyz.block.trailblaze.devices.TrailblazeDeviceId,
  ) : StopBoundSessionResult()

  /** Stop succeeded. [message] is the daemon's `message` field from the JSON response, if any. */
  data class Stopped(val message: String?) : StopBoundSessionResult()

  /** Daemon-side stop failed. [error] is the raw response content for the caller to surface. */
  data class StopFailed(val error: String) : StopBoundSessionResult()
}

/**
 * Shared "stop the session bound to [expectedDevice]" flow used by both
 * `session stop --device X` and `device disconnect`. Both commands need the
 * same pre-conditions (a session must exist, and it must belong to the caller's
 * device — not another shell's) and the same MCP call. They diverge only in
 * how they format the user-visible outcome and the post-stop env-var cleanup,
 * which stays at the call site.
 *
 *  - [expectedDevice] must be the resolved device id the caller intends to
 *    stop. Resolution (`--device` flag → `TRAILBLAZE_DEVICE` env → null) is
 *    the caller's responsibility — passing a blank/null here is a contract
 *    violation, not a runtime case to handle.
 *  - [extraStopArgs] lets `session stop --save` pass additional arguments
 *    (e.g. `save = true`, `title = "..."`); empty for `device disconnect`.
 */
internal suspend fun stopBoundSessionIfMatches(
  client: CliMcpClient,
  expectedDevice: String,
  extraStopArgs: Map<String, Any?> = emptyMap(),
): StopBoundSessionResult {
  val bound = client.getBoundDeviceId()
    ?: return StopBoundSessionResult.NoActiveSession
  if (!deviceArgMatches(expectedDevice, bound)) {
    return StopBoundSessionResult.DeviceMismatch(bound)
  }
  val args = buildMap<String, Any?> {
    put("action", "STOP")
    putAll(extraStopArgs)
  }
  val result = client.callTool("session", args)
  if (result.isError) {
    return StopBoundSessionResult.StopFailed(result.content)
  }
  val message = runCatching {
    kotlinx.serialization.json.Json.parseToJsonElement(result.content)
      .let { it as? kotlinx.serialization.json.JsonObject }
      ?.get("message")
      ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
      ?.content
  }.getOrNull()
  return StopBoundSessionResult.Stopped(message)
}

// ---------------------------------------------------------------------------
// Daemon connection helpers (shared by both CLIs)
// ---------------------------------------------------------------------------

/**
 * Per-device session scope shared by every CLI command that drives a device
 * (`step`, `tool`, `snapshot`, `ask`, `verify`). All five funnel into the same
 * persisted MCP session for a given device, so a sequence like
 * `blaze "Open Settings"` → `tool tap ref=p386 -o "Tap toggle"` → `ask "Is it
 * on?"` rolls up as ONE Trailblaze recording instead of N fragmented ones.
 *
 * The scope is keyed on the resolved device string passed to `--device`,
 * lowercased so `Android` and `android` collapse to the same scope. Different
 * devices stay isolated (e.g. `cli-android/emulator-5554` vs `cli-ios/SIM-X`).
 */
fun cliDeviceSessionScope(device: String): String = "cli-${device.lowercase()}"

/**
 * Tracks the last CLI session scope used on this daemon port, so that
 * `blaze --save` can locate the session that recorded the most recent CLI
 * activity even when the save invocation omits `--device`. Any CLI command
 * that drives a device should call [writeLastCliSessionScope] after a
 * successful run; [readLastCliSessionScope] is the consumer side used by
 * `blaze --save`.
 */
fun lastCliSessionScopeFile(port: Int): File =
  CliMcpClient.scopedStateFile(prefix = "trailblaze-last-cli-scope", port = port)

fun readLastCliSessionScope(port: Int): String? {
  return try {
    lastCliSessionScopeFile(port).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
  } catch (_: Exception) {
    null
  }
}

fun writeLastCliSessionScope(port: Int, scope: String) {
  try {
    lastCliSessionScopeFile(port).writeText(scope)
  } catch (_: Exception) {
    // Best-effort only — `blaze --save` falls back to requiring --device.
  }
}

/**
 * Wrapper for **stateless one-shot** CLI device commands.
 *
 * Currently unused by device-driving commands — `tool`, `snapshot`, `ask`,
 * `verify`, `step` all use [cliReusableWithDevice] so their steps roll up
 * into one recorded session per device. Kept for future commands that
 * genuinely need an isolated, non-recorded MCP session (e.g. read-only
 * diagnostics that should not appear in the user's session list).
 *
 * Each invocation:
 *  - opens a fresh MCP session (no session file I/O),
 *  - binds the explicitly requested `--device`,
 *  - runs [action],
 *  - tears the MCP session down.
 */
fun cliOneShotWithDevice(
  verbose: Boolean,
  device: String?,
  webHeadless: Boolean = true,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  val port = CliConfigHelper.resolveEffectiveHttpPort()

  return runBlocking {
    // Three-tier device resolution: explicit --device flag → TRAILBLAZE_DEVICE env
    // → autodetect-single-connected-device. Last tier closes the OOBE gap for
    // single-device users (zero setup needed). On miss, [resolveDeviceWithAutodetect]
    // has already emitted the appropriate envelope; we exit with the right code
    // per [TrailblazeExitCode] policy (MISUSE for 0/multiple-devices,
    // INFRA_FAILED for daemon-unreachable / list-call-threw).
    val resolvedDevice = when (val r = resolveDeviceWithAutodetect(flag = device, port = port)) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return@runBlocking r.exitCodeFallback()
    }

    val mcpClient = connectOrStartDaemonOneShot(port)
      ?: return@runBlocking INFRA_FAILED.code

    mcpClient.use { client ->
      val deviceError = client.ensureDevice(resolvedDevice, webHeadless = webHeadless)
      if (deviceError != null) {
        reportCliError(
          verb = "Device bind",
          target = resolvedDevice,
          reason = deviceError,
          hint = "check `trailblaze device` for the list of connected devices",
        )
        return@runBlocking INFRA_FAILED.code
      }
      runActionWithIoEnvelope(target = resolvedDevice, action = { action(client) })
    }
  }
}

/**
 * Wrapper for **stateful/reusable** CLI device commands (`step`).
 *
 * Each invocation reattaches to the persisted MCP session under [sessionScope]
 * (creating a fresh one if there is none, or recovering from daemon restart),
 * so follow-up commands like `blaze --save` can reach the same recorded steps.
 * Device-claim conflicts follow the daemon's yield-unless-busy policy.
 */
fun cliReusableWithDevice(
  verbose: Boolean,
  device: String?,
  webHeadless: Boolean = true,
  /**
   * Optional `--target` value from the action command's CLI flag. When set,
   * the wrapper tells the daemon (after `ensureDevice` binds the device) to
   * scope the target to the bound device's MCP session — does NOT persist to
   * disk. For persistent target changes the user invokes `config target`
   * explicitly. See [CliMcpClient.setSessionTargetForBoundDevice].
   */
  target: String? = null,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  val config = CliConfigHelper.getOrCreateConfig()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  // Single helper resolves the (payload, pin, isClearRequest) shape so this
  // wrapper and SessionStartCommand stay in lockstep. The helper covers:
  //   - `--target=clear`     → payload "" (daemon empty-string clears the override)
  //   - flag or env-pin set  → payload = pin value (the value re-applied on every call)
  //   - neither              → payload null (skip the daemon call; leave override alone)
  // Re-applying the env-pinned value on every CLI invocation is the load-bearing
  // mechanic for closing PR #3463's "Bug B": the daemon-side per-device override
  // (kept in `SessionTargetRegistry`) is wiped by `SessionTargetRegistry.clear`
  // whenever a fresh MCP session re-claims the device, so any source other than
  // the per-shell env var would silently degrade between invocations.
  val daemonCall = resolveCliTargetDaemonCall(target)
  // For the session-file invalidation check, anchor on the explicit pin if
  // present (the session must register the right toolset). For a clear request,
  // anchor on the daemon-wide default since the user is reverting.
  val effectiveTarget = daemonCall.pin ?: config.selectedTargetAppId

  return runBlocking {
    // Three-tier device resolution: explicit --device flag → TRAILBLAZE_DEVICE env →
    // autodetect-single-connected-device. Last tier closes the OOBE gap for
    // single-device users (zero setup needed). On miss, [resolveDeviceWithAutodetect]
    // has already emitted the appropriate envelope; we exit with the right code
    // per [TrailblazeExitCode] policy (MISUSE for 0/multiple-devices,
    // INFRA_FAILED for daemon-unreachable / list-call-threw).
    val resolvedDevice = when (val r = resolveDeviceWithAutodetect(flag = device, port = port)) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return@runBlocking r.exitCodeFallback()
    }
    val sessionScope = cliDeviceSessionScope(resolvedDevice)

    val mcpClient = connectOrStartDaemonReusable(
      port,
      targetAppId = effectiveTarget,
      sessionScope = sessionScope,
    ) ?: return@runBlocking INFRA_FAILED.code

    mcpClient.use { client ->
      val deviceError = client.ensureDevice(resolvedDevice, webHeadless = webHeadless)
      if (deviceError != null) {
        reportCliError(
          verb = "Device bind",
          target = resolvedDevice,
          reason = deviceError,
          hint = "check `trailblaze device` for the list of connected devices",
        )
        return@runBlocking INFRA_FAILED.code
      }
      // Tell the daemon to scope the target (or clear it) for this bound
      // device when the user pinned one — either via explicit `--target` on
      // this invocation, or via `TRAILBLAZE_TARGET` in the calling shell
      // (typically `eval $(trailblaze device connect ... --target X)`).
      // Bare-platform writes don't disturb other device sessions because the
      // daemon stores it keyed by the bound device id. We re-apply on every
      // CLI invocation so the daemon-side per-device override survives
      // session-claim revocation (`SessionTargetRegistry.clear` runs whenever
      // a fresh MCP session re-claims the device — see PR #3463's "Known
      // follow-up"). No-op when neither tier supplies a pin (we leave the
      // existing daemon-wide default in place).
      if (daemonCall.payload != null) {
        val setError = client.setSessionTargetForBoundDevice(daemonCall.payload)
        if (setError != null) {
          // When the pin came from $TRAILBLAZE_TARGET (no explicit flag), the
          // user may not remember they exported it — and the error reads as a
          // mystery because they never typed a target. Surface the source +
          // recovery hint so an inherited / stale env-pin can be cleared with
          // one shell command. The "env-tier" check is `target == null` (no
          // flag passed) AND `daemonCall.pin != null` (a value came from
          // somewhere) — the only remaining tier under the pin resolver.
          val pinCameFromEnv = target == null && daemonCall.pin != null
          val hint = if (pinCameFromEnv) {
            "TRAILBLAZE_TARGET=${daemonCall.pin} is your shell pin; " +
              "`unset TRAILBLAZE_TARGET` to drop it, or pass --target=clear"
          } else {
            null
          }
          reportCliError(
            verb = "Set session target",
            target = resolvedDevice,
            reason = setError,
            hint = hint,
          )
          return@runBlocking INFRA_FAILED.code
        }
      }
      // Track the last CLI scope used against this port so `blaze --save`
      // can locate the recording even when called without `--device`. We
      // write before the action so the scope is recoverable even if the
      // action fails partway — the recorded steps still belong to this
      // session.
      writeLastCliSessionScope(port, sessionScope)
      runActionWithIoEnvelope(target = resolvedDevice, action = { action(client) })
    }
  }
}

/**
 * Shared wrapper: connect to daemon without device selection.
 *
 * Used by read-only / device-listing commands (`device list`, `toolbox`) that
 * just attach to whatever shared CLI session exists on the daemon.
 */
fun cliWithDaemon(
  verbose: Boolean,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val targetAppId = CliConfigHelper.getOrCreateConfig().selectedTargetAppId

  return runBlocking {
    val mcpClient = connectOrStartDaemonReusable(port, targetAppId = targetAppId)
      ?: return@runBlocking INFRA_FAILED.code
    mcpClient.use { client -> runActionWithIoEnvelope(target = null, action = { action(client) }) }
  }
}

/**
 * Run a per-command action lambda and translate any leaked network/IO exception
 * into the structured-error envelope plus an [INFRA_FAILED] exit code.
 *
 * Without this wrapper, a [java.net.SocketTimeoutException] / [java.net.ConnectException]
 * thrown from inside `client.callTool(...)` would propagate up through `runBlocking` and
 * leak its raw stack trace to the user — the four-reviewer finding that motivated this
 * change. We never want a `snapshot -d android` to print `Exception in thread "main"
 * java.net.SocketTimeoutException: …`; we want `✗ Snapshot failed on android / reason: …
 * / hint: is the daemon running? …`. Stack traces are debug-mode territory.
 *
 * Surfaces only as a fallback — any code path that catches its own IOException and emits
 * a more specific envelope (e.g. the daemon-connect helpers) keeps doing so. This is the
 * "last line of defense" for action lambdas that don't catch their own transport errors.
 */
internal suspend fun runActionWithIoEnvelope(
  target: String?,
  action: suspend () -> Int,
): Int = try {
  action()
} catch (e: java.io.IOException) {
  reportCliError(
    verb = "Command",
    target = target,
    reason = describeThrowableForUser(e),
    hint = "is the daemon running? try `trailblaze app start`",
  )
  INFRA_FAILED.code
}

/**
 * Connect to the daemon for a one-shot command, auto-starting it if missing.
 * Never reads or writes the persisted session file.
 */
internal suspend fun connectOrStartDaemonOneShot(port: Int): CliMcpClient? {
  if (!checkAndRestartStaleDaemon(port)) {
    reportDaemonUnreachable(
      "stale daemon (wrong version) did not stop on shutdown request",
    )
    return null
  }
  warnIfWorkspaceMismatch(port)

  return try {
    CliMcpClient.connectOneShot(port)
  } catch (_: Exception) {
    if (!cliTryStartDaemon(port)) {
      reportDaemonUnreachable("Trailblaze daemon is not running and could not be auto-started")
      return null
    }
    try {
      CliMcpClient.connectOneShot(port = port)
    } catch (_: Exception) {
      reportDaemonUnreachable(
        "failed to connect to Trailblaze daemon after starting it",
      )
      null
    }
  }
}

/**
 * Connect to the daemon for a reusable workflow, auto-starting it if missing
 * and clearing the (now-stale) persisted session on first failure so the
 * retry doesn't re-discover and re-log it.
 */
internal suspend fun connectOrStartDaemonReusable(
  port: Int,
  targetAppId: String? = null,
  sessionScope: String? = null,
): CliMcpClient? {
  if (!checkAndRestartStaleDaemon(port)) {
    reportDaemonUnreachable(
      "stale daemon (wrong version) did not stop on shutdown request",
    )
    return null
  }
  warnIfWorkspaceMismatch(port)

  return try {
    CliMcpClient.connectReusable(
      port = port,
      targetAppId = targetAppId,
      sessionScope = sessionScope,
    )
  } catch (_: Exception) {
    CliMcpClient.clearSession(port, sessionScope = sessionScope)
    if (!cliTryStartDaemon(port)) {
      reportDaemonUnreachable("Trailblaze daemon is not running and could not be auto-started")
      return null
    }
    try {
      CliMcpClient.connectReusable(
        port = port,
        targetAppId = targetAppId,
        sessionScope = sessionScope,
      )
    } catch (_: Exception) {
      reportDaemonUnreachable(
        "failed to connect to Trailblaze daemon after starting it",
      )
      null
    }
  }
}

/**
 * Warn if the running daemon's workspace anchor differs from the cwd-resolved one.
 *
 * The daemon resolves its workspace once at startup based on the cwd it was launched
 * from. Subsequent CLI invocations connect to that same daemon regardless of where
 * the user `cd`'d to, so a `trailblaze toolbox` from project B silently returns
 * project A's targets if the daemon was started in project A. This produces stale
 * results that look correct but reference a different workspace.
 *
 * Auto-restarting on mismatch would be worse — it'd kill any in-flight runs another
 * shell started in project A. So we surface the mismatch as a prominent banner and
 * let the user decide. The diff (targets only-in-cwd vs only-in-daemon) tells them
 * exactly what they'd gain or lose by restarting.
 *
 * Silently no-ops in two scenarios:
 *  - Daemon isn't running: caller is about to auto-start it with the current cwd, so
 *    by definition no mismatch can exist.
 *  - Either side is in scratch mode (no `trails/config/trailblaze.yaml` discovered):
 *    workspace mismatch is undefined when there's no workspace.
 */
private fun warnIfWorkspaceMismatch(port: Int) {
  val status = try {
    DaemonClient(port = port).use { it.getStatusBlocking() }
  } catch (_: Exception) {
    return
  } ?: return
  val daemonAnchor = status.workspaceAnchor ?: return // daemon scratch mode

  val cwdAnchor = try {
    TrailblazeWorkspaceConfigResolver.resolveConfigFile(Paths.get(""))?.absolutePath
  } catch (_: Exception) {
    return
  } ?: return // cwd scratch mode

  // Canonicalize both sides so symlinked clones don't trigger spurious warnings.
  val daemonReal = canonicalize(daemonAnchor)
  val cwdReal = canonicalize(cwdAnchor)

  if (daemonReal != cwdReal) {
    warnAnchorMismatch(daemonAnchor, cwdAnchor)
    return
  }

  // Same anchor — check for content drift (user edited a trailmap.yaml since the daemon
  // started, daemon still running on stale dist output).
  val daemonHash = status.workspaceContentHash ?: return
  val cwdHash = computeCwdContentHash(File(cwdAnchor)) ?: return
  if (daemonHash != cwdHash) {
    warnContentDrift(cwdAnchor)
  }
}

private fun warnAnchorMismatch(daemonAnchor: String, cwdAnchor: String) {
  if (Console.isQuietMode()) return // Scripted/--quiet callers opt out of advisory banners.
  val daemonTargets = loadTargetIds(File(daemonAnchor))
  val cwdTargets = loadTargetIds(File(cwdAnchor))
  emitWarningBanner(buildAnchorMismatchBanner(daemonAnchor, cwdAnchor, daemonTargets, cwdTargets))
}

private fun warnContentDrift(anchor: String) {
  if (Console.isQuietMode()) return // Scripted/--quiet callers opt out of advisory banners.
  emitWarningBanner(buildContentDriftBanner(anchor))
}

/**
 * Stderr so the banner is visible even when stdout is being piped (e.g.
 * `trailblaze toolbox ... | grep`). Repeated equals-banners make this hard to miss in
 * a terminal. Pure emission of an already-built banner — call sites build the lines via
 * [buildAnchorMismatchBanner] / [buildContentDriftBanner] so the formatting is unit-
 * testable without redirecting Console.
 */
private fun emitWarningBanner(lines: List<String>) {
  for (line in lines) Console.error(line)
}

/**
 * Builds the banner emitted by [warnAnchorMismatch] as an in-memory list of lines so
 * tests can assert on its content without redirecting Console. Production callers feed
 * the result into [emitWarningBanner].
 *
 * Visible for testing.
 */
internal fun buildAnchorMismatchBanner(
  daemonAnchor: String,
  cwdAnchor: String,
  daemonTargets: Set<String>,
  cwdTargets: Set<String>,
): List<String> {
  val onlyInCwd = (cwdTargets - daemonTargets).sorted()
  val onlyInDaemon = (daemonTargets - cwdTargets).sorted()
  val bar = "═".repeat(72)
  val out = mutableListOf<String>()
  out += ""
  out += bar
  out += "  ⚠️  WORKSPACE MISMATCH — daemon and your cwd resolved to different anchors"
  out += bar
  out += "  Daemon was started against:  $daemonAnchor"
  out += "  Your cwd resolves to:        $cwdAnchor"
  out += ""
  if (onlyInCwd.isNotEmpty()) {
    out += "  Targets you would gain by restarting (only in cwd workspace):"
    onlyInCwd.forEach { out += "    + $it" }
  }
  if (onlyInDaemon.isNotEmpty()) {
    if (onlyInCwd.isNotEmpty()) out += ""
    out += "  Targets the daemon currently shows (only in daemon's workspace):"
    onlyInDaemon.forEach { out += "    - $it" }
  }
  if (onlyInCwd.isEmpty() && onlyInDaemon.isEmpty()) {
    out += "  Target lists are identical, but workspace anchors differ — trailmaps,"
    out += "  tools, and toolsets may still resolve from different files."
  }
  out += ""
  out += "  To switch to the cwd workspace:"
  out += "    trailblaze app --stop"
  out += "    <re-run your command>"
  out += ""
  out += "  Restart is not automatic because it would kill any in-flight runs"
  out += "  another shell started against the daemon's current workspace."
  out += bar
  out += ""
  return out
}

/**
 * Builds the banner emitted by [warnContentDrift] as an in-memory list of lines so tests
 * can assert on it without redirecting Console.
 *
 * Visible for testing.
 */
internal fun buildContentDriftBanner(anchor: String): List<String> {
  val bar = "═".repeat(72)
  val out = mutableListOf<String>()
  out += ""
  out += bar
  out += "  ⚠️  WORKSPACE CONTENT DRIFT — files changed since daemon started"
  out += bar
  out += "  Workspace: $anchor"
  out += ""
  out += "  One or more files under `trails/config/` have been edited since the"
  out += "  daemon loaded this workspace. The daemon is still serving the OLD"
  out += "  state — your edits to trailmaps, tool YAMLs, scripts, toolsets, providers,"
  out += "  or `trailblaze.yaml` itself are not visible until it restarts."
  out += ""
  out += "  To pick up the changes:"
  out += "    trailblaze app --stop"
  out += "    <re-run your command>"
  out += ""
  out += "  Restart is not automatic because it would kill any in-flight runs."
  out += bar
  out += ""
  return out
}

/**
 * Compute the cwd workspace's content hash using the same algorithm the daemon
 * captures at startup. Walks every non-excluded file under `<configDir>/` —
 * `trailmap.yaml`, tool YAMLs, scripts, the workspace anchor itself — so any edit
 * the daemon would have to be restarted to pick up shows up as a different hash
 * here. Returns null when the configDir is unreadable; we skip the drift check
 * rather than fire a noisy warning.
 */
private fun computeCwdContentHash(anchorFile: File): String? = try {
  val configDir = anchorFile.parentFile ?: return null
  WorkspaceContentHasher.compute(configDir, TrailblazeVersion.version)
} catch (_: Exception) {
  null
}

private fun canonicalize(path: String): String = try {
  File(path).canonicalPath
} catch (_: Exception) {
  path
}

private fun loadTargetIds(anchorFile: File): Set<String> = try {
  // `projectConfig.targets` is now a list of trailmap ids — already what this helper
  // wants. The pre-resolution shape (workspace declaration) and post-resolution
  // shape (successfully-loaded ids) are both List<String>; no further unwrapping.
  TrailblazeProjectConfigLoader.loadResolved(anchorFile)
    ?.targets
    ?.toSet()
    .orEmpty()
} catch (_: Exception) {
  emptySet()
}

/**
 * Check if the running daemon has a different version than the CLI.
 * If so, stop it so it gets restarted with the current version.
 *
 * @return true if the caller may proceed (no stale daemon blocking the port),
 *         false if a stale daemon could not be stopped.
 */
private fun checkAndRestartStaleDaemon(port: Int): Boolean {
  val cliVersion = TrailblazeVersion.displayVersion
  if (cliVersion == "Developer Build") return true // Can't compare dev builds

  try {
    DaemonClient(port = port).use { daemon ->
      val status = daemon.getStatusBlocking() ?: return true
      val daemonVersion = status.version
      if (daemonVersion != null && daemonVersion != cliVersion) {
        Console.log(
          "Restarting daemon (version mismatch: daemon=$daemonVersion, cli=$cliVersion)..."
        )
        daemon.shutdownBlocking()
        // Wait for daemon to stop
        repeat(20) {
          if (!daemon.isRunningBlocking()) return true
          Thread.sleep(500)
        }
        // Timed out — stale daemon is still running
        return false
      }
    }
  } catch (_: Exception) {
    // Daemon not running or status check failed — will be handled by the connect-or-start helpers
  }
  return true
}

/**
 * Auto-start the Trailblaze daemon in headless mode.
 */
private fun cliTryStartDaemon(port: Int): Boolean {
  val launcher = findTrailblazeLauncher() ?: run {
    Console.error("Cannot auto-start daemon: trailblaze launcher not found.")
    return false
  }

  Console.log("Starting Trailblaze daemon...")
  try {
    val pb = ProcessBuilder(launcher.absolutePath, "app", "--foreground", "--headless")
    if (port != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
      pb.environment()["TRAILBLAZE_PORT"] = port.toString()
    }
    pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
    pb.redirectError(ProcessBuilder.Redirect.DISCARD)
    pb.start()
  } catch (e: Exception) {
    reportCliError(
      verb = "Daemon start",
      reason = describeThrowableForUser(e),
      hint = "try `trailblaze app --foreground --headless` to see startup output directly",
    )
    return false
  }

  Console.appendInfo("Waiting for Trailblaze daemon to be ready")
  val started = DaemonClient(port = port).use {
    it.waitForDaemon { Console.appendInfo(".") }
  }
  Console.info("") // newline after dots
  if (started) {
    Console.log("Trailblaze daemon started.")
  } else {
    Console.error("Daemon did not start within 30s. If a source build is in progress it may need more time.")
    Console.error("Run with --foreground to see startup output directly.")
  }
  return started
}

// ---------------------------------------------------------------------------
// Shared daemon shutdown helper
// ---------------------------------------------------------------------------

/**
 * Shut down the daemon, wait for it to stop, and clear the CLI session.
 *
 * @return [TrailblazeExitCode.SUCCESS] code on success, [TrailblazeExitCode.INFRA_FAILED]
 *   code on failure.
 */
fun shutdownDaemonAndWait(port: Int): Int {
  DaemonClient(port = port).use { daemon ->
    if (!daemon.isRunningBlocking()) {
      Console.log("Trailblaze daemon is not running.")
      return SUCCESS.code
    }

    Console.log("Stopping Trailblaze daemon...")
    val response = daemon.shutdownBlocking()
    if (!response.success) {
      reportCliError(
        verb = "Daemon stop",
        reason = response.message ?: "shutdown request rejected by daemon",
      )
      return INFRA_FAILED.code
    }

    Console.appendLog("Waiting for daemon to stop")
    repeat(20) {
      if (!daemon.isRunningBlocking()) {
        Console.log("")
        Console.log("Trailblaze daemon stopped.")
        CliMcpClient.clearSession(port)
        return SUCCESS.code
      }
      Console.appendLog(".")
      Thread.sleep(500)
    }
    Console.log("")
    reportCliError(
      verb = "Daemon stop",
      reason = "daemon did not stop gracefully within 10s of the shutdown request",
      hint = "if `trailblaze app --status` still shows it running, kill the process manually",
    )
    return INFRA_FAILED.code
  }
}

// ---------------------------------------------------------------------------
// Target helper
// ---------------------------------------------------------------------------

// Persistent --target writes go through `trailblaze config target` only — the
// CLI flag on action commands (`tool`, `step`, `snapshot`, ...) is session-
// scoped via [CliMcpClient.setSessionTargetForBoundDevice]. For per-shell
// pinning (the multi-terminal case) users export `TRAILBLAZE_TARGET` via
// `eval $(trailblaze device connect ... --target X)`; that env var is consulted
// by [resolveCliTarget] (the four-tier resolver) and [resolveCliTargetPin]
// (the two-tier "is there a per-device pin to re-apply?" helper), so every
// action command picks it up automatically.
