package xyz.block.trailblaze.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.server.endpoints.CliDaemonCapabilities
import xyz.block.trailblaze.mcp.newtools.DeviceManagerToolSet
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Shared usage-error message printed by every `session …` subcommand that accepts the
 * session ID both positionally and via `--id`. Kept here so the wording stays uniform.
 */
internal const val SESSION_ID_CONFLICT_MESSAGE: String =
  "Positional <session-id> and --id are two ways to spell the same thing — pass only one."

/**
 * Manage the CLI session — save recordings, end the session.
 *
 * Examples:
 *   trailblaze session info                    - Show session status
 *   trailblaze session save --title login_flow - Save recording as a trail
 *   trailblaze session save --id abc123        - Save a specific session as a trail
 *   trailblaze session recording               - Print recording YAML to stdout
 *   trailblaze session recording --id abc123   - Print recording for a specific session
 *   trailblaze session stop                    - End session, release device
 *   trailblaze session stop --save             - Save and end in one step
 */
@Command(
  name = "session",
  mixinStandardHelpOptions = true,
  description = ["Manage the current device session — save it as a replayable trail, inspect steps, end it"],
  subcommands = [
    SessionStartCommand::class,
    SessionStopCommand::class,
    SessionSaveCommand::class,
    SessionRecordingCommand::class,
    SessionInfoCommand::class,
    SessionListCommand::class,
    SessionArtifactsCommand::class,
    SessionDeleteCommand::class,
    SessionEndCommand::class,
  ],
)
class SessionCommand : Callable<Int> {
  @CommandLine.ParentCommand
  internal lateinit var cliRoot: TrailblazeCliCommand

  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return TrailblazeExitCode.SUCCESS.code
  }
}

/**
 * Start a new session.
 *
 * The session is bound to one device — the CLI is single-shot over MCP and does
 * not durably honor a daemon-side "active" session, so we resolve the device on
 * every invocation via `--device` flag → `TRAILBLAZE_DEVICE` env var → MISUSE.
 * The flag itself is optional at parse time: pin a per-shell ambient with
 * `eval $(trailblaze device connect <platform>)` once and subsequent commands
 * pick it up from the env var. `--target` and `--mode` may be omitted if
 * already in config.
 *
 * Multi-device: `--bind NAME=DEVICE_ID` (repeatable, ordered) starts the session with a
 * named cast instead of a single device. The FIRST bind is the start device — the one the
 * session's tool calls drive until a `switchDevice(name=…)` handover — mirroring the
 * ordered-map semantics of a trail's `config.devices:`. When `--device` is also passed it
 * must name one bind's DEVICE_ID, and that entry becomes the start device. The session
 * attaches to the same per-device MCP session scope (`cli-<startDevice>`) that `step` /
 * `verify` / `tool` reattach to, so follow-up commands with the same `--device` value see
 * the roster.
 *
 * Examples:
 *   trailblaze session start --target myapp --mode trail --device android
 *   trailblaze session start --device ios      (target/mode already configured)
 *   trailblaze session start                   (TRAILBLAZE_DEVICE pinned in this shell)
 *   trailblaze session start --bind seller=emulator-5554 --bind buyer=emulator-5556
 */
@Command(
  name = "start",
  mixinStandardHelpOptions = true,
  description = ["Start a new session with automatic video and log capture"],
)
class SessionStartCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["--target"],
    description = [TARGET_OPTION_DESCRIPTION_SESSION],
  )
  var target: String? = null

  @Option(
    names = ["--mode"],
    description = ["Working mode: trail or blaze. Saved to config for future commands."],
  )
  var mode: String? = null

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION],
  )
  var device: String? = null

  @Option(
    names = ["--bind"],
    paramLabel = "<name=deviceId>",
    split = ",",
    description = [
      "Start a MULTI-DEVICE session: bind each device under a NAME, e.g. " +
        "`--bind seller=emulator-5554 --bind buyer=emulator-5556`. Repeatable or " +
        "comma-separated; the session's cast is exactly the bound devices. The FIRST bind " +
        "is the start device (same ordered semantics as a trail's `config.devices:`); pass " +
        "--device naming one bind's DEVICE_ID to start on that entry instead. Each name must " +
        "bind a DIFFERENT device — two names on one device is refused. " +
        "`switchDevice(name=…)` hands the session between the names. Follow-up commands " +
        "(`step`, `verify`, `session info`, `session stop`) reach the roster with the --device " +
        "value the start prints.",
    ],
  )
  var deviceBinds: List<String> = emptyList()

  @Option(
    names = ["--title"],
    description = ["Title for the session (used as trail name when saving)"],
  )
  var title: String? = null

  @Option(
    names = ["--no-video"],
    description = ["Disable video capture"],
  )
  var noVideo: Boolean = false

  @Option(
    names = ["--no-logs"],
    description = ["Disable device log capture"],
  )
  var noLogs: Boolean = false

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @CommandLine.Mixin
  val headlessOption: HeadlessOption = HeadlessOption()

  override fun call(): Int {
    // Validate --mode early before touching config.
    val normalizedMode = mode?.lowercase()
    if (normalizedMode != null && normalizedMode !in setOf("trail", "blaze")) {
      Console.error("Error: --mode must be 'trail' or 'blaze' (got '$mode').")
      return TrailblazeExitCode.MISUSE.code
    }

    // Parse --bind early so a malformed or repeated entry exits MISUSE before any device
    // or daemon work — same fail-fast contract (and same parser) as `trailblaze run --bind`.
    val parsedDeviceBinds: Map<String, String> = try {
      TrailCommand.parseDeviceBinds(deviceBinds)
    } catch (e: IllegalArgumentException) {
      Console.error("Error: ${e.message}")
      return TrailblazeExitCode.MISUSE.code
    }

    // Resolve the start device.
    //
    // No binds: today's single-device path, unchanged — --device → $TRAILBLAZE_DEVICE →
    // autodetect → MISUSE, via [requireSessionDevice].
    //
    // Binds present: the cast is exactly the bound devices, so the start device comes FROM
    // the binds — the first entry, or the one --device / $TRAILBLAZE_DEVICE names. A device
    // arg matching no bind is MISUSE (the flags contradict each other; picking either side
    // silently would drive a device the user didn't ask for). Autodetect and config
    // fallbacks deliberately do not apply: the binds already name every device.
    val orderedBinds: List<Pair<String, String>>
    val resolvedDevice: String
    if (parsedDeviceBinds.isEmpty()) {
      orderedBinds = emptyList()
      resolvedDevice = when (val r = requireSessionDevice(device, verb = "Session start")) {
        is DeviceResolution.Resolved -> r.deviceSpec
        else -> return r.exitCodeFallback()
      }
    } else {
      when (val r = resolveSessionStartBinds(parsedDeviceBinds, resolveCliDevice(device))) {
        is SessionBindStartResolution.Resolved -> {
          orderedBinds = r.orderedBinds
          resolvedDevice = r.startDeviceSpec
        }
        is SessionBindStartResolution.Misuse -> {
          Console.error("Error: ${r.message}")
          if (device == null && resolveCliDevice(null) != null) {
            Console.error(
              "  hint: TRAILBLAZE_DEVICE=${resolveCliDevice(null)} is your shell pin; " +
                "`unset TRAILBLAZE_DEVICE` to drop it, or pass -d naming one of the bound DEVICE_IDs",
            )
          }
          return TrailblazeExitCode.MISUSE.code
        }
      }
    }

    if (normalizedMode != null) {
      CliConfigHelper.updateConfig { it.copy(cliMode = normalizedMode) }
    }
    val currentConfig = CliConfigHelper.getOrCreateConfig()

    if (!verbose) Console.enableQuietMode()
    val port = CliConfigHelper.resolveEffectiveHttpPort()
    // Session-scoped target: prefer the explicit `--target` flag, fall back
    // to `TRAILBLAZE_TARGET` env var (per-shell pin from `eval $(trailblaze
    // device connect ... --target X)`), else anchor on the daemon-wide
    // default so connectReusable can detect a toolset change and recreate
    // the per-device session when needed. Per-device target overrides live
    // in the daemon's in-memory map (set via setSessionTargetForBoundDevice
    // below) — never written to disk. `--target=clear` is the explicit
    // unset, flag-only (an env pin of `clear` is treated as unset, not as
    // a clear request — see [resolveCliTargetPin]).
    //
    // Shared helper keeps this in lockstep with [cliReusableWithDevice]; both
    // call sites reuse the same payload-shape resolution so a third action
    // command that wants the same semantics doesn't copy the logic a third
    // time.
    val daemonCall = resolveCliTargetDaemonCall(target)
    val targetAppId = daemonCall.pin ?: currentConfig.selectedTargetAppId

    // With binds, attach to the SAME per-device MCP session scope that `step`/`verify`/`tool`
    // reattach to for the start device (see [cliDeviceSessionScope]). The roster lives on the
    // daemon-side MCP session context, so binding it anywhere else would leave follow-up
    // commands a session with no names to switch between. Without binds the scope stays null —
    // byte-for-byte today's single-device behavior.
    val sessionScope = if (orderedBinds.isEmpty()) null else cliDeviceSessionScope(resolvedDevice)

    return runBlocking {
      val client = connectOrStartDaemonReusable(port, targetAppId = targetAppId, sessionScope = sessionScope)
        ?: return@runBlocking TrailblazeExitCode.INFRA_FAILED.code

      client.use {
        // --device is resolved (flag → $TRAILBLAZE_DEVICE), so no config-default or
        // auto-detect fallback. The user tells us the device explicitly on every
        // invocation or via the shell-pinned env var; we honor it directly.
        val deviceError = it.ensureDevice(
          deviceSpec = resolvedDevice,
          webHeadless = headlessOption.resolve(),
        )
        if (deviceError != null) {
          Console.error(deviceError)
          // Eviction runs AFTER the error line so the user reads "what
          // failed" before the supplementary "what was done about it" —
          // see the kdoc on [evictShellPinIfMatches].
          evictShellPinIfMatches(resolvedDevice, deviceError)
          return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
        }
        // Save the platform to config so other commands (e.g., `trail`) that still
        // fall back to cliDevicePlatform when --device is omitted see the most recent
        // value. The session lifecycle no longer falls back to it itself.
        val platformStr = resolvedDevice.split("/", limit = 2)[0]
        if (TrailblazeDevicePlatform.fromString(platformStr) != null) {
          CliConfigHelper.updateConfig { cfg -> cfg.copy(cliDevicePlatform = platformStr.uppercase()) }
        }
        // Session-scope the target on the daemon for the bound device when
        // the user pinned one — either via explicit `--target` or via
        // `TRAILBLAZE_TARGET` in the calling shell. No-op when neither tier
        // supplies a value. `--target=clear` (flag-only) sends an empty
        // string to clear the override.
        //
        // The pin lands on the START DEVICE only. `setSessionTargetForBoundDevice` takes no
        // deviceId — it writes the override for whichever device the MCP session is bound to —
        // so a cast member keeps whatever target the daemon resolves for it (its own override,
        // else the daemon-wide default), and a `switchDevice` handover can therefore land on a
        // different tool set than the start device. Applying the pin cast-wide needs a deviceId
        // on that daemon tool; naming per-bind targets needs a flag that pairs with `--bind`.
        if (daemonCall.payload != null) {
          // Same ordering as cliReusableWithDevice: file-pin clear FIRST so a
          // file write failure doesn't leave the daemon and file diverged.
          if (daemonCall.isClearRequest) {
            clearShellDevicePinTargetIfPossible()
          }
          val setError = it.setSessionTargetForBoundDevice(daemonCall.payload)
          if (setError != null) {
            Console.error(setError)
            // Mirror cliReusableWithDevice's env-source hint so a stale shell
            // pin surfaces with a one-liner recovery rather than a mystery.
            if (target == null && daemonCall.pin != null) {
              Console.error(
                "  hint: TRAILBLAZE_TARGET=${daemonCall.pin} is your shell pin; " +
                  "`unset TRAILBLAZE_TARGET` to drop it, or pass --target=clear",
              )
            }
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
        }

        when (val bindOutcome = bindSessionCast(it, orderedBinds)) {
          is BindCastResult.Failed -> {
            Console.error(bindOutcome.message)
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
          BindCastResult.Bound -> Unit
        }

        val arguments = mutableMapOf<String, Any?>("action" to "START")
        if (title != null) arguments["title"] = title
        if (noVideo) arguments["noVideo"] = true
        if (noLogs) arguments["noLogs"] = true

        val result = it.callTool("session", arguments)
        if (result.isError) {
          // A START that fails after every BIND succeeded strands exactly the claims
          // [bindSessionCast]'s own unwind exists to prevent, so it unwinds the same way.
          Console.error(
            unwindCastReport(
              client = it,
              cast = orderedBinds,
              bound = orderedBinds,
              cause = "Error: ${extractErrorMessage(result.content)}",
            ),
          )
          TrailblazeExitCode.INFRA_FAILED.code
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
              Console.error(
                unwindCastReport(
                  client = it,
                  cast = orderedBinds,
                  bound = orderedBinds,
                  cause = "Error: $error",
                ),
              )
              return@use TrailblazeExitCode.INFRA_FAILED.code
            }
            val msg = json["message"]?.jsonPrimitive?.content
            val sessionId = json["sessionId"]?.jsonPrimitive?.content
            if (sessionId != null) Console.info("Session: trailblaze session info --id $sessionId")
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
          }
          if (sessionScope != null) {
            aliasAndPrintFollowUpDevice(
              client = it,
              port = port,
              startedScope = sessionScope,
              startDeviceSpec = resolvedDevice,
            )
          }
          TrailblazeExitCode.SUCCESS.code
        }
      }
    }
  }
}

/**
 * Makes the started session reachable under the device spelling follow-up commands will use, and
 * prints the spelling that is guaranteed to reach it.
 *
 * [cliDeviceSessionScope] keys on the device string AS TYPED, so a session started from
 * `--bind seller=emulator-5554` lives under `cli-emulator-5554` while a later `step` resolving
 * through this terminal's `device connect` pin opens `cli-android/emulator-5554` — a different
 * session, with no roster. Publishing a second pointer at the same MCP session is what covers
 * both spellings; normalizing the key instead would put a daemon round trip back on every
 * `step` / `tool` / `snapshot` / `ask`, which is why those resolvers return the value as typed.
 *
 * The resolved id comes from a SESSION-ONLY probe: the process-wide device is whatever any
 * terminal selected last, and aliasing on that would point another device's scope at this
 * session. If the alias cannot be written, the as-typed spelling is printed instead of the one
 * that would not work.
 *
 * Runs AFTER `session(action=START)` has already succeeded, so nothing in here may fail the
 * command: a probe that times out or finds a disconnected device would otherwise throw out of a
 * start that worked, telling the user their session did not open when it did. The probe's own
 * error contract only covers `isError` and unparseable content, so the throw is caught here.
 * Cancellation is rethrown rather than degraded — a cancelled CLI is on its way out, and carrying
 * on to print a follow-up hint would be reporting on a session nobody is waiting to hear about.
 */
internal suspend fun aliasAndPrintFollowUpDevice(
  client: CliMcpClient,
  port: Int,
  startedScope: String,
  startDeviceSpec: String,
) {
  val resolvedSpec = try {
    client.getBoundDeviceId(sessionOnly = true)
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }
    ?.toFullyQualifiedDeviceId()
    ?: startDeviceSpec
  val aliased = CliMcpClient.publishSessionFileAlias(
    port = port,
    fromSessionScope = startedScope,
    toSessionScope = cliDeviceSessionScope(resolvedSpec),
  )
  val reachableSpec = if (aliased) resolvedSpec else startDeviceSpec
  Console.info(
    "Follow-ups reach this session with -d $reachableSpec " +
      "(e.g. `trailblaze step -d $reachableSpec \"…\"`)",
  )
}

/** Outcome of [bindSessionCast]: every name bound, or the first failure with its unwind report. */
internal sealed interface BindCastResult {
  /** Every entry bound, start device first. */
  data object Bound : BindCastResult

  /** A bind failed. [message] names it, what was released, and what is still held. */
  data class Failed(val message: String) : BindCastResult
}

/**
 * Binds the session's cast — start device first, then declared order — before `session START`, so
 * the session opens with its full roster and the first bind active.
 *
 * The daemon returns its own `"Error: …"` strings with `isError=false` for validation failures
 * (unknown device, name conflicts), so both shapes count as failure. An older daemon without the
 * BIND action rejects the call outright on the unknown enum value, which lands here too — loud,
 * before any session starts.
 *
 * A failure part-way through unwinds via [releaseBindsAfterStart] rather than returning with the
 * earlier names still bound: BIND takes a device claim keyed to this MCP session scope, so
 * abandoning them would leave devices claimed by a session that was never started, held until
 * something else reuses the scope.
 */
internal suspend fun bindSessionCast(
  client: CliMcpClient,
  orderedBinds: List<Pair<String, String>>,
): BindCastResult {
  val bound = mutableListOf<Pair<String, String>>()
  for ((bindName, bindDeviceId) in orderedBinds) {
    val bindResult = client.callTool(
      "device",
      // BIND matches on the bare instance id (`DeviceManagerToolSet.bindNamedDevice` looks the
      // device up by `instanceId`), so a `platform/`-qualified DEVICE_ID has its qualifier
      // stripped here. Accepted in `--bind` because `--device` accepts it. Because the qualifier
      // does not survive onto the wire, two binds whose instance segments match are refused
      // before this loop even when their platforms differ — see [sameBindTargetOnTheWire].
      mapOf(
        "action" to "BIND",
        "name" to bindName,
        "deviceId" to bindDeviceId.substringAfter('/', missingDelimiterValue = bindDeviceId),
      ),
    )
    if (bindResult.isFailure) {
      return BindCastResult.Failed(
        unwindCastReport(
          client = client,
          cast = orderedBinds,
          bound = bound,
          cause = "Error binding '$bindName' to $bindDeviceId: ${extractErrorMessage(bindResult.content)}",
        ),
      )
    }
    bound += bindName to bindDeviceId
    Console.info("Bound '$bindName' to $bindDeviceId${if (bound.size == 1) " (start device)" else ""}")
  }
  dropNamesOutsideCast(client, orderedBinds.map { it.first })
  return BindCastResult.Bound
}

/**
 * Unbinds any name the session already held that this cast does not name.
 *
 * `--bind` promises the cast is EXACTLY the bound devices, and a `session start --bind` can land
 * on a scope that already has a session: the reused MCP context keeps its previous roster, and
 * neither `ensureDevice` (which takes the reuse path, so it never drops the roster) nor
 * `session START` clears bindings. Left alone, re-running with a changed cast would open a
 * session whose roster is the UNION — extra names `switchDevice` accepts, extra device claims.
 *
 * Runs after the new cast is bound so the leftovers are never the session's last named device,
 * which UNBIND refuses. A failure is reported and not fatal: the cast the user asked for is
 * bound, and a stale extra name is worth a warning rather than a refused start.
 */
private suspend fun dropNamesOutsideCast(client: CliMcpClient, castNames: List<String>) {
  val existing = try {
    rosterDeviceNames(
      extractNamedDeviceRoster(
        client.callTool("device", mapOf("action" to "INFO", "sessionOnly" to true)).content,
      ),
    )
  } catch (_: Exception) {
    return
  }
  for (stale in existing.filter { it !in castNames }) {
    val result = try {
      client.callTool("device", mapOf("action" to "UNBIND", "name" to stale))
    } catch (e: Exception) {
      Console.error("Note: '$stale' was bound by an earlier session on this device and could not be released: ${e.message}")
      continue
    }
    if (result.isFailure) {
      Console.error(
        "Note: '$stale' was bound by an earlier session on this device and could not be " +
          "released: ${extractErrorMessage(result.content)}",
      )
    } else {
      Console.info("Released '$stale', which this cast does not name.")
    }
  }
}

/**
 * Releases a cast whose session never opened, and reports [cause] together with what that left
 * behind — shared by a failed BIND and a failed `session START` after every BIND succeeded, which
 * strand exactly the same claims.
 *
 * [cast] is the cast that was asked for and [bound] the prefix of it that actually bound, so the
 * report can name the start device even when the FIRST bind is what failed — the case where
 * nothing is bound but `ensureDevice` has already connected and claimed that device, and so the
 * case where the recovery line matters most.
 */
internal suspend fun unwindCastReport(
  client: CliMcpClient,
  cast: List<Pair<String, String>>,
  bound: List<Pair<String, String>>,
  cause: String,
): String {
  val unwound = releaseCompanionBinds(client, bound)
  return buildString {
    append(cause)
    if (unwound.released.isNotEmpty()) {
      appendLine()
      append("  Released ${unwound.released.joinToString { "'$it'" }} — no session was started.")
    }
    if (unwound.stillBound.isNotEmpty()) {
      appendLine()
      append(
        "  Could not release ${unwound.stillBound.joinToString { "'$it'" }} — still bound and " +
          "claimed by this CLI session.",
      )
    }
    cast.firstOrNull()?.let { (startName, startDeviceId) ->
      appendLine()
      append(
        "  The start device $startDeviceId (bound as '$startName') stays connected to this " +
          "CLI session — `trailblaze device disconnect -d $startDeviceId` releases it.",
      )
    }
  }
}

/**
 * Drops every reusable-session pointer that named the session just ended.
 *
 * The scope actually used is cleared outright. A bound session is ALSO reachable through the
 * scopes of its cast — the alias `session start` published for the start device's other spelling,
 * and each companion's own scope — and each of those is cleared only if it still names this
 * session, because a different terminal may have opened its own session on that device since.
 */
internal fun clearSessionPointersFor(port: Int, lifecycle: SessionLifecycleClient) {
  val endedSessionId = lifecycle.client.sessionId
  CliMcpClient.clearSession(port, lifecycle.sessionScope)
  lifecycle.rosterDevices
    .map { cliDeviceSessionScope(it) }
    .distinct()
    .filter { it != lifecycle.sessionScope }
    .forEach { CliMcpClient.clearSessionIfPointsTo(port, it, endedSessionId) }
}

/** What [releaseCompanionBinds] managed to give back, and what the caller is still holding. */
internal data class CompanionBindRelease(
  val released: List<String>,
  val stillBound: List<String>,
)

/**
 * Unbinds every name bound after the start device, most recent first.
 *
 * The start device is deliberately kept: the daemon refuses to unbind a session's last named
 * device (`session(action=STOP)` is how a session ends, not an empty roster), and leaving it is
 * the same state an ordinary `session start -d <device>` reaches — connected and claimed by this
 * CLI scope, released by `trailblaze device disconnect`. Attempting it anyway would only print a
 * refusal the caller can do nothing with.
 *
 * A refused or unreachable UNBIND is reported as [CompanionBindRelease.stillBound] rather than
 * dropped: the caller is being told which devices it no longer has to think about, so silently
 * counting a release that did not happen is the one answer that misleads. A transport failure is
 * caught here for the same reason — it must not replace the bind error that started the unwind.
 */
internal suspend fun releaseCompanionBinds(
  client: CliMcpClient,
  bound: List<Pair<String, String>>,
): CompanionBindRelease {
  val released = mutableListOf<String>()
  val stillBound = mutableListOf<String>()
  for ((name, _) in bound.drop(1).asReversed()) {
    val ok = try {
      !client.callTool("device", mapOf("action" to "UNBIND", "name" to name)).isFailure
    } catch (_: Exception) {
      false
    }
    if (ok) released += name else stillBound += name
  }
  return CompanionBindRelease(released = released, stillBound = stillBound)
}

/**
 * Outcome of [resolveSessionStartBinds]: the bind order to send to the daemon (start device
 * first) plus the device spec the start device resolves/binds under, or a MISUSE message.
 */
internal sealed interface SessionBindStartResolution {
  data class Resolved(
    /** Every `--bind NAME=DEVICE_ID` entry, start device first, then declared order. */
    val orderedBinds: List<Pair<String, String>>,
    /** What `ensureDevice` connects and [cliDeviceSessionScope] keys on — the `--device` arg as typed when given, else the first bind's DEVICE_ID. */
    val startDeviceSpec: String,
  ) : SessionBindStartResolution

  data class Misuse(val message: String) : SessionBindStartResolution
}

/**
 * Picks the start device for a `session start --bind …` invocation.
 *
 * The FIRST bind is the start device — the same ordered-map semantics as a trail's
 * `config.devices:` and [xyz.block.trailblaze.toolcalls.SessionDeviceBindings]' start-first
 * map. A non-null [deviceArg] (the resolved `--device` flag / `TRAILBLAZE_DEVICE` pin) must
 * name one bind's DEVICE_ID — matched by [sameBoundDevice], so a `platform/` qualifier on
 * either side is a qualifier rather than part of the identity — and that entry becomes the
 * start device. An arg matching no bind is MISUSE: the cast is exactly the bound devices, so
 * the flags contradict each other and picking either side would silently drive a device the
 * user didn't ask for.
 */
internal fun resolveSessionStartBinds(
  binds: Map<String, String>,
  deviceArg: String?,
): SessionBindStartResolution {
  val entries = binds.entries.map { it.key to it.value }
  if (entries.isEmpty()) {
    return SessionBindStartResolution.Misuse("--bind names no devices, so there is no cast to start.")
  }
  entries.firstOrNull { (_, boundId) -> boundId.isBlank() }?.let { (name, _) ->
    // The shared parser leaves a blank value to "the resolver", which `run --bind` has and this
    // command does not: unchecked it becomes ensureDevice("") and a `cli-` session scope.
    return SessionBindStartResolution.Misuse(
      "--bind $name= names no DEVICE_ID. Every bound name needs a device, e.g. " +
        "`--bind $name=emulator-5554` (`trailblaze device list` shows the ids).",
    )
  }
  duplicateBoundDevice(entries)?.let { (first, second) ->
    val qualifiersDiffer = first.second.contains('/') &&
      second.second.contains('/') &&
      !first.second.substringBefore('/').equals(second.second.substringBefore('/'), ignoreCase = true)
    return SessionBindStartResolution.Misuse(
      "--bind maps '${first.first}' and '${second.first}' onto the same device " +
        "(${first.second}${if (second.second != first.second) " and ${second.second}" else ""}). " +
        "One device holds one name: the daemon reads the second bind as RENAMING the first, so " +
        "the session would open with a one-device roster and no start device under the name you " +
        "gave it. Bind each name to a different DEVICE_ID." +
        if (qualifiersDiffer) {
          " A differing `platform/` qualifier does not separate them: BIND carries only the " +
            "instance id, so both specs resolve to one device on the daemon."
        } else {
          ""
        },
    )
  }
  if (deviceArg == null) {
    return SessionBindStartResolution.Resolved(
      orderedBinds = entries,
      startDeviceSpec = entries.first().second,
    )
  }
  val start = entries.firstOrNull { (_, boundId) ->
    sameBoundDevice(boundId, deviceArg)
  } ?: return SessionBindStartResolution.Misuse(
    "--device $deviceArg matches none of the bound DEVICE_IDs " +
      "(${entries.joinToString { (name, id) -> "$name=$id" }}). With --bind the session's " +
      "cast is exactly the bound devices — pass -d naming one bind's DEVICE_ID to pick the " +
      "start device, or omit -d to start on the first bind.",
  )
  return SessionBindStartResolution.Resolved(
    orderedBinds = listOf(start) + entries.filter { it !== start },
    startDeviceSpec = deviceArg,
  )
}

/**
 * The first pair of `--bind` entries naming the same device, or null when every entry is distinct.
 *
 * The check lives here rather than in the shared [TrailCommand.parseDeviceBinds] because the two
 * commands fail differently: `run --bind` names companions of a trail that declares its own
 * `config.devices:`, and `MultiDeviceConfigurationResolver` already rejects one device under two
 * names there, phrased against that declaration. `session start` has no trail to report against —
 * the binds ARE the cast — so the refusal belongs where the cast is assembled.
 *
 * Rejecting matters because the daemon does not: `DeviceManagerToolSet.bindNamedDevice` reads a
 * BIND naming an already-bound device as a RENAME and reports success, so the CLI would print two
 * bound names over a roster holding one device — and the start device's name would be the one
 * that vanished. `SessionDeviceBindings` refuses the same roster at construction on the trail path.
 *
 * Compared by [sameBindTargetOnTheWire], not [sameBoundDevice]: what collides is what BIND sends.
 */
internal fun duplicateBoundDevice(
  entries: List<Pair<String, String>>,
): Pair<Pair<String, String>, Pair<String, String>>? {
  entries.forEachIndexed { index, entry ->
    entries.subList(0, index)
      .firstOrNull { earlier -> sameBindTargetOnTheWire(earlier.second, entry.second) }
      ?.let { earlier -> return earlier to entry }
  }
  return null
}

/**
 * True when two `--bind` DEVICE_IDs reach the same device once [bindSessionCast] puts them on the
 * wire: instance segments equal, case-insensitively, whatever the `platform/` qualifiers say.
 *
 * Distinct from [sameBoundDevice] on purpose. That predicate answers "does this `--device` arg name
 * this bind?", where two specs disagreeing on platform are genuinely different devices. This one
 * answers "will these two binds hit the same daemon lookup?", and BIND carries no platform —
 * [bindSessionCast] strips it because `DeviceManagerToolSet.bindNamedDevice` matches on
 * `instanceId` alone. So `android/foo` and `ios/foo` are two devices to the CLI and one device to
 * the daemon, which reads the second BIND as a rename and reports success.
 */
internal fun sameBindTargetOnTheWire(first: String, second: String): Boolean =
  first.substringAfter('/', missingDelimiterValue = first)
    .equals(second.substringAfter('/', missingDelimiterValue = second), ignoreCase = true)

/**
 * True when two `--bind` DEVICE_IDs name the same device.
 *
 * Compared on the instance segment, case-insensitively, treating a `platform/` prefix on either
 * side as a qualifier rather than part of the identity — the same rule [resolveSessionStartBinds]
 * uses to match `--device android/emulator-5554` against a bare `seller=emulator-5554`. Two
 * qualified specs must still agree on the platform, so `android/foo` and `ios/foo` stay distinct.
 */
internal fun sameBoundDevice(first: String, second: String): Boolean {
  val firstInstance = first.substringAfter('/', missingDelimiterValue = first)
  val secondInstance = second.substringAfter('/', missingDelimiterValue = second)
  if (!firstInstance.equals(secondInstance, ignoreCase = true)) return false
  val firstPlatform = first.substringBefore('/', missingDelimiterValue = "")
  val secondPlatform = second.substringBefore('/', missingDelimiterValue = "")
  return firstPlatform.isEmpty() ||
    secondPlatform.isEmpty() ||
    firstPlatform.equals(secondPlatform, ignoreCase = true)
}

@Command(
  name = "stop",
  mixinStandardHelpOptions = true,
  description = ["Stop the current session and finalize captures"],
)
class SessionStopCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION],
  )
  var device: String? = null

  @Option(
    names = ["--save"],
    description = ["Save session as a trail before stopping"],
  )
  var save: Boolean = false

  @Option(
    names = ["--title", "-t"],
    description = ["Trail title when saving (overrides session title)"],
  )
  var title: String? = null

  override fun call(): Int {
    // Resolve --device → $TRAILBLAZE_DEVICE → null. `stop` is per-device, so we
    // need an explicit target — either passed on this invocation or pinned in
    // the shell. See DeviceDisconnectCommand for the multi-terminal safety note.
    val resolvedDevice = when (val r = requireSessionDevice(device, verb = "Session stop")) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return r.exitCodeFallback()
    }

    val port = CliConfigHelper.resolveEffectiveHttpPort()
    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      CliMcpClient.clearSession(port)
      return TrailblazeExitCode.SUCCESS.code
    }

    return runBlocking {
      // Act through the session that owns this device's lifecycle — the per-device scope for a
      // `session start --bind` cast, the unscoped session otherwise. See
      // [openSessionLifecycleClient]: stopping from the wrong one leaves capture unfinalized.
      val lifecycle = try {
        openSessionLifecycleClient(port, resolvedDevice)
      } catch (_: Exception) {
        Console.log("No active session.")
        CliMcpClient.clearSession(port)
        return@runBlocking TrailblazeExitCode.SUCCESS.code
      }

      var exitCode = TrailblazeExitCode.SUCCESS.code
      lifecycle.client.use {
        val extraArgs = buildMap<String, Any?> {
          if (save) put("save", true)
          if (title != null) put("title", title)
        }
        when (
          val outcome = stopBoundSessionIfMatches(
            client = it,
            expectedDevice = resolvedDevice,
            extraStopArgs = extraArgs,
            sessionDevices = lifecycle.rosterDevices,
          )
        ) {
          is StopBoundSessionResult.NoActiveSession -> {
            Console.log("No active session for device $resolvedDevice.")
            // Fall through to clearSession + SUCCESS at the bottom.
          }
          is StopBoundSessionResult.DeviceMismatch -> {
            Console.error(
              "No active session for device $resolvedDevice — the daemon's current session is " +
                "bound to ${outcome.boundDevice.toFullyQualifiedDeviceId()}. Pass --device " +
                "${outcome.boundDevice.toFullyQualifiedDeviceId()} if you meant to stop that one.",
            )
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
          is StopBoundSessionResult.StopFailed -> {
            Console.error("Error: ${extractErrorMessage(outcome.error)}")
            exitCode = TrailblazeExitCode.INFRA_FAILED.code
          }
          is StopBoundSessionResult.Stopped -> {
            outcome.message?.let { msg -> Console.info(msg) }
          }
        }
      }

      clearSessionPointersFor(port, lifecycle)
      exitCode
    }
  }
}

@Command(
  name = "list",
  mixinStandardHelpOptions = true,
  description = ["List recent sessions"],
)
class SessionListCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["--limit", "-n"],
    description = ["Maximum number of sessions to show (default: 10)"],
  )
  var limit: Int = 10

  @Option(
    names = ["--all", "-a"],
    description = ["Show all sessions in a flat chronological list"],
  )
  var all: Boolean = false

  override fun call(): Int {
    if (limit < 0) {
      Console.error("Error: --limit must be non-negative (got $limit).")
      return TrailblazeExitCode.MISUSE.code
    }

    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val fetchLimit = if (all) limit else limit + 20 // fetch extra so we have enough completed
        val result = it.callTool("session", mapOf("action" to "LIST", "limit" to fetchLimit))
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val sessions = json["sessions"] as? JsonArray
          if (sessions == null || sessions.isEmpty()) {
            Console.info("No sessions found.")
            return@use TrailblazeExitCode.SUCCESS.code
          }

          if (all) {
            printSessionTable(sessions)
          } else {
            printGroupedSessions(sessions, limit)
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }

  private fun printSessionTable(sessions: JsonArray) {
    Console.info("%-40s  %-20s  %-20s  %s".format("ID", "STATUS", "STARTED", "TITLE"))
    Console.info("-".repeat(100))
    for (entry in sessions) {
      printSessionRow(entry.jsonObject)
    }
  }

  private fun printGroupedSessions(
    sessions: JsonArray,
    recentLimit: Int,
  ) {
    val inProgress = sessions.filter {
      val s = it.jsonObject["status"]?.jsonPrimitive?.content ?: ""
      s == "In Progress"
    }
    val completed = sessions.filter {
      val s = it.jsonObject["status"]?.jsonPrimitive?.content ?: ""
      s != "In Progress"
    }

    if (inProgress.isNotEmpty()) {
      Console.info("In Progress (${inProgress.size})")
      Console.info("-".repeat(100))
      for (entry in inProgress) {
        printSessionRow(entry.jsonObject)
      }
      if (completed.isNotEmpty()) Console.info("")
    }

    if (completed.isNotEmpty()) {
      Console.info("Recent (${completed.size.coerceAtMost(recentLimit)} of ${completed.size})")
      Console.info("-".repeat(100))
      for (entry in completed.take(recentLimit)) {
        printSessionRow(entry.jsonObject)
      }
    }

    if (inProgress.isEmpty() && completed.isEmpty()) {
      Console.info("No sessions found.")
    }
  }

  private fun printSessionRow(obj: JsonObject) {
    val id = obj["id"]?.jsonPrimitive?.content ?: "?"
    val status = obj["status"]?.jsonPrimitive?.content ?: "?"
    val startedAt = obj["startedAt"]?.jsonPrimitive?.content ?: ""
    val title = obj["title"]?.jsonPrimitive?.content ?: ""
    Console.info("  %-38s  %-20s  %-20s  %s".format(id, status, startedAt, title))
  }
}

@Command(
  name = "artifacts",
  mixinStandardHelpOptions = true,
  description = ["List artifacts in a session"],
)
class SessionArtifactsCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID (positional form of --id, defaults to current session). " +
        "Mutually exclusive with --id.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session). Equivalent to the positional form."],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "ARTIFACTS")
        if (effectiveId != null) arguments["id"] = effectiveId
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
          val path = json["path"]?.jsonPrimitive?.content
          val artifacts = json["artifacts"] as? JsonArray
          if (path != null) Console.info("Session directory: $path")
          if (artifacts != null && artifacts.isNotEmpty()) {
            Console.info("")
            Console.info("%-30s  %-12s  %s".format("NAME", "TYPE", "SIZE"))
            Console.info(ITEM_DIVIDER)
            for (entry in artifacts) {
              val obj = entry.jsonObject
              val name = obj["name"]?.jsonPrimitive?.content ?: "?"
              val type = obj["type"]?.jsonPrimitive?.content ?: "?"
              val size = obj["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull()
              val sizeStr = if (size != null) "${size / 1024}KB" else "?"
              Console.info("%-30s  %-12s  %s".format(name, type, sizeStr))
            }
          } else {
            Console.info("No artifacts found.")
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

@Command(
  name = "delete",
  mixinStandardHelpOptions = true,
  description = ["Delete a session's logs and artifacts"],
)
class SessionDeleteCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID to delete (positional form of --id, supports prefix matching). " +
        "Mutually exclusive with --id; one of the two is required.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID to delete (supports prefix matching). Equivalent to the positional form."],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id ?: run {
      Console.error("Missing required <session-id>. Pass it positionally or via --id.")
      return TrailblazeExitCode.MISUSE.code
    }
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val result = it.callTool("session", mapOf("action" to "DELETE", "id" to effectiveId))
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
          val msg = json["message"]?.jsonPrimitive?.content
          if (msg != null) Console.info(msg)
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

@Command(
  name = "end",
  mixinStandardHelpOptions = true,
  description = ["End the CLI session and release the device (deprecated: use 'stop' instead)"],
)
class SessionEndCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["-d", "--device"],
    description = [DEVICE_OPTION_DESCRIPTION],
  )
  var device: String? = null

  @Option(
    names = ["--name", "-n"],
    description = ["Save the recording as a trail before ending"]
  )
  var name: String? = null

  override fun call(): Int {
    Console.error("Deprecated: use 'trailblaze session stop' instead.")

    // Resolve --device → $TRAILBLAZE_DEVICE → null. `end` is per-device with the same
    // multi-terminal safety contract as `session stop` — require an explicit target.
    val resolvedDevice = when (val r = requireSessionDevice(device, verb = "Session end")) {
      is DeviceResolution.Resolved -> r.deviceSpec
      else -> return r.exitCodeFallback()
    }

    // Find the root command to get the port
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      CliMcpClient.clearSession(port)
      return TrailblazeExitCode.SUCCESS.code
    }

    return runBlocking {
      // Same lifecycle-scope contract as `session stop` — see [openSessionLifecycleClient].
      val lifecycle = try {
        openSessionLifecycleClient(port, resolvedDevice)
      } catch (e: Exception) {
        Console.log("No active session.")
        CliMcpClient.clearSession(port)
        return@runBlocking TrailblazeExitCode.SUCCESS.code
      }

      lifecycle.client.use {
        // Use --device as the session lookup key (same contract as `session stop`).
        // `return@runBlocking` from inside `use` is fine — `close()` runs in the
        // surrounding finally block.
        val boundDevice = it.getBoundDeviceId()
        when {
          boundDevice == null -> {
            Console.log("No active session for device $resolvedDevice.")
            CliMcpClient.clearSession(port, lifecycle.sessionScope)
            return@runBlocking TrailblazeExitCode.SUCCESS.code
          }
          !sessionOwnsDevice(resolvedDevice, boundDevice, lifecycle.rosterDevices) -> {
            Console.error(
              "No active session for device $resolvedDevice — the daemon's current session is " +
                "bound to ${boundDevice.toFullyQualifiedDeviceId()}. Pass --device " +
                "${boundDevice.toFullyQualifiedDeviceId()} if you " +
                "meant to end that one.",
            )
            return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
          }
          // else: match — proceed with end.
        }
        if (name != null) {
          val saveResult = it.callTool("trail", mapOf("action" to "SAVE", "name" to name!!))
          if (saveResult.isError) {
            Console.error("Error saving trail: ${extractErrorMessage(saveResult.content)}")
          } else {
            Console.info("Trail saved: $name")
          }
        }

        // End the session recording (deprecated trail tool uses END action)
        it.callTool("trail", mapOf("action" to "END"))
      }

      clearSessionPointersFor(port, lifecycle)
      Console.log("Session ended.")
      TrailblazeExitCode.SUCCESS.code
    }
  }
}

@Command(
  name = "save",
  mixinStandardHelpOptions = true,
  description = ["Write the recorded steps to a *.trail.yaml file you can replay later (does not end the session)"],
)
class SessionSaveCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Option(
    names = ["--title", "-t"],
    description = ["Title for the saved trail (uses session title if not specified)"],
  )
  var title: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID to save (defaults to current session, supports prefix matching)"],
  )
  var id: String? = null

  @Option(
    names = ["-d", "--device"],
    description = [
      "Save the CLI session pinned to this device — pass the same value given to " +
        "`session start -d`. Required to save a multi-device session started with " +
        "`session start --bind`: its named-device roster lives on that per-device session, and " +
        "the save synthesizes the trail's `config.devices:` cast from it.",
    ],
  )
  var device: String? = null

  @Option(
    names = ["--configuration"],
    paramLabel = "<name>",
    description = [
      "Name for the multi-device configuration saved from the session's named-device roster " +
        "(from `session start --bind`). Defaults to the bound names joined with '-', e.g. a " +
        "seller,buyer roster saves as `seller-buyer`. The name is a recommendation — rename it " +
        "in the saved file freely. It becomes a YAML key, so it must start with a letter or " +
        "digit and hold only letters, digits, '-', '_' and '.'. Only meaningful for a roster " +
        "session; on a session that bound a trail-declared configuration it may only restate " +
        "that configuration's name.",
    ],
  )
  var configuration: String? = null

  @Option(
    names = ["--name", "-n"],
    hidden = true,
    description = ["Deprecated: use --title instead"],
  )
  var name: String? = null

  override fun call(): Int {
    if (name != null && title == null) {
      Console.error("Deprecated: --name is renamed to --title.")
    }
    if (configuration?.isBlank() == true) {
      Console.error("Error: --configuration must not be blank.")
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveTitle = title ?: name
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      reportDaemonUnreachable()
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    return runBlocking {
      // An explicit --device saves through the MCP session that OWNS that device's lifecycle — the
      // per-device scope when it holds a named roster (where `session start --bind` put both the
      // recording and the cast to synthesize), the unscoped session otherwise. Same resolution as
      // `session stop` / `session end`, and read-only: it never mints a session to save, which
      // would write a step-less trail and report a pass. Without --device the unscoped session is
      // saved, unchanged.
      val lifecycle = try {
        if (device != null) {
          openSessionLifecycleClient(port, device!!)
        } else {
          SessionLifecycleClient(CliMcpClient.connectReusable(port), null)
        }
      } catch (e: Exception) {
        Console.error("Error: No active session. ${e.message}")
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      lifecycle.client.use {
        // An older daemon decodes tool arguments leniently, so one that predates the SAVE action's
        // `configuration` argument drops it silently and saves without the multi-device cast — a
        // wrong file reported as a pass. Keyed on the ROSTER as well as the flag, because the
        // daemon synthesizes a cast for any roster session and the configuration name DEFAULTS:
        // gating only on `--configuration` would let the common `session save -d seller` land that
        // same cast-less trail. Same INFRA_FAILED shape as `trailblaze run --bind`'s check, but
        // after the connect, which is what can see the roster.
        sessionSaveConfigurationRejection(
          configurationRequested = configuration != null,
          rosterPresent = lifecycle.holdsNamedRoster(),
          daemonCapabilities = { DaemonClient(port = port).use { d -> d.getStatusBlocking()?.capabilities } },
        )?.let { rejection ->
          Console.error("Error: Session save failed — $rejection.")
          Console.error(
            "  hint: restart the daemon so it picks up this build (`trailblaze --stop`, then re-run)",
          )
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }

        // A --device that reached a session bound to some OTHER device saves that session's
        // recording under this command's title — the silent-wrong-file case, so refuse. Same
        // ownership rule as `session stop`, including a roster whose ACTIVE device moved on a
        // `switchDevice` handover. Skipped for `--id`, which names the session to save outright.
        val boundDevice = if (device != null && id == null) it.getBoundDeviceId() else null
        if (device != null && id == null && boundDevice != null &&
          !sessionOwnsDevice(device!!, boundDevice, lifecycle.rosterDevices)
        ) {
          Console.error(
            "No active session for device ${device!!} — the session reached is bound to " +
              "${boundDevice.toFullyQualifiedDeviceId()}. Pass --device " +
              "${boundDevice.toFullyQualifiedDeviceId()} if you meant to save that one.",
          )
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }

        val arguments = mutableMapOf<String, Any?>("action" to "SAVE")
        if (effectiveTitle != null) arguments["title"] = effectiveTitle
        if (id != null) arguments["id"] = id
        if (configuration != null) arguments["configuration"] = configuration
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error saving trail: ${extractErrorMessage(result.content)}")
          TrailblazeExitCode.INFRA_FAILED.code
        } else {
          try {
            val json = Json.parseToJsonElement(result.content).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
              Console.error("Error: $error")
              return@use TrailblazeExitCode.INFRA_FAILED.code
            }
            val msg = json["message"]?.jsonPrimitive?.content
            if (msg != null) Console.info(msg)
          } catch (_: Exception) {
            Console.info(result.content)
          }
          TrailblazeExitCode.SUCCESS.code
        }
      }
    }
  }

  companion object {
    /**
     * Refusal reason when a multi-device save would be delegated to a daemon build that predates the
     * SAVE action's `configuration` argument (an MCP tool decodes arguments leniently, so the old
     * daemon drops it silently and saves a cast-less trail that looks like a pass).
     *
     * Two ways in, both producing that same wrong file: an explicit `--configuration`, and a session
     * that holds a named-device ROSTER, whose cast the daemon synthesizes under a DEFAULTED name
     * with no flag involved. Gating only the flag would leave the common case unguarded.
     *
     * Returns `null` to proceed: nothing capability-gated is in play, the daemon couldn't be asked
     * (existing flows already handle an unreachable daemon), or the capability is advertised.
     */
    internal fun sessionSaveConfigurationRejection(
      configurationRequested: Boolean,
      rosterPresent: Boolean,
      daemonCapabilities: () -> Set<String>?,
    ): String? {
      if (!configurationRequested && !rosterPresent) return null
      val capabilities = daemonCapabilities() ?: return null
      if (CliDaemonCapabilities.SESSION_SAVE_CONFIGURATION in capabilities) return null
      return "the running daemon predates roster-based session save, so it would " +
        if (configurationRequested) {
          "ignore --configuration and save the trail without its multi-device cast"
        } else {
          "save this session's named devices as a single-device trail, without the " +
            "`config.devices:` cast a replay needs"
        }
    }
  }
}

@Command(
  name = "recording",
  mixinStandardHelpOptions = true,
  description = ["Output the recording YAML for a session"],
)
class SessionRecordingCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID (positional form of --id, defaults to current session, supports " +
        "prefix matching). Mutually exclusive with --id.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = [
      "Session ID (defaults to current session, supports prefix matching). " +
        "Equivalent to the positional form.",
    ],
  )
  var id: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      reportDaemonUnreachable()
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    return runBlocking {
      val client = try {
        CliMcpClient.connectReusable(port)
      } catch (_: Exception) {
        reportDaemonUnreachable()
        return@runBlocking TrailblazeExitCode.INFRA_FAILED.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "RECORDING")
        if (effectiveId != null) arguments["id"] = effectiveId
        val result = it.callTool("session", arguments)
        if (result.isError) {
          Console.error("Error: ${extractErrorMessage(result.content)}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val error = json["error"]?.jsonPrimitive?.content
          if (!error.isNullOrBlank()) {
            Console.error("Error: $error")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
          val yaml = json["yaml"]?.jsonPrimitive?.content
          if (yaml != null) {
            // Print YAML directly to stdout for piping/redirection
            println(yaml)
          } else {
            Console.error("Error: No recording YAML returned.")
            return@use TrailblazeExitCode.INFRA_FAILED.code
          }
        } catch (_: Exception) {
          Console.info(result.content)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

@Command(
  name = "info",
  mixinStandardHelpOptions = true,
  description = ["Show information about a session"],
)
class SessionInfoCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: SessionCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = [
      "Session ID (positional form of --id, defaults to current session). " +
        "Mutually exclusive with --id.",
    ],
  )
  var positionalId: String? = null

  @Option(
    names = ["--id"],
    description = ["Session ID (defaults to current session). Equivalent to the positional form."],
  )
  var id: String? = null

  @Option(
    names = ["-d", "--device"],
    description = [
      "Inspect the CLI session pinned to this device — pass the same value given to " +
        "`session start -d` / `step -d`. Adds the session's named-device roster (from " +
        "`session start --bind`) and its ACTIVE device to the output. The roster describes " +
        "the devices bound right now, so it is omitted when a session id is also given.",
    ],
  )
  var device: String? = null

  override fun call(): Int {
    if (positionalId != null && id != null) {
      Console.error(SESSION_ID_CONFLICT_MESSAGE)
      return TrailblazeExitCode.MISUSE.code
    }
    val effectiveId = positionalId ?: id
    val port = CliConfigHelper.resolveEffectiveHttpPort()

    if (!DaemonClient(port = port).use { it.isRunningBlocking() }) {
      Console.log("No active session (daemon not running).")
      return TrailblazeExitCode.SUCCESS.code
    }

    return runBlocking {
      val client = openSessionInfoClient(port, device)
      if (client == null) {
        Console.log("No active session.")
        return@runBlocking TrailblazeExitCode.SUCCESS.code
      }

      client.use {
        val arguments = mutableMapOf<String, Any?>("action" to "INFO")
        if (effectiveId != null) arguments["id"] = effectiveId

        val result = it.callTool("session", arguments)

        // Check for "no active session" — not an error, just informational
        val infoError = try {
          Json.parseToJsonElement(result.content).jsonObject["error"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
        if (infoError != null) {
          Console.info(infoError)
          return@use TrailblazeExitCode.SUCCESS.code
        }

        if (result.isError) {
          Console.error("Error: ${result.content}")
          return@use TrailblazeExitCode.INFRA_FAILED.code
        }
        try {
          val json = Json.parseToJsonElement(result.content).jsonObject
          val sessionId = json["sessionId"]?.jsonPrimitive?.content
          val sessionTitle = json["title"]?.jsonPrimitive?.content
          val status = json["status"]?.jsonPrimitive?.content
          val device = json["device"]?.jsonPrimitive?.content
          val platform = json["platform"]?.jsonPrimitive?.content
          val path = json["path"]?.jsonPrimitive?.content

          Console.info("Session:")
          if (sessionId != null) Console.info("  ID:       $sessionId")
          if (sessionTitle != null) Console.info("  Title:    $sessionTitle")
          if (status != null) Console.info("  Status:   $status")
          if (device != null && platform != null) Console.info("  Device:   $device ($platform)")
          else if (device != null) Console.info("  Device:   $device")
          if (path != null) Console.info("  Path:     file://$path")
        } catch (_: Exception) {
          Console.info(result.content)
        }

        when (
          val roster = liveDeviceRoster(
            requestedDevice = this@SessionInfoCommand.device,
            requestedSessionId = effectiveId,
            fetchDeviceInfo = {
              // `connectReusable` already read this exact block to verify the session it
              // reattached to, so reuse it rather than paying the round trip twice.
              it.reusedSessionProbeContent
                ?.let { probe -> CliMcpClient.ToolResult(probe) }
                ?: it.callTool("device", mapOf("action" to "INFO", "sessionOnly" to true))
            },
          )
        ) {
          LiveDeviceRoster.NotRequested -> Unit
          // Same stream as the roster it stands in for, so a redirected `session info` keeps the
          // whole Devices block together.
          is LiveDeviceRoster.Unavailable ->
            Console.info("  Devices:  unavailable — ${roster.reason}")
          is LiveDeviceRoster.Bound -> {
            Console.info("  Devices:")
            roster.lines.forEach { line -> Console.info("  $line") }
          }
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }
}

/**
 * Opens the MCP session `session info` should read, or null when there is none.
 *
 * An explicit [deviceArg] selects the per-device MCP session scope (the one `session start --bind`
 * / `step` attach to) — that scope's daemon-side context is where a named-device roster lives.
 * Without the flag, today's unscoped session is inspected, unchanged.
 *
 * A device with no scope pointer has no scoped session to inspect, so it reads the unscoped one:
 * scoping anyway would make `connectReusable` MINT a session just to look at it, then report the
 * empty session it had just created as a roster it could not read.
 *
 * A pointer can also outlive the session it named — the daemon restarts, or a `session end` cannot
 * reap it. A scoped read that fails says nothing about the UNSCOPED session, which is where a plain
 * `session start -d <device>` put its session, so the read `session info` would have made with no
 * `-d` runs instead. Without that fallback a stale sibling pointer reports "no active session" over
 * a live one.
 *
 * Every read an explicit [deviceArg] makes is non-creating, including that unscoped fallback. A
 * live session is still reattached to (the reuse path returns before the create), but a miss must
 * answer "none" rather than mint an unrelated session and persist its id — an inspection that
 * leaves a session behind has changed what it was asked to describe. Without [deviceArg] the
 * create-on-miss behavior of plain `session info` is unchanged.
 */
internal suspend fun openSessionInfoClient(port: Int, deviceArg: String?): CliMcpClient? {
  val sessionScope = deviceArg
    ?.let { cliDeviceSessionScope(it) }
    ?.takeIf { CliMcpClient.sessionFile(port, it).exists() }
  val createOnMiss = deviceArg == null
  val scoped = connectReusableOrNull(port, sessionScope = sessionScope, createIfMissing = createOnMiss)
  if (scoped != null || sessionScope == null) return scoped
  return connectReusableOrNull(port, sessionScope = null, createIfMissing = createOnMiss)
}

/** What `session info` appends for the session's live named-device roster. */
internal sealed interface LiveDeviceRoster {
  /** No roster block: `--device` was absent, a session id was given, or nothing is bound. */
  data object NotRequested : LiveDeviceRoster

  /** The bound names, in roster order, with the ACTIVE marker the daemon supplied. */
  data class Bound(val lines: List<String>) : LiveDeviceRoster

  /** The roster was asked for and the daemon could not answer. [reason] is its message. */
  data class Unavailable(val reason: String) : LiveDeviceRoster
}

/**
 * Decides what `session info` prints under a session's metadata for the live device roster.
 *
 * Two rules, both about not passing off one session's state as another's:
 *
 *  - Only for `--device` with no session id. The metadata above comes from the LOGS and can be any
 *    past session on this daemon, while the roster is the devices bound RIGHT NOW — rendering them
 *    together attributes a live cast to a session that may never have had one. `--device` still
 *    chooses which MCP session answers, so the combination stays legal; only the live block goes.
 *  - A failed lookup reports instead of printing nothing. "This session has no named devices" and
 *    "we could not find out" are different answers, and silently collapsing them tells a user who
 *    explicitly asked for the roster that their multi-device session is single-device.
 */
internal suspend fun liveDeviceRoster(
  requestedDevice: String?,
  requestedSessionId: String?,
  fetchDeviceInfo: suspend () -> CliMcpClient.ToolResult,
): LiveDeviceRoster {
  if (requestedDevice == null || requestedSessionId != null) return LiveDeviceRoster.NotRequested
  // A roster lookup is an addition to output that has already been printed, so a transport
  // failure degrades this block rather than turning an informational command into an error.
  val result = try {
    fetchDeviceInfo()
  } catch (e: Exception) {
    return LiveDeviceRoster.Unavailable(e.message ?: e::class.simpleName ?: "lookup failed")
  }
  if (result.isFailure) {
    return LiveDeviceRoster.Unavailable(extractErrorMessage(result.content))
  }
  val lines = extractNamedDeviceRoster(result.content)
  return if (lines.isEmpty()) LiveDeviceRoster.NotRequested else LiveDeviceRoster.Bound(lines)
}

/**
 * The daemon's roster header in `device(action=INFO)` output.
 *
 * Taken from the daemon's own constant, so rewording that block is a compile-time change here
 * rather than a silent empty roster — which would also reroute `session stop` to the wrong MCP
 * session. See `DeviceManagerToolSet.describeNamedBindings`.
 */
private val ROSTER_HEADER = DeviceManagerToolSet.NAMED_DEVICES_HEADER

/**
 * Extracts the roster lines (`  - name: platform/id … [ACTIVE]`) from the `device(action=INFO)`
 * text block, or empty when the session has no named bindings (single-device sessions omit the
 * block entirely). Tolerant of everything around the block — header, driver status, tool
 * summary — by keying on the roster header line and its `  - ` item prefix.
 */
internal fun extractNamedDeviceRoster(deviceInfoContent: String): List<String> {
  val lines = deviceInfoContent.lines()
  val headerIndex = lines.indexOfFirst { it.trim() == ROSTER_HEADER }
  if (headerIndex < 0) return emptyList()
  return lines.drop(headerIndex + 1).takeWhile { it.startsWith("  - ") }.map { it.trim() }
}

/**
 * The names a roster holds, in roster order — the names `switchDevice` accepts.
 *
 * Read off the same lines [extractNamedDeviceRoster] returns, whose shape is
 * `- NAME: PLATFORM/ID[ (target: T)][ ACTIVE-MARKER]`.
 */
internal fun rosterDeviceNames(rosterLines: List<String>): List<String> = rosterLines.mapNotNull { line ->
  line.removePrefix("- ").substringBefore(": ", missingDelimiterValue = "").takeIf { it.isNotBlank() }
}

/**
 * The device ids a roster holds, in roster order — the cast the session can address by name.
 *
 * Read off the same lines [extractNamedDeviceRoster] returns, whose shape is
 * `- NAME: PLATFORM/ID[ (target: T)][ ACTIVE-MARKER]`. The two optional suffixes are stripped by
 * name rather than by splitting on whitespace, because a `web/iPhone 14` instance id contains a
 * space and truncating it would silently drop a bound device from the cast.
 */
internal fun rosterDeviceIds(rosterLines: List<String>): List<String> = rosterLines.mapNotNull { line ->
  line.substringAfter(": ", missingDelimiterValue = "")
    .removeSuffix(ROSTER_ACTIVE_MARKER)
    .substringBefore(ROSTER_TARGET_SUFFIX)
    .takeIf { it.isNotBlank() }
}

/** Suffixes `describeNamedBindings` may append after a roster entry's device id. */
private const val ROSTER_ACTIVE_MARKER = " [ACTIVE]"
private const val ROSTER_TARGET_SUFFIX = " (target: "
