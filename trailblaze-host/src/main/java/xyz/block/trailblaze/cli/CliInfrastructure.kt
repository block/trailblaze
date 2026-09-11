package xyz.block.trailblaze.cli

import kotlinx.coroutines.CancellationException
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
import xyz.block.trailblaze.host.devices.HostDriverPortUtils
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.Console
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

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
/**
 * Single source of truth for the `-d` / `--device` option description on every
 * device-acting command (`snapshot`, `tool`, `ask`, `verify`, `session start/stop/end`,
 * `step`, `toolbox`).
 *
 * Per-command variants live alongside their command class (e.g. [McpCommand]'s
 * `--device` is about pre-binding the MCP session at startup; [TrailCommand]
 * `--device` is the dispatched-replay form). Use this constant on action
 * commands where the semantics are "act on this device, defaulting to the
 * terminal's pin."
 *
 * The wording deliberately puts the per-terminal file-pin first (the primary
 * mechanism now), names `TRAILBLAZE_DEVICE` as a manual escape hatch, and
 * spells out the agent-harness advice on the same line so an agent running
 * `trailblaze tool --help` doesn't have to read three doc pages to discover
 * "use `--device` on every call." The phrase "fresh-shell harness" is the
 * lexicon the SKILL.md and the multi-device error envelope already use.
 *
 * Single line because picocli wraps for help rendering and the docs generator
 * joins continuation lines with a space; embedded line breaks would land in
 * the generated `docs/CLI.md` as literal newlines mid-cell.
 */
internal const val DEVICE_OPTION_DESCRIPTION: String =
  "Device: platform (android, ios, web) or platform/id. " +
    "Defaults to `\$TRAILBLAZE_DEVICE` if set (manual override; rare), " +
    "otherwise this terminal's pin (set by `trailblaze device connect`). " +
    "In a fresh-shell harness (Claude Code, Cursor, Codex, CI), pass " +
    "--device on every call."

/**
 * Single source of truth for the `[ShellDevicePinStore]` log-line prefix used
 * across the file-pin lifecycle (`writeShellDevicePinIfPossible`,
 * `clearShellDevicePinIfPossible`, `clearShellDevicePinTargetIfPossible`, and
 * the lazy stale-pin eviction in `evictShellPinIfMatches`). A const keeps
 * the prefix consistent — operators grepping by it find every event.
 */
private const val SHELL_PIN_LOG_PREFIX = "[ShellDevicePinStore]"

internal const val TARGET_OPTION_DESCRIPTION: String =
  "Target app ID for this command's bound device. Defaults to " +
    "`\$TRAILBLAZE_TARGET` if set, otherwise the target you passed to " +
    "`trailblaze device connect --target X` for this terminal (persists in " +
    "this terminal's pin; cleared by `device disconnect`, replaced by " +
    "`device rebind --target Y`). Pass `--target=clear` to remove a " +
    "previously-set override for this device. To set a persistent " +
    "default, use `trailblaze config target`. List available targets " +
    "with `trailblaze toolbox` (no args)."

/**
 * Variant of [TARGET_OPTION_DESCRIPTION] for `session start` — same env-tier
 * + clear-sentinel + persistent-default story, narrower wording because the
 * session command isn't bound to one tool action and doesn't surface the
 * "list available targets with toolbox" pointer.
 */
internal const val TARGET_OPTION_DESCRIPTION_SESSION: String =
  "Target app ID for this session's bound device. Defaults to " +
    "`\$TRAILBLAZE_TARGET` if set, otherwise the target you passed to " +
    "`trailblaze device connect --target X` for this terminal (persists in " +
    "this terminal's pin; cleared by `device disconnect`, replaced by " +
    "`device rebind --target Y`). Pass `--target=clear` to remove a " +
    "previously-set override. To set a persistent default, use " +
    "`trailblaze config target`."

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
 *  - [PersistedSelection] — the user configured a target via `trailblaze config target`
 *    (so [xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig.selectedTargetAppId]
 *    is non-null on disk). Surfaced in resolved-target headers as "from saved selection".
 *  - [WorkspaceDefault] — the workspace `trails/config/trailblaze.yaml` declares
 *    `defaults.target` and nothing more specific is set. Matches the daemon's rung-3
 *    resolution ([xyz.block.trailblaze.ui.TrailblazeSettingsRepo.getCurrentSelectedTargetApp])
 *    so the CLI surface agrees with what a run actually targets.
 *  - [BuiltinDefault] — none of the above; we fell back to
 *    [xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget].
 *    Surfaced as "built-in default" so the user understands the value isn't pinned.
 *
 * Lives in the infrastructure layer (rather than a per-command file) because the
 * source distinction is the same for every command that wants source attribution
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
  PersistedSelection(attributionLabel = "from saved selection"),
  WorkspaceDefault(attributionLabel = "from workspace defaults.target"),
  BuiltinDefault(attributionLabel = "built-in default"),
}

/**
 * Resolves the effective `--target` value plus the source it came from, in one
 * place, for any CLI command that wants the five-tier resolution
 * (`flag → TRAILBLAZE_TARGET env → saved selection → workspace defaults.target →
 * built-in default`).
 *
 * Returns the resolved id and the source — callers decide whether to render a
 * header, suppress one, etc. Distinct from [cliWithDaemon]'s `targetAppId` read,
 * which passes the persisted selection (possibly null) straight through for
 * session change-detection; this helper preserves the distinction between "user
 * explicitly set this" and "we fell back to a default" so the CLI surface can
 * communicate it.
 */
internal data class ResolvedCliTarget(
  val id: String,
  val source: ResolvedCliTargetSource,
)

/**
 * The neutral-"default" sentinel for every CLI target surface ([resolveCliTarget],
 * `config get target`, `config target` listing). Thin adapter over the shared
 * [TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId] that pins the CLI's neutral
 * default to the compile-time OSS static ([xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget]).
 *
 * The daemon ([xyz.block.trailblaze.ui.TrailblazeSettingsRepo.getCurrentSelectedTargetApp]) calls
 * the same shared function with its runtime-injected `defaultHostAppTarget.id`. Both routing
 * through one implementation is what stops the CLI's target-attribution and the daemon's run
 * resolution from silently disagreeing on what "no explicit selection" means — the parity this
 * function exists to guarantee (see `CliDaemonTargetSentinelParityTest`).
 */
internal fun authoritativeSelectedTargetId(selectedTargetAppId: String?): String? =
  TrailblazeWorkspaceConfigResolver.authoritativeSelectedTargetId(
    selectedTargetAppId = selectedTargetAppId,
    neutralDefaultId = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id,
  )

internal fun resolveCliTarget(
  flag: String?,
  /**
   * Seed for the workspace-anchor walk-up (the `defaults.target` tier). Defaults to the
   * caller's cwd ([CliCallerContext.callerCwd]) — the user's shell workspace, forwarded
   * through the daemon `/cli/exec` path so a daemon launched elsewhere anchors at the
   * caller's workspace rather than its own launch directory. Injectable so tests aren't
   * coupled to the repo they run in (this repo is itself a workspace anchor declaring a
   * default target).
   */
  workspaceAnchorSeedPath: Path = CliCallerContext.callerCwd(),
): ResolvedCliTarget {
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
  // or directly in the shell, parallel to TRAILBLAZE_DEVICE. Wins over the saved selection
  // and workspace tiers so per-shell pinning beats per-machine and per-project defaults
  // in the multi-terminal case.
  envTrailblazeTarget()?.let {
    return ResolvedCliTarget(it, ResolvedCliTargetSource.EnvVar)
  }
  val neutralDefaultId = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id
  // readConfigRaw so a daemon-bridged read can't shadow the on-disk state; null
  // here is the tri-state "user never picked a target" signal. The neutral-default
  // sentinel (see authoritativeSelectedTargetId) drops a legacy auto-persisted
  // "default" on this tier so it can't mask a committed workspace default — same
  // rule as the daemon's TrailblazeSettingsRepo.getCurrentSelectedTargetApp.
  val rawSetting = CliConfigHelper.readConfigRaw()?.selectedTargetAppId
  authoritativeSelectedTargetId(rawSetting)?.let {
    return ResolvedCliTarget(it, ResolvedCliTargetSource.PersistedSelection)
  }
  // Workspace `defaults.target` — the daemon's rung 3. The CLI has no loaded-target
  // set to validate against, so the declared id is surfaced as-is (blank-normalized by
  // the accessor); the daemon rejects an unknown id at dispatch the same way it always has.
  // TRAILBLAZE_CONFIG_DIR is read via CliCallerContext.callerEnv — not System.getenv — for
  // the same reason workspaceAnchorSeedPath is the caller's cwd: on a daemon launched elsewhere,
  // System.getenv is the daemon's frozen env, and CONFIG_DIR_ENV_VAR OUTRANKS the cwd walk-up,
  // so failing to forward it would resolve (and dispatch) the wrong workspace's default.
  TrailblazeWorkspaceConfigResolver.workspaceDefaultTarget(
    fromPath = workspaceAnchorSeedPath,
    consumer = "defaults.target (cli)",
    envReader = { CliCallerContext.callerEnv(TrailblazeWorkspaceConfigResolver.CONFIG_DIR_ENV_VAR) },
  )?.let {
    return ResolvedCliTarget(it, ResolvedCliTargetSource.WorkspaceDefault)
  }
  if (rawSetting != null && rawSetting.isNotBlank()) {
    // Legacy auto-persisted "default" with no workspace default declared: still a
    // saved selection for attribution purposes. A blank persisted id is non-authoritative
    // (authoritativeSelectedTargetId already rejected it) and must not surface here — it
    // falls through to BuiltinDefault, matching the daemon, which never matches a blank id.
    return ResolvedCliTarget(rawSetting, ResolvedCliTargetSource.PersistedSelection)
  }
  return ResolvedCliTarget(
    neutralDefaultId,
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
  // Returning null here (rather than falling through to the env / pin tiers)
  // is what keeps the clear deterministic — without this short-circuit a
  // file-pinned target would silently re-establish the very binding the user
  // is trying to clear.
  if (normalized == "clear") return null
  if (normalized != null) return normalized
  envTrailblazeTarget()?.let { return it }
  // File-pin target tier: when the user passed `--target X` to a previous
  // `device connect`, that X was persisted alongside the device id in the
  // pin file. Re-applying it here ensures the per-device daemon override is
  // re-established on every action command, surviving daemon restarts. The
  // tier is silently skipped when the wrapper didn't forward
  // TRAILBLAZE_SHELL_PID, or when the user pinned a device without --target.
  return readShellPinTarget(CliConfigHelper.resolveEffectiveHttpPort())
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
 * True when the caller's wrapper detected a tty on stdin — typical of a
 * human typing into a real terminal. False for AI agent harnesses (Claude
 * Code / Cursor / Codex Bash tools), CI scripts, and piped invocations,
 * where each command typically runs in a fresh shell with no parent-tty.
 *
 * Read by `device connect` to decide whether to warn that the file-pin
 * won't carry across separate command invocations. We deliberately default
 * to `false` on missing/blank env (older wrapper, direct-JVM use): missing
 * the warning for a real human is mild noise ("Note: this terminal isn't
 * interactive..." is technically wrong but harmless); missing it for an
 * agent is a loop where the agent keeps re-pinning and failing.
 */
internal fun isInteractiveCaller(): Boolean =
  CliCallerContext.callerEnv("TRAILBLAZE_INTERACTIVE")?.trim() == "1"

/**
 * Persist the [deviceId] (and optional [targetId]) as the pin for the caller's
 * terminal, identified by the `TRAILBLAZE_SHELL_PID` env var forwarded by the
 * bash wrapper.
 *
 * Persisting the target alongside the device is what makes
 * `device connect --target X` survive a daemon restart — without it, the
 * target lives only in the daemon's in-memory `SessionTargetRegistry` and
 * silently degrades to workspace config on next `app --stop && app start`.
 *
 * Silently skips when:
 *  - The wrapper didn't forward the PID (older wrapper, direct-JVM use, a
 *    tool invoking the CLI outside `./trailblaze`).
 *  - The pin file write throws (e.g. read-only home directory). The
 *    daemon-side device bind still happened, so the user can still drive
 *    the device for this one call via the resolver's existing flag/env
 *    tiers — they just won't get persistence.
 *
 * Diagnostic message goes to `Console.log` (file log) on write failure so a
 * read-only-home-directory bug surfaces without spamming the user's terminal.
 */
internal fun writeShellDevicePinIfPossible(deviceId: String, targetId: String? = null) {
  val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
    ?: return
  val pid = pidStr.toLongOrNull()?.takeIf { it > 0 } ?: return
  try {
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    ShellDevicePinStore.setPin(
      file = ShellDevicePinStore.pinFileFor(port),
      shellPid = pid,
      device = deviceId,
      target = targetId,
    )
  } catch (e: IllegalStateException) {
    // `mutate()` throws IllegalStateException for filesystem-state problems
    // it can't recover from automatically (e.g., the `.lock` path is a
    // directory, or a symlink to one). The user-visible "Pinned $device for
    // this terminal." line was already printed at this point — promote the
    // failure to stderr so the user knows their pin DIDN'T land and they'll
    // need `--device` on follow-up calls until they clean up the bad path.
    // Debug log retained for the operator log trail.
    Console.error(
      "Warning: this terminal's device pin couldn't be written (${e.message}). " +
        "The daemon-side bind succeeded, but subsequent commands in this terminal " +
        "won't inherit the pin — pass `--device $deviceId` on each call until you fix " +
        "the underlying filesystem state.",
    )
    Console.log("$SHELL_PIN_LOG_PREFIX failed to write pin for pid=$pid: ${e.message}")
  } catch (e: Exception) {
    Console.log("$SHELL_PIN_LOG_PREFIX failed to write pin for pid=$pid: ${e.message}")
  }
}

/**
 * Read the target from this terminal's file-pin. Returns null when there's no
 * pin, when the pin doesn't include a target (user pinned bare with no
 * `--target`), or when the wrapper didn't forward the shell PID. Used as a
 * resolver tier in [resolveCliTargetPin] between the env var and null so a
 * `device connect --target X` re-applies that X on every subsequent action
 * command, surviving daemon restarts.
 */
private fun readShellPinTarget(port: Int): String? {
  val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
    ?: return null
  val pid = pidStr.toLongOrNull()?.takeIf { it > 0 } ?: return null
  val lookup = ShellDevicePinStore.resolvePin(ShellDevicePinStore.pinFileFor(port), pid)
  return (lookup as? ShellDevicePinStore.PinLookup.Found)?.target
}

/**
 * Clear ONLY the target field on this terminal's pin, preserving the device
 * binding. Called when the user passes `--target=clear` so the next action
 * command doesn't re-read a stale target from the pin and silently undo the
 * clear. Without this, the eviction-half of `--target=clear` only ran on the
 * daemon side (via `setSessionTargetForBoundDevice("")`); the file-pin kept
 * the original target and `resolveCliTargetPin` re-established it on the next
 * call. See PR #3611 lead-dev-review finding #1.
 *
 * Silently skips when no pin exists for this PID (nothing to clear), when the
 * PID isn't forwarded (same as the other shell-pin helpers), or when the
 * write fails (logged at debug level). Symmetric with the other helpers:
 * never throws, never blocks the action that triggered the clear.
 */
internal fun clearShellDevicePinTargetIfPossible() {
  val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
    ?: return
  val pid = pidStr.toLongOrNull()?.takeIf { it > 0 } ?: return
  try {
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    // Atomic clear: one `mutate()` lock window does read-then-write so a
    // concurrent same-PID writer can't slip in between. No-ops when the
    // entry is missing or already has target=null (saves a lock cycle in
    // the common-after-first-clear case).
    ShellDevicePinStore.clearPinTarget(
      file = ShellDevicePinStore.pinFileFor(port),
      shellPid = pid,
    )
    // Success path is observable too — paired with the failure log below
    // so an operator triaging "user says --target=clear didn't stick" can
    // verify the helper ran. Debug-level (Console.log → file) so it
    // doesn't add interactive-user noise.
    Console.log("$SHELL_PIN_LOG_PREFIX cleared target for pid=$pid")
  } catch (e: Exception) {
    Console.log("$SHELL_PIN_LOG_PREFIX failed to clear pin target for pid=$pid: ${e.message}")
  }
}

/**
 * Clear the pin entry for the caller's terminal (if any). Symmetric with
 * [writeShellDevicePinIfPossible]; same silent-skip semantics on missing
 * PID or write failure.
 */
internal fun clearShellDevicePinIfPossible() {
  val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
    ?: return
  val pid = pidStr.toLongOrNull()?.takeIf { it > 0 } ?: return
  try {
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    ShellDevicePinStore.clearPin(
      file = ShellDevicePinStore.pinFileFor(port),
      shellPid = pid,
    )
  } catch (e: Exception) {
    Console.log("$SHELL_PIN_LOG_PREFIX failed to clear pin for pid=$pid: ${e.message}")
  }
}

/**
 * Lazy staleness handler for the per-terminal device pin. Clears the pin
 * only when the failure says the device is gone — not on transient or
 * contention failures. Used from the three `ensureDevice` failure sites
 * ([cliReusableWithDevice] / [cliOneShotWithDevice] / [SessionStartCommand])
 * so a pin pointing at a now-unplugged device self-evicts on first
 * failure, instead of making every subsequent call repeat the same
 * `Device bind failed` envelope.
 *
 * **Two gates, both required.**
 *
 * Match gate: the pin must point at the failed [deviceSpec] (case-
 * insensitive). This stops a `--device ios/X` failure from evicting an
 * unrelated `android/...` pin — the failure was about the flag, not the pin.
 *
 * Reason gate: the [deviceError] must read like a "device not found"
 * message (substring match on `not found`, case-insensitive). The daemon's
 * device-not-found path returns wording like
 * `"Device 'ios/SIM-X' not found. Available: …"` or
 * `"No mobile devices found. …"` (see [CliMcpClient.connectToDevice]).
 * Everything else — `"Error: … is busy"` (another shell holds the device),
 * `"Error connecting to device: …"` (transport blip), `"Session reports an
 * existing device but device(INFO) returned no platform"` (daemon-state
 * inconsistency) — is treated as transient and the pin survives. This
 * narrowing closes a race Codex and Copilot flagged on PR #3621: a
 * device-busy failure from another shell would otherwise have cleared
 * this terminal's still-valid pin.
 *
 * Silent no-op when no PID is forwarded, when the pin is missing, when the
 * pin points at a different device, when the error doesn't read as
 * staleness, or when the underlying write fails — same swallow-everything
 * contract as the other shell-pin helpers.
 *
 * **Ordering note.** Call sites invoke this function AFTER
 * [reportCliError], so the `✗ Device bind failed` envelope lands first and
 * the supplementary "Pin cleared for X" stderr line follows. The user
 * reads "what failed" → "what was done about it" instead of an eviction
 * notice floating above an unexplained failure.
 */
internal fun evictShellPinIfMatches(deviceSpec: String, deviceError: String) {
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val pinned = readShellPinDevice(port) ?: return
  if (!pinned.equals(deviceSpec, ignoreCase = true)) return
  if (!looksLikeDeviceNotFound(deviceError)) return
  Console.error(
    "Pin cleared for $pinned (the daemon couldn't find this device). " +
      "Reconnect it and re-run `trailblaze device connect $pinned`, " +
      "or pick a different one from `trailblaze device list`.",
  )
  Console.log("$SHELL_PIN_LOG_PREFIX evicted pin: device=$pinned ensureDevice reported not-found")
  clearShellDevicePinIfPossible()
}

/**
 * Pure predicate: does [deviceError] read as "the device isn't connected
 * anymore" rather than "something else went wrong"?
 *
 * Two shapes the daemon emits from [CliMcpClient.connectToDevice] for the
 * device-gone path:
 *  - `"Device 'X' not found. Available: …"` / `"… Run 'trailblaze device list'."`
 *    — matches on `not found`.
 *  - `"No mobile devices found. Connect an Android device/emulator or start
 *    an iOS simulator."` — matches on `no mobile devices found`.
 *
 * Everything else — busy / transport / state-inconsistency — does not.
 * Centralizing the gate next to its sole caller keeps the substring shapes
 * unit-testable and the eviction path readable.
 */
internal fun looksLikeDeviceNotFound(deviceError: String): Boolean =
  deviceError.contains("not found", ignoreCase = true) ||
    deviceError.contains("no mobile devices found", ignoreCase = true)

/**
 * Reads the per-terminal device pin written by `device connect`. Returns the
 * pinned device spec when all of the following hold:
 *  - The wrapper forwarded `TRAILBLAZE_SHELL_PID` (older wrappers skip this
 *    tier silently, preserving the env-var-or-autodetect behavior they had);
 *  - The PID is a valid positive integer;
 *  - The pin file has an entry for this PID;
 *  - The PID is still bound to a live OS process (the shell hasn't exited).
 *
 * Any other case returns null so the resolver falls through. The path is
 * scoped to the daemon [port] so a custom-port daemon (`TRAILBLAZE_PORT=…`)
 * gets its own pin map and doesn't see a default-port daemon's bindings
 * pointing at device IDs it doesn't own.
 *
 * Note: this returns the pin *as recorded*. The resolver no longer pre-
 * validates the pin against a live `device LIST` (that round-trip cost ~2s
 * on every call). Staleness is detected lazily by [evictShellPinIfMatches]
 * when [CliMcpClient.ensureDevice] rejects the bound device downstream.
 */
internal fun readShellPinDevice(port: Int): String? {
  val pidStr = CliCallerContext.callerEnv("TRAILBLAZE_SHELL_PID")?.takeIf { it.isNotBlank() }
    ?: return null
  val pid = pidStr.toLongOrNull()?.takeIf { it > 0 } ?: return null
  val lookup = ShellDevicePinStore.resolvePin(ShellDevicePinStore.pinFileFor(port), pid)
  return (lookup as? ShellDevicePinStore.PinLookup.Found)?.device
}

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
/**
 * Emit the `✗ <verb> failed / reason: no devices connected / hint: …` envelope. Extracted
 * so the verb-plumbing contract between [cliReusableWithDevice] / [cliOneShotWithDevice]
 * and the rendered envelope can be unit-tested without spinning up a daemon to drive
 * `resolveDeviceWithAutodetect` to the [DeviceAutodetectResult.NoDevices] branch.
 */
internal fun emitNoDevicesEnvelope(verb: String) {
  reportCliError(
    verb = verb,
    reason = "no devices connected",
    hint = "start an Android emulator (Android Studio AVD) or iOS simulator (Xcode), " +
      "or run `trailblaze device connect web` to launch a browser",
  )
}

/**
 * Emit the `✗ <verb> failed / reason: multiple devices connected / hint: …` envelope plus
 * the two trailing recovery lists (interactive `device connect` and per-call `--device`).
 * Extracted alongside [emitNoDevicesEnvelope] for the same testability reason — the
 * verb-plumbing regression that motivated this split lived precisely in the gap between
 * "the resolver had a verb" and "the envelope rendered it."
 */
internal fun emitMultipleDevicesEnvelope(verb: String, specs: List<String>) {
  reportCliError(
    verb = verb,
    reason = "multiple devices connected — pick one",
    hint = "in an interactive terminal, pin one (subsequent commands inherit it):",
  )
  specs.forEach { spec -> Console.error("    trailblaze device connect $spec") }
  Console.error("")
  Console.error("  Or, for scripts and AI agents like Claude Code (each command runs in a fresh shell), append to your command:")
  specs.forEach { spec -> Console.error("    --device $spec") }
}

internal suspend fun resolveDeviceWithAutodetect(
  flag: String?,
  port: Int,
  verb: String = "Command",
): DeviceResolution {
  resolveCliDevice(flag)?.let { return DeviceResolution.Resolved(it) }

  // Per-terminal pin tier: trust the pin and return immediately. No daemon
  // round-trip on the hot path.
  //
  // The previous shape validated the pin against a `device LIST` before
  // honoring it, which cost a full one-shot MCP session (initialize handshake
  // + tool registry hydration + LIST) — roughly two seconds on every
  // pin-resolved call. For the common case (pin set, device still
  // connected) that validation was pure overhead, doubling `trailblaze
  // snapshot` from ~2.5s to ~4.5s when relying on the per-terminal pin vs.
  // passing `--device` explicitly. See PR #3611.
  //
  // Staleness handling: when the pinned device is gone, [ensureDevice] will
  // reject it from the wrapper below ([cliReusableWithDevice] /
  // [cliOneShotWithDevice]). Those wrappers call [evictShellPinIfMatches]
  // on failure so the next invocation falls back to autodetect rather than
  // hitting the same stale pin again. The first call after the device
  // disappears now surfaces a `Device bind failed` envelope instead of the
  // friendlier "Pinned device X is no longer connected" message that
  // validation used to print, but it does so at full speed — a one-time
  // degraded message on a rare event is a fair trade for ~2s on every
  // successful call.
  readShellPinDevice(port)?.let { return DeviceResolution.Resolved(it) }

  // No pin (and no flag, no env) — run autodetect for the OOBE
  // single-connected-device case. This still costs the daemon round-trip,
  // but only fires on first use before any pin exists; once the user runs
  // `device connect`, subsequent calls skip this entirely via the pin tier
  // above.
  val autodetect = autodetectSingleConnectedDevice(port)

  return when (val r = autodetect) {
    is DeviceAutodetectResult.Resolved -> {
      reportAutodetectedDevice(r.deviceSpec)
      DeviceResolution.Resolved(r.deviceSpec)
    }
    is DeviceAutodetectResult.NoDevices -> {
      emitNoDevicesEnvelope(verb)
      DeviceResolution.Misuse
    }
    is DeviceAutodetectResult.Multiple -> {
      emitMultipleDevicesEnvelope(verb, r.specs)
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
 *  - [sessionDevices] is the cast this MCP session addresses by name, empty for a single-device
 *    session. The daemon reports ONE current device, and a `switchDevice` handover makes that a
 *    companion — so on a bound session the reported device is evidence about which member is
 *    ACTIVE, not about which session owns [expectedDevice]. Any member of the cast identifies
 *    this session, so a handover must not turn the documented `session stop -d <startDevice>`
 *    into a refusal. `device disconnect` passes nothing and keeps today's strict check.
 */
internal suspend fun stopBoundSessionIfMatches(
  client: CliMcpClient,
  expectedDevice: String,
  extraStopArgs: Map<String, Any?> = emptyMap(),
  sessionDevices: List<String> = emptyList(),
): StopBoundSessionResult {
  val bound = client.getBoundDeviceId()
    ?: return StopBoundSessionResult.NoActiveSession
  if (!sessionOwnsDevice(expectedDevice, bound, sessionDevices)) {
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

/**
 * Whether the MCP session that reported [reportedDevice] as current is the one that owns
 * [expectedDevice], given the cast it addresses by name ([sessionDevices], empty for a
 * single-device session).
 *
 * On a single-device session the reported device IS the session's identity, so this is
 * [deviceArgMatches] unchanged. On a bound session it is only the ACTIVE member: a
 * `switchDevice` handover moves it to a companion while every member still belongs to this one
 * session, so refusing on the reported device alone would break the lifecycle contract the start
 * prints — `session stop -d <startDevice>` — the moment a handover happens.
 */
internal fun sessionOwnsDevice(
  expectedDevice: String,
  reportedDevice: xyz.block.trailblaze.devices.TrailblazeDeviceId,
  sessionDevices: List<String>,
): Boolean = deviceArgMatches(expectedDevice, reportedDevice) ||
  sessionDevices.any { sameBoundDevice(it, expectedDevice) }

/**
 * The MCP session a lifecycle command should act through, plus the scope it came from (null for
 * the unscoped session) so the caller clears the right pointer once the session ends.
 *
 * [rosterDevices] is the cast that session addresses by name, empty for a single-device session.
 * Lifecycle commands need it because the daemon reports ONE current device per session and a
 * `switchDevice` handover moves that to a companion — so the device the user names is not
 * necessarily the one the daemon answers with. See [stopBoundSessionIfMatches].
 */
internal class SessionLifecycleClient(
  val client: CliMcpClient,
  val sessionScope: String?,
  val rosterDevices: List<String> = emptyList(),
) {
  /**
   * True when the session this opened addresses devices BY NAME.
   *
   * [rosterDevices] answers it for the scoped path, which read the roster to choose that scope in
   * the first place. The unscoped path never looked, and a raw MCP `device(action=BIND)` can leave a
   * roster on the unscoped session, so that case falls back to the INFO block `connectReusable`
   * already fetched — no extra round trip either way.
   */
  fun holdsNamedRoster(): Boolean = rosterDevices.isNotEmpty() ||
    extractNamedDeviceRoster(client.reusedSessionProbeContent.orEmpty()).isNotEmpty()
}

/**
 * Opens the MCP session that owns [device]'s CLI session, for the commands that act on "the
 * current session" — `session stop` and `session end`.
 *
 * Which session that is depends on how it was started, so this mirrors the choice `session start`
 * made rather than picking one unconditionally:
 *
 *  - `session start --bind …` attaches to the per-device scope, because the named roster lives on
 *    the daemon-side MCP session context and `step` / `verify` / `tool` have to reach it. Its
 *    lifecycle has to be driven from there too: the stop-capture callback is per-context, so a
 *    STOP sent from any other session ends the recording while leaving video and log capture
 *    unfinalized and the roster's device claims held.
 *  - A plain `session start` opens the unscoped session and leaves its capture callback there, so
 *    routing every stop through the device scope would break the single-device flow the same way.
 *
 * The named roster is what distinguishes them, and only a bound session has one — a scope created
 * by `step` holds a session with no bindings and is left alone.
 *
 * Costs no round trip beyond the connect it already needs when [device] has its own scope. The
 * scope's session file is checked first, so a device that never had a scope is decided locally;
 * when it did, the roster is read off [CliMcpClient.reusedSessionProbeContent] — the INFO block
 * `connectReusable` already fetched to verify the session. `createIfMissing = false` keeps the
 * probe read-only: a stale pointer must not be answered by minting a session, which would leave an
 * orphan on the daemon and overwrite the scope's stored target app.
 *
 * A device with NO scope of its own may still belong to a cast — every member but the start device
 * is in exactly that state — so [sessionOwningBoundDevice] asks the scopes that do exist before the
 * unscoped fallback is taken.
 *
 * Falling back is reported rather than silent, because for a bound session it IS the
 * unfinalized-capture failure described above and the exit code alone would say nothing.
 */
internal suspend fun openSessionLifecycleClient(
  port: Int,
  device: String,
): SessionLifecycleClient {
  val scope = cliDeviceSessionScope(device)
  if (CliMcpClient.sessionFile(port, scope).exists()) {
    val scoped = try {
      CliMcpClient.connectReusable(port, sessionScope = scope, createIfMissing = false)
    } catch (e: Exception) {
      Console.error(
        "Note: could not reach the '$scope' MCP session (${e.message}); acting through the " +
          "unscoped session. If this device was started with `session start --bind`, its video " +
          "and log capture may not finalize.",
      )
      null
    }
    if (scoped != null) {
      val roster = extractNamedDeviceRoster(scoped.reusedSessionProbeContent.orEmpty())
      if (roster.isNotEmpty()) {
        return SessionLifecycleClient(scoped, scope, rosterDevices = rosterDeviceIds(roster))
      }
      scoped.close()
    }
  }
  sessionOwningBoundDevice(port, device)?.let { return it }
  return SessionLifecycleClient(CliMcpClient.connectReusable(port), null)
}

/**
 * The bound session whose roster names [device], found by probing the scopes that do have a pointer
 * on [port]. Null when none does.
 *
 * Reached whenever [device]'s own scope did not produce a bound session — it has no pointer, its
 * pointer could not be reached, or the session it names carries no roster (a scope created by
 * `step`). The case that matters is a cast companion, which is in exactly the first state: only the
 * start device's scope (and the alias `session start` publishes for its resolved spelling) point at
 * the roster-owning session. Falling straight through to the unscoped session is not harmless there:
 * `getBoundDeviceId()` reads the PROCESS-WIDE selected device, which during a live cast is a cast
 * member, so a `session stop -d <companion>` can pass its ownership check and issue STOP through a
 * context whose `stopCaptureCallback` is empty — reporting success while video and log capture never
 * finalize and the roster keeps its device claims.
 *
 * Deliberately not solved by publishing an alias per bound device at start. A pointer is shared by
 * every command that resolves a scope, so aliasing `cli-<companion>` onto the cast session would
 * also redirect `step -d <companion>`, which would then act on whichever member is ACTIVE rather
 * than the one named — trading a lifecycle inconvenience for a silent wrong-device action.
 *
 * Costs one connect-and-probe per existing pointer on this port, and runs only on `session stop` /
 * `session end` (never on a device-driving command) and only after the direct scope lookup missed.
 * Read-only throughout: `createIfMissing = false`, so a stale pointer is skipped rather than
 * answered by minting a session. Scopes are searched in [CliMcpClient.scopesWithSessionFiles]'
 * sorted order, so which session a device resolves to does not vary between runs.
 *
 * Every candidate is isolated: the scopes walked belong to other commands and other terminals, so a
 * candidate this CLI version cannot read is skipped the way a failed connect is. Aborting here would
 * fail the lookup for the device the user actually named.
 *
 * The close sits in a `finally` because the guarantee it makes is "every exit path that does not
 * hand the client to the caller closes it", and `catch (Exception)` cannot make that: an `Error`
 * would leave the loop with the client still open, which is the leak this is here to prevent.
 * Cancellation propagates for the same reason it is not degraded to "no session" anywhere else in
 * this file — a cancelled CLI is not a device without a session.
 */
private suspend fun sessionOwningBoundDevice(port: Int, device: String): SessionLifecycleClient? {
  for (candidate in CliMcpClient.scopesWithSessionFiles(port)) {
    val client = connectReusableOrNull(port, sessionScope = candidate, createIfMissing = false)
      ?: continue
    var owner: SessionLifecycleClient? = null
    try {
      val rosterIds = rosterDeviceIds(extractNamedDeviceRoster(client.reusedSessionProbeContent.orEmpty()))
      if (rosterIds.any { sameBoundDevice(it, device) }) {
        owner = SessionLifecycleClient(client, candidate, rosterDevices = rosterIds)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // An unreadable foreign scope is not this lookup's problem — try the next one.
    } finally {
      if (owner == null) client.close()
    }
    if (owner != null) return owner
  }
  return null
}

/**
 * [CliMcpClient.connectReusable], or null when the scope holds no live session.
 *
 * Cancellation is rethrown rather than reported as "no session": on the JVM
 * [CancellationException] is a `RuntimeException`, so a bare `catch (Exception)` swallows it and
 * leaves a cancelled CLI probing the next scope or quietly opening a different session.
 */
internal suspend fun connectReusableOrNull(
  port: Int,
  sessionScope: String?,
  createIfMissing: Boolean,
): CliMcpClient? = try {
  CliMcpClient.connectReusable(port, sessionScope = sessionScope, createIfMissing = createIfMissing)
} catch (e: CancellationException) {
  throw e
} catch (_: Exception) {
  null
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
  /**
   * User-facing verb used in the structured error envelope (e.g. "Snapshot" →
   * `✗ Snapshot failed on …`). Defaults to the generic `"Command"` so callers
   * that don't care still get a valid envelope, but every action command
   * should pass its own verb so a multi-device / device-bind failure names
   * the actual subcommand the user typed rather than a generic placeholder.
   */
  verb: String = "Command",
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
    val resolvedDevice = when (val r = resolveDeviceWithAutodetect(flag = device, port = port, verb = verb)) {
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
        // Eviction runs AFTER the envelope so the user reads "what failed"
        // before the supplementary "what was done about it" — see the kdoc
        // on [evictShellPinIfMatches] for the ordering rationale.
        evictShellPinIfMatches(resolvedDevice, deviceError)
        return@runBlocking INFRA_FAILED.code
      }
      runActionWithIoEnvelope(target = resolvedDevice, verb = verb, action = { action(client) })
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
  /**
   * User-facing verb used in the structured error envelope (e.g. "Snapshot" →
   * `✗ Snapshot failed on …`). Defaults to the generic `"Command"` so callers
   * that don't care still get a valid envelope, but every action command
   * should pass its own verb so a multi-device / device-bind failure names
   * the actual subcommand the user typed rather than a generic placeholder.
   */
  verb: String = "Command",
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
    val resolvedDevice = when (val r = resolveDeviceWithAutodetect(flag = device, port = port, verb = verb)) {
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
        // Eviction runs AFTER the envelope so the user reads "what failed"
        // before the supplementary "what was done about it" — see the kdoc
        // on [evictShellPinIfMatches] for the ordering rationale.
        evictShellPinIfMatches(resolvedDevice, deviceError)
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
        // For `--target=clear`: clear the file-pin FIRST so that if the file
        // write fails, the daemon-side override stays as the user's source
        // of truth (preserving consistency rather than silently diverging).
        // For a set (non-clear) target: the file-pin already reflects the
        // pinned value via `device connect`'s write path, so we only need
        // the daemon-side re-apply on each action call.
        if (daemonCall.isClearRequest) {
          clearShellDevicePinTargetIfPossible()
        }
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
      runActionWithIoEnvelope(target = resolvedDevice, verb = verb, action = { action(client) })
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
  sessionScope: String? = null,
  targetAppId: String? = null,
  action: suspend (CliMcpClient) -> Int,
): Int {
  if (!verbose) Console.enableQuietMode()
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  val effectiveTargetAppId = targetAppId ?: CliConfigHelper.getOrCreateConfig().selectedTargetAppId

  return runBlocking {
    val mcpClient = connectOrStartDaemonReusable(
      port,
      targetAppId = effectiveTargetAppId,
      sessionScope = sessionScope,
    )
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
  verb: String = "Command",
  action: suspend () -> Int,
): Int = try {
  action()
} catch (e: java.io.IOException) {
  reportCliError(
    verb = verb,
    target = target,
    reason = describeThrowableForUser(e),
    hint = "is the daemon running? try `trailblaze app start`",
  )
  INFRA_FAILED.code
}

/**
 * Refuses a daemon port a device could be allocated, before anything probes it.
 *
 * Every caller today gets its port from [CliConfigHelper.resolveEffectiveHttpPort], which already
 * rejects one, so this cannot fire in production as written. It is here as a contract on the two
 * entry points every auto-start path funnels through, because the failure it prevents is silent: a
 * device's `adb forward` answers the health probe, so the auto-start path below would report a
 * healthy daemon and then fail when MCP initialization hit a device RPC server.
 */
private fun requireConnectablePort(port: Int) {
  TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(port, "The daemon HTTP port")
}

/**
 * Connect to the daemon for a one-shot command, auto-starting it if missing.
 * Never reads or writes the persisted session file.
 */
internal suspend fun connectOrStartDaemonOneShot(port: Int): CliMcpClient? {
  requireConnectablePort(port)
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
    val outcome = cliTryStartDaemon(port)
    when (outcome) {
      // Already reported, and with the accurate reason — a daemon IS running, it just isn't
      // answering, so the generic "not running" envelope below would send the user the wrong way.
      DaemonAutoStartOutcome.REFUSED_PORT_ALREADY_OWNED -> return null
      DaemonAutoStartOutcome.FAILED -> {
        reportDaemonUnreachable("Trailblaze daemon is not running and could not be auto-started")
        return null
      }
      DaemonAutoStartOutcome.STARTED, DaemonAutoStartOutcome.ALREADY_RUNNING -> Unit
    }
    try {
      CliMcpClient.connectOneShot(port = port)
    } catch (_: Exception) {
      reportDaemonUnreachable(daemonReconnectFailureReason(outcome, port))
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
  requireConnectablePort(port)
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
    val outcome = cliTryStartDaemon(port)
    when (outcome) {
      // See the one-shot path above: an owned-but-unresponsive port has already been reported.
      DaemonAutoStartOutcome.REFUSED_PORT_ALREADY_OWNED -> return null
      DaemonAutoStartOutcome.FAILED -> {
        reportDaemonUnreachable("Trailblaze daemon is not running and could not be auto-started")
        return null
      }
      DaemonAutoStartOutcome.STARTED, DaemonAutoStartOutcome.ALREADY_RUNNING -> Unit
    }
    try {
      CliMcpClient.connectReusable(
        port = port,
        targetAppId = targetAppId,
        sessionScope = sessionScope,
      )
    } catch (_: Exception) {
      reportDaemonUnreachable(daemonReconnectFailureReason(outcome, port))
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
 * What to do about a daemon whose version may not match the CLI's.
 *
 *  - [KEEP_OK] — versions match (or the daemon didn't report a version). Nothing to do.
 *  - [RESTART] — version mismatch AND the daemon is idle. Safe to stop and restart.
 *  - [KEEP_BUSY] — version mismatch BUT the daemon has in-flight runs. Leave it running;
 *    stopping it would sever those runs (the daemon may be mid-run for another
 *    shell/checkout — the port is machine-global, version staleness is per-checkout).
 *    The restart happens once it goes idle.
 */
internal enum class StaleDaemonAction { KEEP_OK, RESTART, KEEP_BUSY }

/**
 * Pure decision for [checkAndRestartStaleDaemon] — separated from the daemon I/O so the
 * three-way matrix (match / mismatch-idle / mismatch-busy) is unit-testable without a
 * live daemon. See `StaleDaemonActionTest`.
 */
internal fun staleDaemonAction(
  cliVersion: String,
  daemonVersion: String?,
  activeRuns: Int,
): StaleDaemonAction {
  if (daemonVersion == null || daemonVersion == cliVersion) return StaleDaemonAction.KEEP_OK
  return if (activeRuns > 0) StaleDaemonAction.KEEP_BUSY else StaleDaemonAction.RESTART
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
      when (staleDaemonAction(cliVersion, daemonVersion, status.activeRuns)) {
        StaleDaemonAction.KEEP_OK -> return true
        StaleDaemonAction.KEEP_BUSY -> {
          // Console.info (not Console.log) so the developer actually sees WHY their newer
          // CLI is talking to an older daemon — this line is the only explanation, and
          // Console.log is suppressed in CLI quiet mode.
          Console.info(
            "Daemon version mismatch (daemon=$daemonVersion, cli=$cliVersion) but it has " +
              "${status.activeRuns} in-flight run(s) — leaving it running; it restarts once idle:" +
              status.activeRunSummaries.joinToString("") { "\n  - $it" },
          )
          return true
        }
        StaleDaemonAction.RESTART -> {
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
    }
  } catch (_: Exception) {
    // Daemon not running or status check failed — will be handled by the connect-or-start helpers
  }
  return true
}

/** What [cliTryStartDaemon] did, so the caller knows whether a (re)connect is worth attempting. */
internal enum class DaemonAutoStartOutcome {
  /** A daemon is up because we started it — the caller should try its connection again. */
  STARTED,

  /**
   * A daemon already owned the port and answered its health check, so nothing was spawned. The
   * caller should try its connection again exactly as for [STARTED], but must attribute a second
   * failure to the incumbent rather than to a failed startup.
   */
  ALREADY_RUNNING,

  /**
   * A listener already owns the port and never answered as a daemon, so we deliberately did NOT
   * start a second one. The specific failure is reported here (the caller's generic "not running"
   * envelope would be wrong — the port is held), so the caller should just give up.
   */
  REFUSED_PORT_ALREADY_OWNED,

  /** No daemon owns the port and we could not start one. The caller reports it. */
  FAILED,
}

/**
 * Why a reconnect failed after [cliTryStartDaemon] reported a daemon was available.
 *
 * Phrased for what actually happened: "after starting it" sends the user to look at daemon startup,
 * which is the wrong place entirely when the daemon was already running and merely refused the
 * command.
 */
private fun daemonReconnectFailureReason(outcome: DaemonAutoStartOutcome, port: Int): String =
  when (outcome) {
    DaemonAutoStartOutcome.ALREADY_RUNNING ->
      "the daemon already running on port $port passes health checks but did not accept this " +
        "command — restart it with `trailblaze app --stop` then re-run, or bypass it with `--no-daemon`"

    else -> "failed to connect to Trailblaze daemon after starting it"
  }

/** Whether [cliTryStartDaemon] is allowed to spawn, and if not, why. */
internal enum class DaemonAutoStartAction {
  /** Nothing holds the port and spawning is allowed. */
  SPAWN,

  /** The port is free, but the kill switch forbids putting a daemon on it. */
  REFUSE_AUTOSTART_DISABLED,

  /**
   * A listener holds the port and may be a daemon still finishing its boot, so wait for it to
   * answer instead of spawning past it.
   */
  WAIT_FOR_INCUMBENT,

  /**
   * The port is held and nothing on it ever accepted a connection, so there is no listener here to
   * wait for and no daemon can bind it either.
   */
  REFUSE_PORT_HELD,
}

/**
 * Pure decision for [cliTryStartDaemon] — separated from the process spawning so the matrix is
 * unit-testable without a live daemon, in the same shape as [staleDaemonAction]. See
 * `DaemonAutoStartActionTest`.
 *
 * The rule that matters: **a failed connection is not evidence that no daemon is running.** A
 * daemon that is alive but wedged (its event loop saturated, its MCP surface not answering) fails
 * exactly the same connect that a missing daemon fails. Auto-starting on that signal alone is what
 * let ten daemons accumulate against one emulator — each `trailblaze run` failed to connect, spawned
 * another daemon, watched `waitForDaemon` go green off the *old* daemon's still-healthy `/ping`,
 * failed to connect again, and left the new JVM behind to contend for the same device.
 *
 * So the port is probed for an owner before any spawn, and ownership vetoes the spawn.
 *
 * Ownership is checked **before** the auto-start kill switch, because the two answer different
 * questions. The kill switch says "do not spawn"; ownership says "a daemon is already there".
 * Refusing on ownership spawns nothing, so it honours the kill switch either way — but it reports
 * the accurate reason. Order it the other way and the CI-default path (every Gradle `Test` task
 * sets `TRAILBLAZE_DISABLE_DAEMON_AUTOSTART`) tells the user a wedged daemon "is not running" and
 * hints at starting one, which is exactly the misdirection this change exists to remove.
 *
 * The kill switch does not decide whether a listener is worth *waiting* on, because waiting spawns
 * nothing and so honours the switch either way. The switch being set is in fact when waiting
 * matters most: it is how the user is told to run a daemon (`trailblaze app` by hand, which every
 * Gradle `Test` task's environment expects), and a cold uber-jar binds the port 30s+ before it
 * serves — so refusing a `LISTENING` port here would refuse the very daemon the error text asks
 * for, and only during its boot, which reads as flaky.
 *
 * Switched on [hold] rather than tested with `hold ==` guards so a new [DaemonPortHold] is a
 * compile error here instead of falling through to [DaemonAutoStartAction.SPAWN].
 */
internal fun daemonAutoStartAction(
  autoStartDisabled: Boolean,
  hold: DaemonPortHold,
): DaemonAutoStartAction = when (hold) {
  // Nothing accepted across the settle window, so there is no listener here to wait for a `/ping`
  // from — a daemon still finishing its boot accepts the moment it binds, which reads as LISTENING.
  DaemonPortHold.HELD_SILENT -> DaemonAutoStartAction.REFUSE_PORT_HELD
  DaemonPortHold.LISTENING -> DaemonAutoStartAction.WAIT_FOR_INCUMBENT
  DaemonPortHold.FREE ->
    if (autoStartDisabled) {
      DaemonAutoStartAction.REFUSE_AUTOSTART_DISABLED
    } else {
      DaemonAutoStartAction.SPAWN
    }
}

/**
 * What, if anything, holds a daemon port, and how the two probes tell those answers apart.
 *
 * The LISTEN socket is the only thing that reliably says which process owns a daemon port: a
 * pidfile can be stale after a SIGKILL, and `/ping` answering (or not) describes the daemon's
 * health, not its ownership of the port. A wedged daemon still holds its listener, which is
 * precisely the case that must veto a second spawn.
 *
 * Two probes, because they fail asymmetrically:
 *  - **A completed connect proves ownership.** Both loopback families are tried, since the daemon's
 *    HTTP connector binds `::` and on an IPv6-only loopback host the IPv4 connect fails.
 *  - **A failed connect proves nothing.** A listener that has stopped calling `accept()` fills its
 *    backlog, after which the kernel drops further SYNs — the connect then times out exactly as it
 *    does against an empty port. That is not a corner case here: it is the wedged daemon this veto
 *    exists for, made worse by every hung CLI command queued behind it. So the fallback asks the
 *    question that actually matters, by attempting the bind a new daemon would attempt.
 *
 * Neither probe proves the owner is a Trailblaze daemon — deliberately, since either way a second
 * daemon cannot have that port. Messages built on this answer say "a listener", not "a daemon".
 */
internal enum class DaemonPortHold {
  /** Nothing holds the port. A daemon can bind it. */
  FREE,

  /**
   * Something completed a TCP handshake, so a LISTEN socket is definitely there. It may be a daemon
   * still finishing its boot, a wedged one, or an unrelated listener; all three mean a second
   * daemon cannot have the port.
   */
  LISTENING,

  /**
   * Nothing accepted, and the port cannot be bound either. Two states look like this from
   * userspace and they want opposite handling, which is why [settleDaemonPortHold] gives it time
   * rather than deciding at once:
   *  - a listener whose accept backlog is full — the wedged daemon, which must veto a spawn and
   *    never releases the port on its own;
   *  - an unrelated socket holding the port as its *source* port, which listens for nothing and
   *    goes away by itself. Whether this even reaches here is platform-dependent: BSD lets a
   *    server bind past an established socket, so it reads FREE on macOS, while Linux wants
   *    `SO_REUSEADDR` on both sockets before allowing that and reports the port in use.
   */
  HELD_SILENT,
}

/** One instantaneous reading of who holds [port]. */
internal fun daemonPortHold(port: Int): DaemonPortHold = when {
  DAEMON_LOOPBACK_PROBE_HOSTS.any {
    HostDriverPortUtils.isPortReachable(it, port, timeoutMs = DAEMON_PORT_OWNERSHIP_PROBE_MS)
  } -> DaemonPortHold.LISTENING

  isDaemonPortBindable(port) -> DaemonPortHold.FREE
  else -> DaemonPortHold.HELD_SILENT
}

/**
 * [daemonPortHold], but a [DaemonPortHold.HELD_SILENT] reading is re-read for a short window
 * before it is believed.
 *
 * Time is what separates the two states that read as held-and-silent. A listener never releases
 * its port, so a wedged daemon still vetoes the spawn. An unrelated socket holding the port as its
 * source port does release it — and this daemon port sits inside the OS ephemeral range, so any
 * outbound connection on the machine can land on it (measured, and documented at length in
 * `MockRpcServerTest`). Without the re-read, that transient collision would be reported as an
 * owned port and refuse an auto-start that should have succeeded. This is what lets
 * [isDaemonPortBindable] keep asking the questions a real server's bind asks rather than tuning
 * its probe to dodge the collision.
 *
 * On macOS that collision no longer reaches here at all, since a server can bind past an
 * established socket; the re-read is what keeps the behaviour the same on a platform where it
 * can't.
 *
 * Only the ambiguous answer waits: FREE and LISTENING return on the first probe, so the common
 * paths pay nothing.
 *
 * [budgetMs] bounds when the last re-read may *start*, not total elapsed time — a probe already in
 * flight cannot be cut short. It is close to the same thing for the collision this window exists
 * for, whose probes return immediately; it is not for a wedged listener, whose probes each spend a
 * connect timeout, and that port is never going to clear anyway.
 */
internal fun settleDaemonPortHold(
  budgetMs: Long = DAEMON_PORT_HOLD_SETTLE_MS,
  pollMs: Long = DAEMON_PORT_HOLD_POLL_MS,
  // Monotonic on purpose: a wall clock stepped backwards by NTP would extend this window, and
  // stepped forwards would skip it, on the one path whose whole job is to spend a fixed 3s.
  now: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
  sleep: (Long) -> Unit = { Thread.sleep(it) },
  probe: () -> DaemonPortHold,
): DaemonPortHold {
  val startedAt = now()
  var hold = probe()
  if (hold != DaemonPortHold.HELD_SILENT) return hold
  val deadline = startedAt + budgetMs
  // The clock is read after each probe, not just after each sleep, because the probe is the
  // expensive half: `daemonPortHold` spends up to DAEMON_PORT_OWNERSHIP_PROBE_MS per loopback family
  // on a wedged listener, which is exactly the port that reaches here. Counting only the sleeps let
  // a 3s budget run past 5s while managing two re-reads instead of eleven.
  while (now() + pollMs <= deadline) {
    sleep(pollMs)
    hold = probe()
    if (hold != DaemonPortHold.HELD_SILENT) return hold
  }
  return hold
}

/** Ownership as the auto-start veto sees it. */
internal fun daemonPortHoldForAutoStart(port: Int): DaemonPortHold =
  // A port number no socket could name reads as held-and-silent for the same reason a wedged
  // listener does, but nothing about it is transient, so re-reading it just spends the settle
  // window before reporting a misconfigured `TRAILBLAZE_PORT` the same way either way.
  if (!HostDriverPortUtils.isValidTcpPort(port)) {
    daemonPortHold(port)
  } else {
    settleDaemonPortHold { daemonPortHold(port) }
  }

/**
 * True iff a daemon could bind [port] right now, and nothing is already there — the direct form of
 * the only question the auto-start veto needs answered.
 *
 * The daemon's connector binds `::` (`SslConfig.configureForSelfSignedSsl`) with the usual server
 * socket options, and [HostDriverPortUtils.isPortBindable] deliberately binds the same way it
 * does; the mistakes available in either direction are documented there. It errs toward refusing:
 * a port that already has a narrower listener on it is called unbindable even where the wildcard
 * bind would win, because a daemon whose port is shadowed for every loopback client is not a daemon
 * anyone can reach. Transient collisions on this port — it sits inside the OS ephemeral range — are
 * handled by re-reading [DaemonPortHold.HELD_SILENT] rather than by narrowing the probe. See
 * [settleDaemonPortHold].
 */
internal fun isDaemonPortBindable(port: Int): Boolean = HostDriverPortUtils.isPortBindable(port)

/**
 * The same addresses the bind probe covers, and deliberately the same list: the connect and the
 * bind answer two halves of one question about one daemon, so a non-loopback bind address has to
 * move both at once.
 */
private val DAEMON_LOOPBACK_PROBE_HOSTS = HostDriverPortUtils.LOOPBACK_BIND_PROBE_ADDRESSES

/**
 * Bound on the loopback connect that probes for a daemon already owning the port. A local listener
 * accepts effectively instantly; this only stops a pathological stack from parking the CLI before
 * it has printed anything.
 */
private const val DAEMON_PORT_OWNERSHIP_PROBE_MS = 1_000

/**
 * How long a held-but-silent port is re-read before it is believed. Short on purpose: it is paid
 * only in an ambiguous state, and the alternative for a transient collision used to be the full
 * [DaemonClient.MAX_WAIT_FOR_DAEMON_MS] budget spent polling for a `/ping` nothing would ever send.
 */
private const val DAEMON_PORT_HOLD_SETTLE_MS = 3_000L

private const val DAEMON_PORT_HOLD_POLL_MS = 250L

/**
 * Auto-start the Trailblaze daemon in headless mode.
 */
private fun cliTryStartDaemon(
  port: Int,
  childEnvironment: Map<String, String> = emptyMap(),
  claimRetryRemaining: Int = 1,
  respectAutoStartDisable: Boolean = true,
): DaemonAutoStartOutcome {
  if (DaemonClient(port = port).use { it.isRunningBlocking() }) {
    return DaemonAutoStartOutcome.ALREADY_RUNNING
  }
  val autoStartDisabled = daemonAutoStartIsBlocked(respectAutoStartDisable)
  val hold = daemonPortHoldForAutoStart(port)
  when (daemonAutoStartAction(autoStartDisabled = autoStartDisabled, hold = hold)) {
    DaemonAutoStartAction.REFUSE_AUTOSTART_DISABLED -> {
      Console.error(
        "Daemon auto-start is disabled (TRAILBLAZE_DISABLE_DAEMON_AUTOSTART). " +
          "Start one manually with `trailblaze app` or unset the variable.",
      )
      return DaemonAutoStartOutcome.FAILED
    }

    DaemonAutoStartAction.REFUSE_PORT_HELD -> {
      reportCliError(
        verb = "Daemon connection",
        target = "port $port",
        reason = "port $port is held, but nothing on it accepted a connection. That is either a " +
          "listener too wedged to accept, or an unrelated socket holding the port. Either way a " +
          "daemon cannot bind it, so refusing to auto-start one",
        hint = "see what holds the port with `lsof -nP -iTCP:$port` (no `-sTCP:LISTEN` — it may " +
          "not be a listener), restart the daemon with `trailblaze app --stop` then re-run, or " +
          "bypass it with `--no-daemon`",
      )
      return DaemonAutoStartOutcome.REFUSED_PORT_ALREADY_OWNED
    }

    DaemonAutoStartAction.WAIT_FOR_INCUMBENT -> {
      // The incumbent is either a daemon still finishing its boot — a cold uber-jar start
      // legitimately takes 30s+, and it binds the port well before it serves anything — or one
      // that is up and wedged. Both hold the listener, and neither is a reason to add a second
      // daemon, so wait for this one to answer rather than spawning past it. A daemon that is
      // already healthy is detected on the first poll, so the wedged case pays nothing extra.
      Console.appendInfo("Port $port already has a listener — waiting for it to answer as a daemon")
      val incumbentAnswered = DaemonClient(port = port).use {
        it.waitForDaemon { Console.appendInfo(".") }
      }
      Console.info("") // newline after dots
      if (incumbentAnswered) return DaemonAutoStartOutcome.ALREADY_RUNNING

      reportCliError(
        verb = "Daemon connection",
        target = "port $port",
        reason = "another process is listening on port $port but never answered as a daemon — " +
          "the port is held, not free, so this is an unresponsive listener rather than a missing " +
          "daemon. Refusing to auto-start a second daemon on the same port and workspace",
        hint = "check what holds the port with `lsof -nP -iTCP:$port -sTCP:LISTEN`, restart the " +
          "daemon with `trailblaze app --stop` then re-run, or bypass it with `--no-daemon`",
      )
      return DaemonAutoStartOutcome.REFUSED_PORT_ALREADY_OWNED
    }
    DaemonAutoStartAction.SPAWN -> Unit // fall through and actually spawn
  }

  val launcher = findTrailblazeLauncher() ?: run {
    Console.error("Cannot auto-start daemon: trailblaze launcher not found.")
    return DaemonAutoStartOutcome.FAILED
  }

  val pidFile = daemonStartupClaimFile(port)

  val claimOwner = when (val claim = claimDaemonStartup(pidFile)) {
    is DaemonStartupClaim.Existing -> {
      Console.info("A Trailblaze daemon is already starting (PID ${claim.pid}); waiting for it.")
      Console.appendInfo("Waiting for Trailblaze daemon to be ready")
      val started = DaemonClient(port = port).use {
        it.waitForDaemon(isSpawnAlive = { processIsAlive(claim.pid) }) { Console.appendInfo(".") }
      }
      Console.info("")
      if (
        !started &&
        !processIsAlive(claim.pid) &&
        claimRetryRemaining > 0 &&
        waitForStartupClaimRelease(pidFile)
      ) {
        return cliTryStartDaemon(
          port,
          childEnvironment,
          claimRetryRemaining - 1,
          respectAutoStartDisable,
        )
      }
      return if (started) {
        DaemonAutoStartOutcome.ALREADY_RUNNING
      } else {
        DaemonAutoStartOutcome.FAILED
      }
    }
    DaemonStartupClaim.Unavailable -> {
      if (claimRetryRemaining > 0 && waitForStartupClaimPublication(pidFile, port)) {
        return cliTryStartDaemon(
          port,
          childEnvironment,
          claimRetryRemaining - 1,
          respectAutoStartDisable,
        )
      }
      Console.error("Cannot claim the daemon startup lock at ${pidFile.absolutePath}.")
      return DaemonAutoStartOutcome.FAILED
    }
    is DaemonStartupClaim.Owner -> claim
  }

  Console.log("Starting Trailblaze daemon...")
  val child = try {
    val pb = ProcessBuilder(daemonSpawnArgv(launcher, foreground = true, headless = true))
    pb.environment().putAll(childEnvironment)
    if (port != TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT) {
      pb.environment()["TRAILBLAZE_PORT"] = port.toString()
    }
    // Append (not DISCARD) so a spawned daemon that exits — e.g. losing the port-bind race
    // to a concurrent launch — leaves its exit reason recoverable from the daemon log.
    val daemonLogFile = xyz.block.trailblaze.ui.TrailblazeDesktopUtil.getDaemonLogFile()
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(daemonLogFile))
    pb.redirectError(ProcessBuilder.Redirect.appendTo(daemonLogFile))
    pb.start()
  } catch (e: Exception) {
    releaseDaemonStartupClaim(claimOwner.ownerFile)
    reportCliError(
      verb = "Daemon start",
      reason = describeThrowableForUser(e),
      hint = "try `trailblaze app start --foreground --headless` to see startup output directly",
    )
    return DaemonAutoStartOutcome.FAILED
  }
  if (!replaceDaemonStartupPid(claimOwner.ownerFile, child.pid())) {
    child.destroy()
    releaseDaemonStartupClaim(claimOwner.ownerFile)
    Console.error("Cannot record the daemon startup PID at ${pidFile.absolutePath}.")
    return DaemonAutoStartOutcome.FAILED
  }
  val claimReaper = scheduleDaemonStartupClaimReaper(pidFile, claimOwner.ownerFile, child.pid()) ?: run {
    child.destroy()
    releaseDaemonStartupClaim(claimOwner.ownerFile)
    Console.error("Cannot monitor the daemon startup claim at ${pidFile.absolutePath}.")
    return DaemonAutoStartOutcome.FAILED
  }

  Console.appendInfo("Waiting for Trailblaze daemon to be ready")
  val started = DaemonClient(port = port).use {
    it.waitForDaemon(isSpawnAlive = { child.isAlive }) { Console.appendInfo(".") }
  }
  Console.info("") // newline after dots
  if (started) {
    // Keep the claim for this child's lifetime. The detached reaper removes it on exit; retaining
    // it prevents a transiently failed health probe from electing a second daemon meanwhile.
    Console.log("Trailblaze daemon started.")
  } else if (!child.isAlive) {
    claimReaper.destroy()
    releaseDaemonStartupClaim(claimOwner.ownerFile)
    // A child that's already gone can't still be starting, so "needs more time" would send the
    // user to wait on a process that gave up. The exit code plus the daemon log is what actually
    // localizes a broken spawn.
    Console.error(
      "Trailblaze exited before the daemon became ready on port $port " +
        "(exit code ${child.exitValue()}).",
    )
    Console.error("Run `trailblaze app start --foreground --headless` to see startup output directly.")
  } else {
    Console.error(
      "Daemon did not start within ${DaemonClient.MAX_WAIT_FOR_DAEMON_MS / 1000}s. " +
        "If a source build is in progress it may need more time.",
    )
    Console.error("Run `trailblaze app start --foreground --headless` to see startup output directly.")
  }
  return if (started) DaemonAutoStartOutcome.STARTED else DaemonAutoStartOutcome.FAILED
}

internal sealed interface DaemonStartupClaim {
  data class Owner(val ownerFile: File) : DaemonStartupClaim
  data class Existing(val pid: Long) : DaemonStartupClaim
  data object Unavailable : DaemonStartupClaim
}

internal fun daemonStartupClaimFile(port: Int): File = File(
  xyz.block.trailblaze.ui.TrailblazeDesktopUtil.getDefaultAppDataDirectory(),
  "daemon-$port.pid.starting",
)

private fun waitForStartupClaimRelease(pidFile: File): Boolean {
  repeat(60) {
    if (!pidFile.exists()) return true
    Thread.sleep(100)
  }
  return !pidFile.exists()
}

/**
 * Wait for the winner of the directory-lock election to publish its owner, release its claim, or
 * make its daemon ready. The claim normally remains for the daemon's lifetime, but a winner that
 * cannot publish its owner releases the directory so another starter can retry the election.
 */
private fun waitForStartupClaimPublication(pidFile: File, port: Int): Boolean =
  waitForStartupClaimPublication(pidFile, daemonIsReady = {
    DaemonClient(port = port).use { it.isRunningBlocking() }
  })

internal fun waitForStartupClaimPublication(
  pidFile: File,
  daemonIsReady: () -> Boolean,
  maxAttempts: Int = 60,
  retryDelayMillis: Long = 100,
): Boolean {
  repeat(maxAttempts) {
    if (daemonIsReady() || readDaemonStartupOwner(pidFile) != null || !pidFile.exists()) return true
    Thread.sleep(retryDelayMillis)
  }
  return daemonIsReady() || readDaemonStartupOwner(pidFile) != null || !pidFile.exists()
}

/** Atomically elect one daemon starter across concurrent CLI processes. */
internal fun claimDaemonStartup(
  pidFile: File,
  claimantPid: Long = ProcessHandle.current().pid(),
): DaemonStartupClaim {
  val parent = pidFile.parentFile ?: return DaemonStartupClaim.Unavailable
  if (!parent.mkdirs() && !parent.isDirectory) return DaemonStartupClaim.Unavailable
  repeat(4) { attempt ->
    var createdOwnerFile: File? = null
    try {
      java.nio.file.Files.createDirectory(pidFile.toPath())
      val identity = processStartIdentity(claimantPid)
        ?: error("Cannot identify daemon startup claimant $claimantPid")
      createdOwnerFile = pidFile.resolve("owner-${java.util.UUID.randomUUID()}")
      createdOwnerFile.writeText("$claimantPid\n$identity\n")
      return DaemonStartupClaim.Owner(createdOwnerFile)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
      if (!pidFile.isDirectory) return DaemonStartupClaim.Unavailable
      val existingOwner = readDaemonStartupOwner(pidFile)
      if (existingOwner != null && processIsSame(existingOwner)) {
        return DaemonStartupClaim.Existing(existingOwner.pid)
      }
      if (existingOwner != null) {
        releaseDaemonStartupClaim(existingOwner.ownerFile)
      } else {
        // Directory creation is the atomic election. The winner publishes its owner record just
        // afterwards; an observer must never reclaim that short handoff window, because doing so
        // could elect a second starter. A crashed unpublished claimant is deliberately surfaced
        // as unavailable rather than risking a duplicate daemon.
        Thread.sleep(10)
      }
    } catch (_: Exception) {
      createdOwnerFile?.let(::releaseDaemonStartupClaim)
      runCatching { java.nio.file.Files.delete(pidFile.toPath()) }
      return DaemonStartupClaim.Unavailable
    }
  }
  return DaemonStartupClaim.Unavailable
}

private fun processIsAlive(pid: Long): Boolean =
  ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

private data class DaemonStartupOwner(
  val ownerFile: File,
  val pid: Long,
  val processIdentity: String,
)

private fun readDaemonStartupOwner(claimDirectory: File): DaemonStartupOwner? {
  val ownerFile = claimDirectory.listFiles()?.singleOrNull {
    it.isFile && it.name.startsWith("owner-") && !it.name.contains(".new")
  }
    ?: return null
  val lines = runCatching { ownerFile.readLines() }.getOrNull() ?: return null
  val pid = lines.getOrNull(0)?.toLongOrNull() ?: return null
  val identity = lines.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
  return DaemonStartupOwner(ownerFile, pid, identity)
}

private fun processIsSame(owner: DaemonStartupOwner): Boolean =
  processIsAlive(owner.pid) && processStartIdentity(owner.pid) == owner.processIdentity

private fun processStartIdentity(pid: Long): String? = runCatching {
  val process = ProcessBuilder("ps", "-o", "lstart=", "-p", pid.toString())
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .also { it.environment()["LC_ALL"] = "C" }
    .start()
  val identity = process.inputStream.bufferedReader().use { it.readText().trim() }
  if (process.waitFor() == 0) identity.takeIf { it.isNotBlank() } else null
}.getOrNull()

/** Replace a live claimant PID without exposing an empty or partial startup lock to another CLI. */
internal fun replaceDaemonStartupPid(ownerFile: File, childPid: Long): Boolean {
  val claimDirectory = ownerFile.parentFile?.takeIf { it.isDirectory } ?: return false
  val identity = processStartIdentity(childPid) ?: return false
  val replacement = runCatching {
    java.nio.file.Files.createTempFile(claimDirectory.toPath(), "owner-", ".new")
  }.getOrNull() ?: return false
  return try {
    java.nio.file.Files.writeString(replacement, "$childPid\n$identity\n")
    java.nio.file.Files.move(
      replacement,
      ownerFile.toPath(),
      java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
    true
  } catch (_: Exception) {
    false
  } finally {
    java.nio.file.Files.deleteIfExists(replacement)
  }
}

/** Release this exact owner without exposing a path that another process could have replaced. */
internal fun releaseDaemonStartupClaim(ownerFile: File) {
  val removedOwner = runCatching {
    java.nio.file.Files.deleteIfExists(ownerFile.toPath())
  }.getOrDefault(false)
  if (removedOwner) {
    val claimDirectory = ownerFile.parentFile
    claimDirectory.listFiles()?.filter {
      it.isFile && it.name.startsWith("owner-") && it.name.endsWith(".new")
    }?.forEach { handoff -> runCatching { java.nio.file.Files.deleteIfExists(handoff.toPath()) } }
    runCatching { java.nio.file.Files.delete(claimDirectory.toPath()) }
  }
}

/** Reclaim a lifetime claim once its recorded owner is no longer alive. */
internal fun reclaimDaemonStartupClaimIfOwnerExited(pidFile: File) {
  val owner = readDaemonStartupOwner(pidFile) ?: return
  if (!processIsSame(owner)) releaseDaemonStartupClaim(owner.ownerFile)
}

/**
 * A source build can outlive the CLI's wait window. Leave one detached watcher to remove this
 * exact child's startup claim when it exits, without letting the short-lived command JVM linger.
 * HTTP readiness is insufficient: another daemon may have won the port while this child is alive.
 * Positional shell arguments keep paths and values out of the script syntax.
 */
internal fun scheduleDaemonStartupClaimReaper(
  pidFile: File,
  ownerFile: File,
  childPid: Long,
): Process? =
  runCatching {
    val identity = processStartIdentity(childPid) ?: return@runCatching null
    ProcessBuilder(
      "sh",
      "-c",
      """
        pid="${'$'}1"; owner="${'$'}2"; started="${'$'}3"; lock="${'$'}4"
        process_identity() {
          LC_ALL=C ps -o lstart= -p "${'$'}1" 2>/dev/null | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*${'$'}//'
        }
        while kill -0 "${'$'}pid" 2>/dev/null && [ "${'$'}(process_identity "${'$'}pid")" = "${'$'}started" ]; do
          sleep 5
        done
        if [ -f "${'$'}owner" ] && rm -f "${'$'}owner"; then
          rmdir "${'$'}lock" 2>/dev/null || true
        fi
      """.trimIndent(),
      "_",
      childPid.toString(),
      ownerFile.absolutePath,
      identity,
      pidFile.absolutePath,
    )
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
  }.getOrNull()

/** Ensure the HTTP daemon exists without opening an MCP session. */
internal fun ensureDaemonServerRunning(
  port: Int,
  childEnvironment: Map<String, String> = emptyMap(),
  restartStaleDaemon: Boolean = true,
  respectAutoStartDisable: Boolean = true,
): Boolean {
  requireConnectablePort(port)
  if (restartStaleDaemon && !checkAndRestartStaleDaemon(port)) return false
  DaemonClient(port = port).use { daemon ->
    if (daemon.isRunningBlocking()) return true
  }
  return when (
    cliTryStartDaemon(
      port = port,
      childEnvironment = childEnvironment,
      respectAutoStartDisable = respectAutoStartDisable,
    )
  ) {
    DaemonAutoStartOutcome.STARTED, DaemonAutoStartOutcome.ALREADY_RUNNING -> true
    DaemonAutoStartOutcome.REFUSED_PORT_ALREADY_OWNED, DaemonAutoStartOutcome.FAILED -> false
  }
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
      reclaimDaemonStartupClaimIfOwnerExited(daemonStartupClaimFile(port))
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
        reclaimDaemonStartupClaimIfOwnerExited(daemonStartupClaimFile(port))
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
