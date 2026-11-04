package xyz.block.trailblaze.toolcalls

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext

/** One whole trail execution, independent of the recording session and individual tool calls. */
class TrailExecution(val trailId: String?) {
  private val completion: CompletableJob = Job()
  val isFinished: Boolean
    get() = completion.isCompleted

  /** Runs after the owner has finished every cleanup that can still dispatch tools. */
  fun invokeOnCompletion(callback: () -> Unit) {
    completion.invokeOnCompletion { callback() }
  }

  /** Only the whole-execution owner calls this, after runtime and app cleanup. */
  fun finish() {
    completion.complete()
  }

  companion object {
    private val active = ThreadLocal<TrailExecution?>()

    fun current(): TrailExecution? = active.get()

    fun element(execution: TrailExecution? = current()) = active.asContextElement(execution)

    /** An outer runner owns completion; nested item loops inherit that same handle. */
    suspend fun <T> within(trailId: String?, block: suspend () -> T): T {
      if (current() != null) return block()
      return run(trailId, block)
    }

    suspend fun <T> run(trailId: String?, block: suspend () -> T): T {
      val execution = TrailExecution(trailId)
      var originalFailure: Throwable? = null
      return try {
        withContext(active.asContextElement(execution)) {
          try {
            block()
          } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
          }
        }
      } catch (failure: Throwable) {
        // Coroutine stack recovery may copy a Throwable at this added context boundary.
        // Preserve the runner's existing failure identity and cancellation semantics.
        val original = originalFailure
        throw if (original != null && (failure === original || failure.cause === original)) {
          original
        } else {
          failure
        }
      } finally {
        // withContext has joined owned children, including their cleanup, before completing.
        execution.finish()
      }
    }

    fun <T> withinBlocking(trailId: String?, block: () -> T): T {
      if (current() != null) return block()
      val execution = TrailExecution(trailId)
      active.set(execution)
      try {
        return block()
      } finally {
        execution.finish()
        active.remove()
      }
    }
  }
}
