package xyz.block.trailblaze.report.snapshot

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import java.io.File

/**
 * Collects and organizes snapshots from pre-parsed logs.
 * 
 * This collector uses logs already parsed by [LogsRepo], avoiding duplicate file I/O
 * and JSON parsing. It extracts [TrailblazeLog.TrailblazeSnapshotLog] entries and
 * converts them to [SnapshotMetadata] for the snapshot viewer.
 * 
 * Usage:
 * ```kotlin
 * val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
 * val collector = SnapshotCollector(logsDir)
 * val snapshots = collector.collectSnapshots(
 *   logsBySession = logsRepo.logsBySession,
 *   sessionInfoBySession = logsRepo.sessionInfoBySession
 * )
 * ```
 */
class SnapshotCollector(private val logsDir: File) {
  
  /**
   * Collect snapshots from pre-parsed logs.
   * 
   * @param logsBySession Map of session ID to parsed logs for that session
   * @param sessionInfoBySession Map of session ID to session info (for test metadata)
   * @return Map of test name -> list of snapshots, sorted by timestamp
   */
  fun collectSnapshots(
    logsBySession: Map<SessionId, List<TrailblazeLog>>,
    sessionInfoBySession: Map<SessionId, SessionInfo?>
  ): Map<String, List<SnapshotMetadata>> {
    println("📸 Collecting snapshots from ${logsBySession.size} session(s)...")
    
    val snapshots = mutableListOf<SnapshotMetadata>()
    var totalSnapshotLogs = 0
    
    for ((sessionId, logs) in logsBySession) {
      // Extract snapshot logs from this session
      val snapshotLogs = logs.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
      totalSnapshotLogs += snapshotLogs.size
      
      if (snapshotLogs.isEmpty()) continue
      
      // Get test metadata from session info
      val sessionInfo = sessionInfoBySession[sessionId]
      val testClassName = sessionInfo?.testClass
      val testMethodName = sessionInfo?.testName
      
      // Convert to SnapshotMetadata
      for (snapshotLog in snapshotLogs) {
        val metadata = SnapshotMetadata.fromSnapshotLog(
          snapshotLog, 
          logsDir, 
          testClassName, 
          testMethodName
        )
        
        if (metadata != null) {
          snapshots.add(metadata)
          println("✅ Found snapshot: ${metadata.shortTestName()} - ${metadata.displayName()}")
        } else {
          println("⚠️  Skipping snapshot (screenshot file not found): ${snapshotLog.screenshotFile}")
        }
      }
    }
    
    println("📸 Found $totalSnapshotLogs snapshot log(s) across ${logsBySession.size} session(s)")
    
    if (snapshots.isEmpty() && totalSnapshotLogs > 0) {
      println("⚠️  Warning: Found $totalSnapshotLogs snapshot log(s) but could not locate any screenshot files.")
      println("   This may happen if:")
      println("   - Screenshot files were not saved to disk")
      println("   - Screenshot files are in a different directory")
      println("   - Logs are from an older version with different filename format")
    }
    
    // Group by full test name and sort by timestamp within each group
    return snapshots
      .groupBy { it.fullTestName() }
      .mapValues { (_, snapshotList) -> 
        snapshotList.sortedBy { it.epochMillis }
      }
      .toSortedMap() // Sort test names alphabetically
  }
  
  /**
   * Get a summary of collected snapshots.
   */
  fun getSummary(snapshots: Map<String, List<SnapshotMetadata>>): String {
    val totalSnapshots = snapshots.values.sumOf { it.size }
    val totalTests = snapshots.size
    
    return buildString {
      appendLine("📊 Snapshot Summary:")
      appendLine("   Total Tests: $totalTests")
      appendLine("   Total Snapshots: $totalSnapshots")
      appendLine()
      snapshots.forEach { (testName, snapshotList) ->
        appendLine("   • ${testName.substringAfterLast(".")}: ${snapshotList.size} snapshot(s)")
      }
    }
  }
}
