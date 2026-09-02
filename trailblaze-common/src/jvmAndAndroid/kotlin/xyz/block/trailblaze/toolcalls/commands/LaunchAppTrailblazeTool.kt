package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.LaunchAppCommand
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

/**
 * Decides whether an Android launch actually took, re-issuing it once when it didn't.
 *
 * `startActivity` reports nothing when the platform drops the start — the app simply never comes
 * up, and every later step in the trail runs against whatever was on screen (usually the launcher)
 * until something fails with an unrelated "element not found". So the foreground poll's answer is
 * the only launch verdict there is, and a discarded `false` is a tool reporting a launch it did not
 * perform.
 *
 * The re-issue is here rather than in a driver because the drop is transient and driver-agnostic:
 * the next trail's launch, seconds later, lands.
 *
 * The re-issue must not destroy more state than the declared mode already permits — the poll that
 * triggers it can be a false negative on a launch that worked. [relaunchModeFor] holds that rule.
 *
 * Free of the tool and its context so the policy is testable without a device.
 */
internal suspend fun foregroundFailureAfterLaunch(
  appId: String,
  awaitForeground: (maxWaitMs: Long) -> Boolean,
  relaunch: suspend () -> TrailblazeToolResult,
): ForegroundVerdict {
  if (awaitForeground(LaunchForegroundBudget.FIRST_ATTEMPT_MS)) return ForegroundVerdict.InForeground
  // The re-issue itself can throw rather than return: Maestro's launch surfaces
  // UnableToLaunchApp / UnableToClearState as exceptions. Fold that into the same verdict so the
  // report says the launch never foregrounded, instead of an unrelated stack trace.
  val retried = runCatching { relaunch() }
    .getOrElse { thrown ->
      return ForegroundVerdict.Failed(
        "Launched $appId, but it never reached the foreground, and re-issuing the launch threw: " +
          "${thrown.message ?: thrown::class.simpleName}",
      )
    }
  // A fatal result terminates execution by contract; passing it through unchanged keeps that
  // meaning rather than flattening it into a retryable error.
  if (retried is TrailblazeToolResult.Error.FatalError) return ForegroundVerdict.Fatal(retried)
  if (retried is TrailblazeToolResult.Error) {
    return ForegroundVerdict.Failed(
      "Launched $appId, but it never reached the foreground, and re-issuing the launch " +
        "failed: ${retried.errorMessage}",
    )
  }
  if (awaitForeground(LaunchForegroundBudget.RETRY_MS)) return ForegroundVerdict.Relaunched
  return ForegroundVerdict.Failed(
    "Launched $appId, but it never reached the foreground — twice, waiting " +
      "${LaunchForegroundBudget.FIRST_ATTEMPT_MS / 1000}s then " +
      "${LaunchForegroundBudget.RETRY_MS / 1000}s. The device is showing something else (usually " +
      "the launcher), so the rest of this trail would run against the wrong screen.",
  )
}

/**
 * The launch mode a re-issue goes out in, given the mode the caller asked for.
 *
 * A re-issue fires on a foreground miss, which can be a false negative on a launch that actually
 * worked — so it must not destroy more state than the declared mode already permits.
 *
 * [LaunchMode.REINSTALL] is downgraded because it clears app data: repeating it would wipe a
 * logged-in session mid-trail, unrecoverable. [LaunchMode.RESUME] is repeated as-is rather than
 * upgraded, because `RESUME` promises to continue an app already in memory and `FORCE_RESTART`
 * would kill the very process that promise is about — re-issuing the resume is both the
 * contract-preserving and the correct recovery for a dropped start.
 */
internal fun relaunchModeFor(
  declared: LaunchAppTrailblazeTool.LaunchMode,
): LaunchAppTrailblazeTool.LaunchMode = when (declared) {
  LaunchAppTrailblazeTool.LaunchMode.RESUME -> LaunchAppTrailblazeTool.LaunchMode.RESUME
  LaunchAppTrailblazeTool.LaunchMode.REINSTALL,
  LaunchAppTrailblazeTool.LaunchMode.FORCE_RESTART,
  -> LaunchAppTrailblazeTool.LaunchMode.FORCE_RESTART
}

/**
 * How long `launchApp` may spend deciding whether a launch reached the foreground.
 *
 * The whole verdict — first poll, re-issue, second poll — runs inside ONE synchronous MCP `step`
 * call, so [TOTAL_MS] is bounded by the client's request deadline, not by how patient we would
 * like to be. Overrun does not degrade gracefully: the caller times out the socket and the user
 * sees a transport error instead of the "never reached the foreground" message this tool exists to
 * produce. So a budget that cannot fit is strictly worse than a budget that is too short.
 *
 * `LaunchForegroundBudgetFitsRequestDeadlineTest` in `:trailblaze-host` holds [TOTAL_MS] against
 * `CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS`, because the two constants live in different modules
 * and nothing else would notice them drifting apart.
 */
object LaunchForegroundBudget {

  /**
   * Room left under the request deadline for everything that is not polling: two `startActivity`
   * round trips, the view-hierarchy read, and MCP transport.
   */
  const val REQUEST_DEADLINE_HEADROOM_MS = 20_000L

  /** Everything [foregroundFailureAfterLaunch] may spend. Must fit the request deadline. */
  const val TOTAL_MS = 160_000L

  /**
   * The first attempt gets the full supported cold start — `AppUnderTestLauncher.LAUNCH_TIMEOUT_MS`
   * allows the same 120s because a big app's first launch on a farm emulator includes dex/AOT
   * warmup. While this poll was advisory an impatient budget only cost time; as the verdict it
   * would force-stop an app that was still coming up and report the launch as failed.
   */
  const val FIRST_ATTEMPT_MS = 120_000L

  /**
   * What is left for the re-issue. Derived rather than written down, so the two numbers cannot
   * contradict [TOTAL_MS].
   *
   * Smaller than the first attempt, and that is a real limit rather than a claim that a re-issued
   * start is cheaper: `FORCE_RESTART` stops the process, so the second attempt is also a cold
   * start. An app whose *second* cold start needs more than this — after its first was dropped —
   * is reported as a failed launch. That intersection is narrow, and the alternative is spending
   * the deadline on it and turning every failed launch into an opaque client timeout.
   */
  const val RETRY_MS = TOTAL_MS - FIRST_ATTEMPT_MS
}

/** What [foregroundFailureAfterLaunch] concluded. */
internal sealed interface ForegroundVerdict {
  /** The first launch landed; nothing was re-issued. */
  data object InForeground : ForegroundVerdict

  /** The first launch was dropped and the re-issue landed — worth reporting, not a failure. */
  data object Relaunched : ForegroundVerdict

  /** The app is not in the foreground and the tool should fail with [message]. */
  data class Failed(val message: String) : ForegroundVerdict

  /** The re-issue returned a fatal result, which propagates unchanged. */
  data class Fatal(val result: TrailblazeToolResult.Error.FatalError) : ForegroundVerdict
}

@Serializable
@TrailblazeToolClass("launchApp")
@LLMDescription(
  "Open an app on the device as if a user tapped on its icon in the launcher.",
)
data class LaunchAppTrailblazeTool(
  @LLMDescription("The package name of the app to launch. Example: 'com.example.app'")
  val appId: String,
  @LLMDescription(
    """
Available App Launch Modes:
- "REINSTALL" (Default if unspecified) will launch the app as if it was just installed and never run on the device before.
- "RESUME" will launch the app like you would from the apps launcher.  If the app was in memory, it'll pick up where it left off.
- "FORCE_RESTART" will force stop the application and then launch the app like you would from the app launcher.
    """,
  )
  val launchMode: LaunchMode = LaunchMode.REINSTALL,
  override val reasoning: String? = null,
) : MapsToMaestroCommands(), ReasoningTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // iOS system apps (Calendar, Contacts, etc.) cannot have their state cleared — the OS
    // prohibits uninstalling them. Skip clearState upfront rather than catching the error.
    // All Apple system apps use the com.apple.* prefix, which third-party apps cannot use.
    val isIosSystemApp = toolExecutionContext.trailblazeDeviceInfo.platform == TrailblazeDevicePlatform.IOS &&
      appId.startsWith("com.apple.")
    val effectiveLaunchMode = if (launchMode == LaunchMode.REINSTALL && isIosSystemApp) {
      LaunchMode.FORCE_RESTART
    } else {
      launchMode
    }
    if (effectiveLaunchMode != launchMode) {
      return copy(launchMode = effectiveLaunchMode).execute(toolExecutionContext)
    }

    val result = launchOnce(toolExecutionContext)
    if (!result.isSuccess()) return result

    // Wait for the app to reach the foreground before returning, so the next view hierarchy
    // snapshot is stable. This replaces a blind 2s delay — polling returns as soon as the app
    // is ready (typically <1s) while still handling slow CI cold starts.
    val androidDeviceCommandExecutor = toolExecutionContext.androidDeviceCommandExecutor
    if (toolExecutionContext.trailblazeDeviceInfo.platform == TrailblazeDevicePlatform.ANDROID &&
      androidDeviceCommandExecutor != null
    ) {
      val verdict = foregroundFailureAfterLaunch(
        appId = appId,
        // The poll blocks (PollingUtils sleeps the calling thread), so it must not run on the
        // caller's dispatcher.
        awaitForeground = { maxWaitMs ->
          runBlocking(Dispatchers.IO) {
            androidDeviceCommandExecutor.waitUntilAppInForeground(
              appId = appId,
              maxWaitMs = maxWaitMs,
            )
          }
        },
        relaunch = {
          copy(launchMode = relaunchModeFor(effectiveLaunchMode)).launchOnce(toolExecutionContext)
        },
      )
      when (verdict) {
        is ForegroundVerdict.Fatal -> return verdict.result
        is ForegroundVerdict.Failed -> return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = verdict.message,
          command = this,
        )
        // Say so in the result rather than only in a console line, which quiet mode drops — a
        // launch that needed a second attempt is the flake signal worth keeping in the report.
        ForegroundVerdict.Relaunched -> return TrailblazeToolResult.Success(
          message = "Launched $appId ($effectiveLaunchMode) after re-issuing a dropped launch",
        )
        ForegroundVerdict.InForeground -> Unit
      }
    }
    return TrailblazeToolResult.Success(message = "Launched $appId ($effectiveLaunchMode)")
  }

  /**
   * One pass of the underlying Maestro launch. Exists because `super.execute` cannot be called
   * from inside the relaunch lambda, so the re-issue needs a named member to go through.
   */
  private suspend fun launchOnce(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = super.execute(toolExecutionContext)

  override fun toMaestroCommands(): List<Command> = listOf(
    LaunchAppCommand(
      appId = appId,
      clearState = when (launchMode) {
        LaunchMode.REINSTALL -> true
        LaunchMode.RESUME,
        LaunchMode.FORCE_RESTART,
        -> false
      },
      stopApp = when (launchMode) {
        LaunchMode.RESUME -> false
        LaunchMode.FORCE_RESTART,
        LaunchMode.REINSTALL,
        -> true
      },
    ),
  )

  @Serializable(with = LaunchMode.Serializer::class)
  enum class LaunchMode {
    /**
     * Launch the app in a clean state, like when the app is initially installed.
     */
    REINSTALL,

    /**
     * Resume the app without clearing its state.
     */
    RESUME,

    /**
     * Stop the application and then restart it without clearing its state.
     */
    FORCE_RESTART,

    ;

    object Serializer : CaseInsensitiveEnumSerializer<LaunchMode>(LaunchMode::class, LaunchMode.entries)

    companion object {
      fun fromString(value: String?): LaunchMode = LaunchMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: REINSTALL
    }
  }
}
