package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.deviceClockOffsets
import xyz.block.trailblaze.logs.client.normalizedMs
import xyz.block.trailblaze.logs.client.normalizedTimestamp
import xyz.block.trailblaze.model.TrailblazeTargetAppInfo
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.TrailConfig

/**
 * Sentinel value stamped into [SessionInfo.testClass] (via `SessionStatus.Started.testClassName`)
 * for sessions initiated through the MCP bridge — i.e. CLI commands like
 * `trailblaze snapshot`, `ask`, `step` that don't run inside a JUnit harness and
 * therefore have no real test class to record.
 *
 * Producer: `TrailblazeMcpBridgeImpl.emitSessionStartedLog` writes this value
 * when constructing the session-started log.
 *
 * Consumer: [SessionInfo.displayName] short-circuits the `cls:name` template
 * when [testClass] matches this constant, so user-facing surfaces (`session list`)
 * don't expose the transport layer.
 */
const val MCP_TEST_CLASS_NAME: String = "MCP"

@Serializable
data class SessionInfo(
  val sessionId: SessionId,
  val latestStatus: SessionStatus,
  val timestamp: Instant,
  /**
   * How long the session lasted. The session's own counter when its end status carries one
   * ([SessionStatus.Ended.durationMs]), else the span of its logs.
   *
   * The two differ by whatever ran between the session being minted and its first log. On a device
   * runner that is the pre-trail setup — turbo's detector attach, a locale change — which the log
   * span cannot see because the Started log comes after it. A comparison of two runs that differ
   * only in that setup reads as a tie on the log span. [timestamp] stays the first log's, so
   * `timestamp + durationMs` can land later than the last log by that same setup.
   */
  val durationMs: Long,
  val trailFilePath: String?,
  val hasRecordedSteps: Boolean,
  val trailblazeDeviceId: TrailblazeDeviceId? = null,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo? = null,
  /** Identity + version of the app under test, from the session-start log. Null when not captured. */
  val targetAppInfo: TrailblazeTargetAppInfo? = null,
  val testName: String? = null,
  val testClass: String? = null,
  val trailConfig: TrailConfig? = null,
  val llmUsageSummary: LlmSessionUsageAndCost? = null,
  /** See [SessionStatus.Started.selectedDeviceConfiguration]. Null for single-device sessions. */
  val selectedDeviceConfiguration: String? = null,
  /**
   * Milliseconds to add to a device stamp to reach the host clock, as measured from this session's
   * ingestion anchors (see `deviceClockOffsets`). Carried here because normalizing a session's logs
   * CONSUMES the evidence — a normalized log is marked `clock: host`, so a later reader can no
   * longer derive it — while streams that were never normalized still need it: the device-log panel
   * scrubs on the host timeline over logcat lines stamped by the device. Null when the session had
   * no anchored device-clock log, and for the summary path, which parses only status logs.
   */
  val deviceClockOffsetMs: Long? = null,
  /**
   * When the session ended, or null while it is still running.
   *
   * The unambiguous end of the interval [durationMs] measures, which [timestamp] plus [durationMs]
   * is not: that lands later than the session really ended, by whatever ran before the first log.
   * A synthetic end (an abandoned session) has no log of its own to read, so its end is carried
   * here rather than inferred from the last log that happened to be written.
   *
   * Last in the parameter list on purpose: a field added in the middle shifts every `componentN`
   * after it, so an already-compiled consumer destructures into the wrong values rather than
   * failing to link.
   */
  val endTimestamp: Instant? = null,
) {
  // Title resolution priority:
  //  1. trailConfig.title  — explicit human-readable title in YAML
  //  2. trailConfig.id     — explicit stable ID in YAML (e.g. "sample-app/taps/simple-tap")
  //  3. trailFilePath      — derived from asset path when running from a YAML file
  //                          e.g. "trails/EvaluationLongTest/tenKey.trail.yaml"
  //                               → "EvaluationLongTest/tenKey"
  //  4. ClassName/method   — fully-qualified stable name for tool-based tests
  //  5. sessionId          — last resort (includes timestamp+random, not stable across runs)
  //
  // MCP-initiated sessions (`trailblaze snapshot`, `ask`, `step` over the CLI/MCP
  // bridge) carry `testClass = MCP_TEST_CLASS_NAME` as a downstream marker — but
  // rendering it verbatim leaks the transport layer in user-facing surfaces
  // (`session list` showed labels like "MCP:Capture screen state"). Compute
  // `displayTestClass` once with the marker normalized away, then both branches
  // below share the same answer.
  @Transient
  val displayName: String = run {
    val displayTestClass = testClass
      ?.takeIf { it.isNotBlank() && it.trim().uppercase() != MCP_TEST_CLASS_NAME }
    trailConfig?.title?.takeIf { it.isNotBlank() }
      ?: trailConfig?.id?.takeIf { it.isNotBlank() }
      ?: trailFilePath?.takeIf { it.isNotBlank() }?.let { TrailRecordings.shortTrailName(it) }
      ?: testName?.takeIf { it.isNotBlank() }?.let { name ->
        displayTestClass?.let { cls -> "$cls:$name" } ?: name
      }
      ?: displayTestClass
      ?: sessionId.value
  }

  /**
   * Stable identifier used to group retries of the same test together. Distinct from
   * [displayName], which is the human-readable label and may collide between unrelated tests.
   *
   * Priority — prefers identifiers that are unique per test, falling back to less stable forms:
   *   1. `trailConfig.id`        — explicit stable id from the YAML
   *   2. `trailFilePath`         — asset path (stripped of `trails/` prefix and `.trail.yaml`
   *                                suffix), stable for trail-only runs
   *   3. `testClass:testName`    — JUnit-style fully-qualified id
   *   4. [displayName]           — last resort
   *
   * `trailFilePath` is preferred over `testClass:testName` because some YAML runners emit fixed
   * class/method pairs (e.g. `HostAccessibilityV3:run`) that would otherwise collapse unrelated
   * trails into a single group.
   *
   * Every tier is blank-guarded, so this is never the empty string — only [sessionId] is
   * unconditional. A blank tier that resolved would be far worse than a missing one: consumers
   * group on this key, and `?:` / jq's `//` both treat `""` as a present value, so every session
   * carrying it would collapse into a single group and take the losers' verdicts with it.
   * `trailConfig.id` is author-supplied YAML (`id:` with nothing after it), which is why it needs
   * the guard as much as the derived tiers do.
   */
  @Transient
  val stableTestKey: String = trailConfig?.id?.takeIf { it.isNotBlank() }
    ?: trailFilePath?.takeIf { it.isNotBlank() }?.let { TrailRecordings.shortTrailName(it) }
    ?: testName?.takeIf { it.isNotBlank() }?.let { name ->
      testClass?.let { cls -> "$cls:$name" } ?: name
    }
    ?: displayName
}

fun List<TrailblazeLog>.getSessionStatus(): SessionStatus = this
  .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
  .lastOrNull()?.sessionStatus ?: SessionStatus.Unknown

fun List<TrailblazeLog>.getSessionStartedInfo(): SessionStatus.Started? = this
  .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
  .map { it.sessionStatus }
  .filterIsInstance<SessionStatus.Started>()
  .firstOrNull()

fun List<TrailblazeLog>.getSessionInfo(): SessionInfo? {
  if (this.isEmpty()) {
    return null
  }
  val sessionStartedInfo: SessionStatus.Started? = this.getSessionStartedInfo()
  // Earliest/latest on the HOST timeline, not first/last in the list: a session mixing host- and
  // device-stamped logs is not chronological in either raw order or raw timestamp, so positional
  // bookends read a device's skew as session duration (and can report it negative).
  val offsets = deviceClockOffsets()
  val firstLog: TrailblazeLog = this.minBy { it.normalizedMs(offsets) }
  val lastLog: TrailblazeLog = this.maxBy { it.normalizedMs(offsets) }

  val latestStatus = this.getSessionStatus()
  // The session's own counter first: it starts when the session is minted — on a device runner,
  // before the pre-trail setup and the Started log — and is measured on one clock, so neither the
  // setup nor device skew is lost. The log span is the fallback for an end status that carries no
  // duration (a synthetic end, an unfinished session).
  val durationMs = (latestStatus as? SessionStatus.Ended)?.durationMs?.takeIf { it > 0 }
    ?: (lastLog.normalizedMs(offsets) - firstLog.normalizedMs(offsets))

  // The log that CLOSED the session, not the last log written: logs keep arriving after a session
  // ends — a recording's save-back appends its progress afterwards — and taking the newest of those
  // reports a completion later than the session actually reached. An unfinished session has no end
  // at all; reporting one would invent a completion it never had.
  val endTimestamp = (latestStatus as? SessionStatus.Ended)?.let {
    this.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .filter { statusLog -> statusLog.sessionStatus is SessionStatus.Ended }
      .maxByOrNull { statusLog -> statusLog.normalizedMs(offsets) }
      ?.normalizedTimestamp(offsets)
  }

  return SessionInfo(
    sessionId = firstLog.session,
    timestamp = firstLog.normalizedTimestamp(offsets),
    latestStatus = latestStatus,
    trailblazeDeviceId = sessionStartedInfo?.trailblazeDeviceId,
    testName = sessionStartedInfo?.testMethodName,
    testClass = sessionStartedInfo?.testClassName,
    trailblazeDeviceInfo = sessionStartedInfo?.trailblazeDeviceInfo,
    targetAppInfo = sessionStartedInfo?.targetAppInfo,
    trailConfig = sessionStartedInfo?.trailConfig,
    durationMs = durationMs,
    endTimestamp = endTimestamp,
    trailFilePath = sessionStartedInfo?.trailFilePath,
    hasRecordedSteps = sessionStartedInfo?.hasRecordedSteps ?: false,
    llmUsageSummary = this.computeUsageSummary(),
    selectedDeviceConfiguration = sessionStartedInfo?.selectedDeviceConfiguration,
    deviceClockOffsetMs = offsets?.sessionWideOffsetMs,
  )
}
