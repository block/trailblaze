package xyz.block.trailblaze.toolcalls

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.job

/**
 * Carries a trail's [Job] across the blocking tool-dispatch call, so a tool dispatched from a
 * suspending loop is cancelled with its trail. The runner installs it for the whole trail as a
 * coroutine context [element], which follows the trail across threads; a scripted tool's nested
 * calls install their own tool call's Job the same way. The dispatch opens its own `runBlocking`, which
 * inherits no Job from the caller; without this, a cancelled trail leaves a waiting host-local tool
 * running until the wait gives up on its own.
 *
 * Links cancellation only: the dispatch is not a child of the trail's Job, so a tool that throws
 * fails its own call, never the trail.
 */
object DispatchCancellation {
  private val trailJob = ThreadLocal<Job?>()

  /** [job] as the Job a dispatch is cancelled with, on whatever thread the coroutine resumes on. */
  fun element(job: Job?): ThreadContextElement<Job?> = trailJob.asContextElement(job)

  /** Runs [block] on this thread with [job] as the Job a dispatch inside it is cancelled with. */
  fun <T> withTrailJob(job: Job?, block: () -> T): T {
    val previous = trailJob.get()
    trailJob.set(job)
    try {
      return block()
    } finally {
      trailJob.set(previous)
    }
  }

  /** Runs [block] in [scope], cancelling the scope when the installed trail Job is cancelled. */
  suspend fun <T> linked(scope: CoroutineScope, block: suspend () -> T): T = linked(scope, trailJob.get(), block)

  /** Runs [block] in [scope], cancelling the scope when [owner] is cancelled or finishes first. */
  suspend fun <T> linked(scope: CoroutineScope, owner: Job?, block: suspend () -> T): T {
    if (owner == null) return block()
    val dispatch = scope.coroutineContext.job
    // A child of the owner is cancelled the moment the owner is; completing it normally afterwards
    // leaves the owner alone.
    val link = Job(owner)
    val handle = link.invokeOnCompletion { cause ->
      if (cause != null) dispatch.cancel(CancellationException("the calling tool was cancelled", cause))
    }
    try {
      return block()
    } finally {
      handle.dispose()
      link.complete()
    }
  }
}
