package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable

/**
 * A trail the runner declined to run because its `config.skip:` resolved a reason for this device.
 *
 * A skip is honored BEFORE a session is opened - no `SessionStarted` is logged and no session
 * directory is written - so a skipped trail leaves no trace in the log-backed report pipeline. That
 * is the right runtime behavior (see `TrailCommand.planTrailExecution` and the matching pre-flight
 * in each host rule) but it makes the report unable to distinguish a trail that was deliberately
 * held back from one nobody ever configured. This record is the runner telling the report what it
 * chose not to run, and why.
 *
 * Field names are snake_case to match [SessionResult], the sibling row shape it becomes in
 * `trailblaze_test_report.json`.
 */
@Serializable
data class SkippedTrail(
  /** Absolute path of the trail file, as the runner resolved it. */
  val trail_path: String,

  /** Human-readable label: the trail's `config.title`, else its `config.id`, else its short name. */
  val title: String,

  /**
   * The identity a session for this trail WOULD have carried, resolved by the same rule as
   * `SessionInfo.stableTestKey` (`config.id`, else the `trails/`-relative short name). Sharing the
   * rule is what lets the report's matrix put a skipped device's cell on the same row as the
   * devices that ran - the whole point of surfacing a skip next to its siblings rather than in a
   * list of its own.
   */
  val test_key: String,

  /**
   * The trail's declared `config.id`, or null when it declares none.
   *
   * Deliberately narrower than [test_key], which falls back to a short name. This pair of fields
   * exists to match ONE consumer: `RunReportGenerator.sessionMetaJson`, which emits a session's
   * `trailId` from `trailConfig.id` and its `target` from `trailConfig.target`, and which the
   * viewer keys its matrix rows on. A session carrying no `trailId` gets a row of its own, so a
   * skip that invented an id where the run declared none would sit in a row the run can never
   * join.
   *
   * `RunIndexGenerator.stubMeta` keys rows differently - `test_key` and the report-wide
   * `metadata.target_app` - but it applies that rule to every row it emits, skips and runs alike,
   * so the two agree there without these fields.
   */
  val trail_id: String? = null,

  /** The trail's `config.target`, emitted alongside [trail_id] and read by the same consumer. */
  val target: String? = null,

  /**
   * The trail's `config.metadata`, carried verbatim onto the row this becomes.
   *
   * Not decoration: this map is where a trail declares its durable TestRail case id
   * (`config.metadata.testRailCaseId`), and that id is the cross-run join key for a trail whose
   * `test_key` no longer spells `case_<n>`. Dropping it would give a skipped row a null case id
   * while the same trail's runs carry a real one, splitting one trail's history in two.
   */
  val metadata: Map<String, String>? = null,

  /** The resolved `config.skip:` reason. Never blank - a blank reason means "not skipped". */
  val reason: String,

  /** Platform the skip was resolved for (`android`, `ios`, `web`), when the run named a device. */
  val platform: String? = null,

  /** The device classifiers the skip resolved against, joined as the report's `device_classifier`. */
  val device_classifier: String? = null,

  /**
   * The trail's `config.source.type`, resolved by the same rule the report applies to a session
   * that ran, so a skipped row doesn't claim a different provenance than the same trail's passing
   * rows on other devices.
   */
  val trail_source: String = SOURCE_TYPE_GENERATED,

  /** When the runner recorded the skip. Orders skips alongside runs in the report. */
  val recorded_at_epoch_ms: Long,
)
