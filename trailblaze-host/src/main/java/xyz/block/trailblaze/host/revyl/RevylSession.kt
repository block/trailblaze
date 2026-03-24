package xyz.block.trailblaze.host.revyl

/**
 * Represents an active Revyl cloud device session.
 *
 * @property index Local session index (0-based).
 * @property sessionId Unique identifier returned by the Revyl backend.
 * @property workflowRunId Hatchet workflow run powering this session.
 * @property workerBaseUrl HTTP base URL of the device worker (e.g. "https://worker-xxx.revyl.ai").
 * @property viewerUrl Browser URL for live device screen.
 * @property platform "ios" or "android".
 * @property screenWidth Device screen width in pixels (0 when unknown).
 * @property screenHeight Device screen height in pixels (0 when unknown).
 */
data class RevylSession(
  val index: Int,
  val sessionId: String,
  val workflowRunId: String,
  val workerBaseUrl: String,
  val viewerUrl: String,
  val platform: String,
  val screenWidth: Int = 0,
  val screenHeight: Int = 0,
)
