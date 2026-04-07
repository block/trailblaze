package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazePortManager

import xyz.block.trailblaze.util.GitUtils
import java.io.File
import xyz.block.trailblaze.util.Console

class HostTrailblazeLoggingRule(
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  logsBaseUrl: String = "https://localhost:${TrailblazePortManager.resolveEffectiveHttpsPort()}",
  additionalLogEmitter: LogEmitter? = null,
  logsDir: File? = null,
  /** When true, the LogsRepo rejects all writes — fixture sessions remain readable. */
  noLogging: Boolean = false,
  /**
   * When false (default), the LogsRepo does not start FileWatchService threads for
   * reactive session monitoring. Per-run LogsRepo instances only need to write logs —
   * the server's primary LogsRepo (or the desktop app) already watches the directory.
   * Set to true only when this is the sole LogsRepo for the logs directory (e.g., in
   * standalone test harnesses that need reactive session updates).
   */
  watchFileSystem: Boolean = false,
  val logsRepo: LogsRepo = LogsRepo(resolveLogsDir(logsDir), readOnly = noLogging, watchFileSystem = watchFileSystem),
) : TrailblazeLoggingRule(
  logsBaseUrl = logsBaseUrl,
  additionalLogEmitter = additionalLogEmitter,
  noLogging = noLogging,
  writeLogToDisk = { _: SessionId, log: TrailblazeLog ->
    logsRepo.saveLogToDisk(log)
  },
  writeScreenshotToDisk = { screenshot: TrailblazeScreenStateLog ->
    logsRepo.saveScreenshotToDisk(screenshot)
  },
  writeTraceToDisk = { sessionId: SessionId, json: String ->
    val sessionDir = logsRepo.getSessionDir(sessionId)
    File(sessionDir, "trace.json").writeText(json)
  },
) {

  init {
    Console.log("Logs dir: ${logsRepo.logsDir.canonicalPath}")
  }

  companion object {
    private fun resolveLogsDir(explicitLogsDir: File?): File {
      explicitLogsDir?.let { return it }

      val gitRoot = GitUtils.getGitRootViaCommand()
      if (gitRoot != null) return File(gitRoot, "logs")

      // Release/binary builds: use ~/.trailblaze/logs as the default logs directory
      return File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), "logs")
    }
  }
}
