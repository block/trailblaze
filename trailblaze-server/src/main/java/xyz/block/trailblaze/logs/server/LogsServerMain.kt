package xyz.block.trailblaze.logs.server

import xyz.block.trailblaze.report.utils.GitUtils
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

fun main(args: Array<String>) {
  val port = args.getOrNull(1)?.toIntOrNull() ?: 52525

  val gitDir = GitUtils.getGitRootViaCommand()
    ?: throw RuntimeException("Could not find git root directory. Make sure you are running this from a git repository.")

  val logsDir = File(gitDir, "logs").also {
    it.mkdirs()
    println("logsDir: ${it.canonicalPath}")
  }

  val logsRepo = LogsRepo(logsDir)

  println("Once the server is running, you'll be able to view captured logs from any executions.")
  println("Use trailblaze using 'host' mode (from your laptop) or 'on-device' mode (within the Android device).")
  println("With the server is running, you'll be able to view captured logs from any executions.")
  println("Running the logs-server is NOT required, but extremely helpful for debugging local usage.")
  println("Open http://localhost:52525 in your browser to view the trailblaze logs.")
  println("NOTE: Gradle will continue to say 'EXECUTING' until you stop the server.")
  TrailblazeMcpServer(logsRepo, isOnDeviceMode = { true }).startSseMcpServer(
    port = port,
    wait = true,
  )
}
