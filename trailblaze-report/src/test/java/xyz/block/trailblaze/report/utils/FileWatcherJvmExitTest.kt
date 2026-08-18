package xyz.block.trailblaze.report.utils

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A process that starts a file watcher must still be able to exit on its own.
 *
 * The watch loop parks in `WatchService.take()` forever, so if its thread is non-daemon the JVM
 * hangs in `DestroyJavaVM` after `main` returns — no exception, no output, just a wedged process
 * until SIGKILL. That hit every CLI command whose exit code was 0 (`TrailblazeCli.run` only calls
 * `exitProcess` for non-zero codes), most visibly `trailblaze report` on an empty logs directory.
 *
 * Verified by forking a JVM rather than by inspecting threads in-process: "the process terminates"
 * is the actual contract, and it's the thing that was broken.
 */
class FileWatcherJvmExitTest {

  @Test
  fun `a jvm that starts a FileWatchService exits once main returns`() {
    assertProbeJvmExits(WatcherExitProbe.MODE_FILE_WATCH_SERVICE)
  }

  @Test
  fun `a jvm that opens a watching LogsRepo exits once main returns`() {
    // The shape the CLI actually runs: every command that touches the logs repo gets these
    // watchers for free, so the exit contract has to hold at this level too.
    assertProbeJvmExits(WatcherExitProbe.MODE_LOGS_REPO)
  }

  private fun assertProbeJvmExits(mode: String) {
    val workDir = Files.createTempDirectory("watcher-exit-$mode").toFile()
    val watchDir = File(workDir, "watched")
    assertTrue(watchDir.mkdirs(), "Could not create the probe's watch directory at $watchDir")
    // Redirect to a file rather than reading the pipe: a wedged probe never closes its stdout, so
    // reading the stream to EOF would park this test instead of failing it.
    val outputFile = File(workDir, "probe-output.txt")
    val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
    val process = ProcessBuilder(
      java,
      "-cp",
      System.getProperty("java.class.path"),
      WatcherExitProbe::class.java.name,
      mode,
      watchDir.absolutePath,
    ).redirectErrorStream(true).redirectOutput(outputFile).start()
    try {
      val exited = process.waitFor(HANG_GUARD_SECONDS, TimeUnit.SECONDS)
      assertTrue(
        exited,
        "Probe JVM ($mode) never exited — a non-daemon watcher thread is holding it open. " +
          "Output:\n${outputFile.readProbeOutput()}",
      )
      assertEquals(
        0,
        process.exitValue(),
        "Probe JVM ($mode) failed. Output:\n${outputFile.readProbeOutput()}",
      )
    } finally {
      // Wait for the kill to land before cleaning up: on the failing path the probe is still
      // holding a watch on workDir, and returning without reaping it leaves an orphan JVM behind
      // for the rest of the CI run.
      process.destroyForcibly().waitFor()
      workDir.deleteRecursively()
    }
  }

  private fun File.readProbeOutput(): String = if (exists()) readText() else "(no output captured)"

  companion object {
    /**
     * Hang containment, not a performance budget: the probe JVM either exits in about a second or
     * never exits at all, so this bound exists only to turn the "never" case into an attributable
     * failure instead of a parked test run.
     */
    private const val HANG_GUARD_SECONDS = 60L
  }
}

/**
 * Entry point for the forked JVM in [FileWatcherJvmExitTest]: start a watcher, then return from
 * `main` without stopping it. Deliberately does NOT call `stopWatching()` / `close()` — the point
 * is that a caller which never gets around to it (every CLI command that reads the logs repo)
 * still exits.
 *
 * Before returning it confirms a watch thread is actually running, and throws (exiting non-zero) if
 * not. Without that, a probe that silently watched nothing — `startWatching()` logs and returns when
 * `path.register` fails — would exit cleanly and take the exit assertion green having proven nothing.
 */
object WatcherExitProbe {
  const val MODE_FILE_WATCH_SERVICE = "file-watch-service"
  const val MODE_LOGS_REPO = "logs-repo"

  /** Name prefix [FileWatchService] gives the thread running its watch loop. */
  private const val WATCH_THREAD_NAME_PREFIX = "FileWatcher-"

  /**
   * Both modes start the watcher synchronously, so the thread is normally up on the first check.
   * The poll only covers a watcher that starts off-thread, and bounds how long a broken one takes
   * to report.
   */
  private const val WATCH_THREAD_WAIT_SECONDS = 10L

  @JvmStatic
  fun main(args: Array<String>) {
    val mode = args[0]
    val dir = File(args[1])
    check(dir.isDirectory) { "Probe watch directory does not exist: $dir" }
    when (mode) {
      MODE_FILE_WATCH_SERVICE -> FileWatchService(dir).startWatching()
      MODE_LOGS_REPO -> LogsRepo(dir, watchFileSystem = true)
      else -> error("Unknown probe mode: $mode")
    }
    check(awaitWatchThread()) {
      "No $WATCH_THREAD_NAME_PREFIX thread is running for $dir, so this probe would prove nothing " +
        "about whether a watcher holds the JVM open."
    }
  }

  private fun awaitWatchThread(): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WATCH_THREAD_WAIT_SECONDS)
    while (true) {
      if (Thread.getAllStackTraces().keys.any { it.name.startsWith(WATCH_THREAD_NAME_PREFIX) }) {
        return true
      }
      if (System.nanoTime() >= deadline) return false
      Thread.sleep(50)
    }
  }
}
