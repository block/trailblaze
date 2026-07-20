package xyz.block.trailblaze.host.capture

import java.util.concurrent.CopyOnWriteArrayList
import xyz.block.trailblaze.host.networkcapture.AndroidNetworkCaptureRegistry
import xyz.block.trailblaze.logs.model.SessionId
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
    runCatching { stopCapture(sessionId) }
      .onFailure {
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
