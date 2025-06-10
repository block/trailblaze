package xyz.block.trailblaze.logs.server

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchService
import kotlin.io.path.name

class FileWatchService(
  private val dirToWatch: File,
  private val onFileChange: (ChangeType, File) -> Unit,
) {
  private val eventTypes: List<ChangeType> = listOf(
    ChangeType.CREATE,
    ChangeType.DELETE,
    ChangeType.MODIFY,
  )

  enum class ChangeType(val watchEventKind: WatchEvent.Kind<Path>) {
    CREATE(StandardWatchEventKinds.ENTRY_CREATE),
    DELETE(StandardWatchEventKinds.ENTRY_DELETE),
    MODIFY(StandardWatchEventKinds.ENTRY_MODIFY),
    ;

    companion object {
      fun fromWatchEventKind(watchEventKind: WatchEvent.Kind<out Any>): ChangeType = ChangeType.entries.first { it.watchEventKind == watchEventKind }
    }
  }

  // Path to the directory to watch
  val path: Path = Paths.get(dirToWatch.canonicalPath)

  // Create a WatchService
  val watchService: WatchService = FileSystems.getDefault().newWatchService()

  suspend fun startWatching() {
    // Register the directory with the WatchService for create, delete, and modify events
    path.register(
      watchService,
      eventTypes.map { it.watchEventKind }.toTypedArray(),
    )

    println("Watching directory: $path")

    // Infinite loop to watch for events
    while (true) {
      // Retrieve and remove the next watch key, waiting if none are present
      val key = watchService.take()

      // Process the events for the watch key
      for (event in key.pollEvents()) {
        val kind = event.kind()
        // The filename is the context of the event
        val filename = event.context() as Path

        val realChangedFile = File(path.toFile(), filename.name)
        println("File Changed in $dirToWatch! $kind ${realChangedFile.canonicalPath}")
        onFileChange(ChangeType.fromWatchEventKind(kind), realChangedFile)
      }

      // Reset the key -- this step is critical to receive further watch events
      val valid = key.reset()
      if (!valid) {
        break
      }
    }
  }
}
