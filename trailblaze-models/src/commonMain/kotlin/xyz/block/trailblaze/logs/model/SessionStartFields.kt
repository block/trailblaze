package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.model.TrailblazeTargetAppInfo
import xyz.block.trailblaze.yaml.TrailConfig

/**
 * The parts of a session's [SessionStatus.Started] that something running mid-trail can read about
 * the trail it is in: its config (id, title, metadata) and the app under test. A survey run through
 * `ctx.surveys` reads its summary's trail fields from these, the way a finished session's reader
 * reads them from the `Started` log. A scripted tool can read these, so they hold only the named
 * fields surveys use, never the raw YAML, seeded memory or argument defaults, any of which can carry
 * a sensitive value; a field added to the trail config later stays out until it is named here.
 */
@Serializable
data class SessionStartFields(
  val trailConfig: TrailConfig? = null,
  val targetAppInfo: TrailblazeTargetAppInfo? = null,
) {
  /** Just the fields surveys read: the trail's id, title, target and metadata, and the app's version. */
  fun visible(): SessionStartFields =
    SessionStartFields(
      trailConfig = trailConfig?.let { TrailConfig(id = it.id, title = it.title, target = it.target, metadata = it.metadata) },
      targetAppInfo = targetAppInfo?.let {
        TrailblazeTargetAppInfo(appId = it.appId, versionName = it.versionName, versionCode = it.versionCode, buildNumber = it.buildNumber)
      },
    )

  companion object {
    fun of(started: SessionStatus.Started): SessionStartFields =
      SessionStartFields(trailConfig = started.trailConfig, targetAppInfo = started.targetAppInfo).visible()
  }
}
