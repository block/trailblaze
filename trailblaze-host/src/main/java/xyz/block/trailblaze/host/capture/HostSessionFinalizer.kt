package xyz.block.trailblaze.host.capture

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.host.animations.SessionAnimationDisabler
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.host.networkcapture.WebCompanionNetworkCapture
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.SessionCleanups
import xyz.block.trailblaze.util.Console

/** Prevents artifact publication before downstream session resources are durable. */
fun interface HostSessionFinalizer {
  fun finalizeSession(sessionId: SessionId)
}

/** Keeps downstream cleanup pluggable without coupling OSS session management to integrations. */
object HostSessionFinalizerRegistry {
  private val finalizers = CopyOnWriteArrayList<HostSessionFinalizer>()

  fun register(finalizer: HostSessionFinalizer): AutoCloseable {
    finalizers += finalizer
    return AutoCloseable { finalizers -= finalizer }
  }

  fun finalizeSession(sessionId: SessionId) {
    val failures = mutableListOf<Throwable>()
    finalizers.forEach { finalizer ->
      runCatching { finalizer.finalizeSession(sessionId) }
        .onFailure {
          failures += it
          Console.log(
            "[HostSessionFinalizerRegistry] finalizer failed for $sessionId: ${it.message}"
          )
        }
    }
    if (failures.isNotEmpty()) {
      val failure =
        IllegalStateException(
          "${failures.size} host session finalizer(s) failed for $sessionId; artifacts may be incomplete.",
          failures.first(),
        )
      failures.drop(1).forEach(failure::addSuppressed)
      throw failure
    }
  }
}

/** Attempts the full barrier so one cleanup failure cannot strand other session resources. */
internal fun finalizeHostSessionResources(
  sessionIds: Iterable<SessionId>,
  stopCapture: (SessionId) -> Unit,
) {
  val failures = mutableListOf<Throwable>()
  sessionIds.distinct().forEach { sessionId ->
    // Undo calls a session registered but never ran, because it ran no trail (MCP, interactive).
    // First, while the device and any lease are still held. The session's drivers may already be
    // released (`endSessionForDevice` releases them before this barrier), so an undo that needs a
    // Maestro or Playwright driver fails here; adb-based undos don't. Logged, not failed: the
    // trail-end run reports the same failures for trails.
    runCatching {
      val failures = runBlocking { SessionCleanups.runAll(SessionCleanups.drain(sessionId)) { it.runDetached() } }
      failures.forEach { Console.log("[SessionCleanup] $sessionId: $it") }
    }.onFailure { Console.log("[SessionCleanup] $sessionId: ${it.message}") }
    runCatching { HostSessionFinalizerRegistry.finalizeSession(sessionId) }
      .onFailure(failures::add)
    // The Android network-capture activator is registered per distribution, so stop it here in
    // the shared barrier rather than trusting every entry point to register a finalizer for it.
    // Distributions that also register it as a HostSessionFinalizer stop it twice: a no-op when
    // healthy, though a retained failure then appears twice in this barrier's failure list.
    runCatching { AndroidNetworkCaptureRegistry.activator?.stop(sessionId.value) }
      .onFailure {
        failures += it
        Console.log(
          "[HostSessionFinalizerRegistry] network capture stop failed for $sessionId: ${it.message}"
        )
      }
    // A multi-device session's web devices borrow their browsers, so nothing else stops them.
    // Logged, not failed: unlike Android capture, a browser's stream is diagnostics rather than
    // evidence a run is judged on, and a browser that closed or crashed mid-run fails its stop.
    runCatching { WebCompanionNetworkCapture.stop(sessionId.value) }
      .onFailure {
        Console.log(
          "[HostSessionFinalizerRegistry] web network capture stop failed for $sessionId: ${it.message}"
        )
      }
    // Same rationale as network capture: restore in the shared barrier rather than trusting every
    // entry point to pair its own restore with the start. No-op for sessions that never disabled
    // animations. Runs while capture stops below, since neither waits on the other; the barrier
    // still returns only once both are done, so the next run on the device finds them restored.
    val restoreFailure = AtomicReference<Throwable?>()
    val restore = Thread(
      { runCatching { SessionAnimationDisabler.restoreForSession(sessionId) }.onFailure(restoreFailure::set) },
      "session-animation-restore",
    ).apply {
      isDaemon = true
      start()
    }
    val captureStopFailure = runCatching { stopCapture(sessionId) }.exceptionOrNull()
    joinUninterruptibly(restore)
    restoreFailure.get()?.let {
      failures += it
      Console.log(
        "[HostSessionFinalizerRegistry] animation restore failed for $sessionId: ${it.message}"
      )
    }
    captureStopFailure?.let {
      failures += it
      Console.log(
        "[HostSessionFinalizerRegistry] capture stop failed for $sessionId: ${it.message}"
      )
    }
  }
  if (failures.isNotEmpty()) {
    val failure = failures.first()
    failures.drop(1).forEach(failure::addSuppressed)
    throw failure
  }
}

/** Joins [thread], keeping an interrupt for the caller rather than abandoning the join. */
private fun joinUninterruptibly(thread: Thread) {
  var interrupted = false
  while (true) {
    try {
      thread.join()
      break
    } catch (e: InterruptedException) {
      interrupted = true
    }
  }
  if (interrupted) Thread.currentThread().interrupt()
}
