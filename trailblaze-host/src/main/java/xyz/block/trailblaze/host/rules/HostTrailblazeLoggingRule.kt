package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.rules.TrailblazeLoggingRule

import xyz.block.trailblaze.util.GitUtils
import java.io.File

class HostTrailblazeLoggingRule(
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  logsBaseUrl: String = "https://localhost:8443",
) : TrailblazeLoggingRule(
  logsBaseUrl = logsBaseUrl,
  writeLogToDisk = { sessionId: SessionId, log: TrailblazeLog ->
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

  companion object {
    private val gitRoot = GitUtils.getGitRootViaCommand()
    private val logsDir = File(gitRoot, "logs").also { println("Logs dir: ${it.canonicalPath}") }
    private val logsRepo = LogsRepo(logsDir)
  }
}
