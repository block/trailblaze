package xyz.block.trailblaze.host.rules

import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.GitUtils
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import java.io.File

class HostTrailblazeLoggingRule(
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  sendStartAndEndLogs: Boolean = true,
  logsBaseUrl: String = "https://localhost:8443",
) : TrailblazeLoggingRule(
  sendStartAndEndLogs = sendStartAndEndLogs,
  logsBaseUrl = logsBaseUrl,
  writeLogToDisk = { currentTestName: String, log: TrailblazeLog ->
    logsRepo.saveLogToDisk(log)
  },
  writeScreenshotToDisk = { sessionId: String, fileName: String, bytes: ByteArray ->
    val sessionDir = logsRepo.getSessionDir(sessionId)
    File(sessionDir, fileName).writeBytes(bytes)
  },
  writeTraceToDisk = { sessionId: String, json: String ->
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
