package xyz.block.trailblaze.report.snapshot

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import xyz.block.trailblaze.logs.client.TrailblazeLog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Metadata extracted from a TrailblazeSnapshotLog entry.
 * Contains information about a snapshot including its file location and test context.
 */
data class SnapshotMetadata(
  val file: File,
  val timestamp: LocalDateTime,
  val testClassName: String?,
  val testMethodName: String?,
  val customName: String?,
  val epochMillis: Long,
) {
  companion object {
    /**
     * Create SnapshotMetadata from a TrailblazeSnapshotLog and the logs directory.
     * 
     * @param snapshotLog The log entry containing snapshot metadata
     * @param logsDir The directory where screenshot files are stored
     * @param testClassName Optional test class name from session metadata
     * @param testMethodName Optional test method name from session metadata
     * @return SnapshotMetadata if the screenshot file exists, null otherwise
     */
    fun fromSnapshotLog(
      snapshotLog: TrailblazeLog.TrailblazeSnapshotLog, 
      logsDir: File,
      testClassName: String? = null,
      testMethodName: String? = null
    ): SnapshotMetadata? {
      // Extract filename from screenshotFile (could be a filename or a full URL)
      val screenshotPath = snapshotLog.screenshotFile
      val screenshotFileName = if (screenshotPath.startsWith("http://") || screenshotPath.startsWith("https://")) {
        // Extract filename from URL (e.g., from S3 URL after ATF upload)
        // URL format: https://...?key=...%2Ffilename.png
        val keyParam = screenshotPath.substringAfter("key=", "")
        if (keyParam.isNotEmpty()) {
          // Decode URL-encoded filename (e.g., %2F -> /)
          java.net.URLDecoder.decode(keyParam, "UTF-8")
            .substringAfterLast("/") // Get just the filename
        } else {
          // Fallback: try to extract from path
          screenshotPath.substringAfterLast("/").substringBefore("?")
        }
      } else {
        // Already a filename
        screenshotPath
      }
      
      // First try direct path
      var screenshotFile = File(logsDir, screenshotFileName)
      
      // If not found, search recursively in subdirectories
      if (!screenshotFile.exists()) {
        screenshotFile = logsDir.walkTopDown()
          .firstOrNull { it.isFile && it.name == screenshotFileName }
          ?: run {
            println("⚠️  Screenshot file not found: $screenshotFileName in ${logsDir.absolutePath}")
            println("   Original path from log: $screenshotPath")
            return null
          }
      }
      
      // Convert Instant to LocalDateTime
      val localDateTime = snapshotLog.timestamp
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .toJavaLocalDateTime()
      
      return SnapshotMetadata(
        file = screenshotFile,
        timestamp = localDateTime,
        testClassName = testClassName,
        testMethodName = testMethodName,
        customName = snapshotLog.displayName,
        epochMillis = snapshotLog.timestamp.toEpochMilliseconds()
      )
    }
  }
  
  /**
   * Get a human-readable display name for this snapshot.
   * Falls back to filename if no custom name is provided.
   */
  fun displayName(): String = customName ?: file.nameWithoutExtension
  
  /**
   * Get a formatted timestamp string.
   */
  fun formattedTimestamp(): String = 
    timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
  
  /**
   * Get the full test name (class.method).
   * Returns "Unknown Test" if test context is not available.
   */
  fun fullTestName(): String {
    return when {
      testClassName != null && testMethodName != null -> "$testClassName.$testMethodName"
      testClassName != null -> testClassName
      testMethodName != null -> testMethodName
      else -> "Unknown Test"
    }
  }
  
  /**
   * Get a short test name (just the class name without package).
   */
  fun shortTestName(): String {
    val className = testClassName?.substringAfterLast(".") ?: "Unknown"
    val methodName = testMethodName ?: "unknown"
    return "$className.$methodName"
  }
}
