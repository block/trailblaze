package xyz.block.trailblaze.trailhead

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Trailhead teardown: wire shapes shared by the runtime, the TypeScript SDK (`trailhead.ts`) and the
 * report. A trailhead tool's `teardown` runs once after every trailhead invocation that started;
 * a reusable result is handed to the next run of the same trailhead as `ctx.previousRun`.
 */

/** How the trail that just ended finished. Serial names match the TS `TrailOutcome`. */
@Serializable
enum class TrailOutcome {
  @SerialName("passed") PASSED,
  @SerialName("failed") FAILED,
  @SerialName("cancelled") CANCELLED,

  /** The trailhead threw, so no trail step ran. */
  @SerialName("setup-failed") SETUP_FAILED,
}

/** What `teardown` returned, after the framework's forced-`false` rules. */
@Serializable
data class TeardownResult(
  val reusable: Boolean,
  val reason: String,
  val state: JsonObject? = null,
)

/**
 * `ctx.previousRun`: a reusable [TeardownResult] and the session that returned it. Only a
 * `reusable: true` result is ever delivered, so it carries no `reusable` flag.
 */
@Serializable
data class PreviousRun(
  val sessionId: String,
  val reason: String,
  val state: JsonObject? = null,
  /**
   * The one waypoint the screen matched as that run's trailhead returned; a reused setup must land
   * on it again. Null when nobody checked (an on-device run). A run whose landing matched no
   * waypoint, or more than one, hands nothing on.
   */
  val trailheadEndWaypoint: String? = null,
)

/** The `{ outcome }` payload `teardown` receives; the SDK adds `events`. */
@Serializable
data class TrailRunWire(
  val outcome: TrailOutcome,
)

/**
 * The `trailhead` session event stream (`events/trailhead.ndjson`): the trail markers, what
 * each trailhead invocation received and did, and what `teardown` returned. The report and
 * surveys read it like any other stream.
 */
object TrailheadEventStream {
  const val NAME: String = "trailhead"

  /** The `type` field of each record's `data`. */
  object Type {
    /** A trailhead invocation started. */
    const val TRAILHEAD_START: String = "trailheadStart"

    /** What the invocation received as `previousRun`, or why it got `null`. */
    const val PREVIOUS_RUN: String = "previousRun"

    /** What the handler did with the previous setup; the `setup` field is one of [Setup]. */
    const val SETUP: String = "setup"

    /**
     * Where a trailhead invocation left the screen: every waypoint it matched as it returned, in
     * `waypoints`, or `waypoints: null` with a `reason` when nothing checked. A reused invocation
     * also names the previous run's `trailheadEndWaypoint` it was checked against in `expected`,
     * and `missing` repeats it when the screen did not match it.
     */
    const val TRAILHEAD_END: String = "trailheadEnd"

    /** The trailhead returned; trail steps start. */
    const val TRAIL_START: String = "trailStart"

    /** The trail's verdict is fixed. */
    const val TRAIL_END: String = "trailEnd"

    /** What `teardown` returned (after the forced-`false` rules), or why it didn't run cleanly. */
    const val TEARDOWN: String = "teardown"
  }

  /** The `setup` field of a [Type.SETUP] record. A `failed: true` field marks a handler that threw. */
  object Setup {
    /** `previousRun.reuse(...)` was called and its catch-up finished. */
    const val REUSED: String = "reused"

    /** `previousRun.reuse(...)` was called and the handler failed during or after it. */
    const val REUSE_FAILED: String = "reuseFailed"

    /** The handler never called `reuse`. */
    const val FULL_SETUP: String = "fullSetup"
  }
}

/** Wire constants shared with the SDK's `trailhead.ts`. */
object TeardownWire {
  /** `_meta.trailblaze.trailhead` on a tool result: `{ reuse? }`. */
  const val META_KEY_TRAILHEAD: String = "trailhead"

  /** Reserved host functions behind `trailRun.events`. */
  const val HOST_FUNCTION_TRAIL_EVENTS_LIST: String = "trailblaze.trailEvents.list"
  const val HOST_FUNCTION_TRAIL_EVENTS_QUERY: String = "trailblaze.trailEvents.query"

  /** The step text of the finish objective that wraps `teardown` in the session log. */
  const val OBJECTIVE_STEP: String = "Teardown"
}
