package xyz.block.trailblaze.report.utils

import java.io.File
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent

/**
 * Emitted from our [FileWatchService] when changes occur on the file system.
 */
data class FileChangeEvent(
  val changeType: ChangeType,
  val file: File,
) {
  enum class ChangeType(val watchEventKind: WatchEvent.Kind<Path>) {
    CREATE(StandardWatchEventKinds.ENTRY_CREATE),
    DELETE(StandardWatchEventKinds.ENTRY_DELETE),
    MODIFY(StandardWatchEventKinds.ENTRY_MODIFY),
    ;

    companion object {
      fun fromWatchEventKind(watchEventKind: WatchEvent.Kind<out Any>): ChangeType = ChangeType.entries.first { it.watchEventKind == watchEventKind }
    }
  }
}
