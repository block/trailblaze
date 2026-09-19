package xyz.block.trailblaze.scripting.subprocess

import xyz.block.trailblaze.util.Console
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards the one resource a subprocess MCP session consumes for its entire lifetime: a permit on
 * `Dispatchers.IO`.
 *
 * The MCP SDK's `StdioClientTransport` reads its subprocess's stdout with a **blocking**
 * `readAtMostTo`, and it launches that read on `Dispatchers.IO` — a hardcoded internal choice
 * (`getIODispatcher()`), not something a caller can inject. A blocking read parks its thread until
 * the subprocess writes, so **every live session holds one IO permit from handshake to shutdown**,
 * whether or not it is doing any work.
 *
 * `Dispatchers.IO` is capped at `max(64, availableProcessors)`. Inline scripted tools are
 * synthesized one subprocess per distinct author file ([InlineScriptToolServerSynthesizer]), so a
 * target with 64 tool files parks 64 permits and there are none left — for anything. The daemon's
 * own request handlers need IO to touch files, `adb`, and sockets, so they stop being scheduled and
 * every route hangs forever. The daemon looks healthy throughout: `/ping` is answered on the Netty
 * event loop, which needs no IO permit, so it keeps returning 200 while nothing else completes.
 *
 * That failure is invisible from the outside and impossible to diagnose without a thread dump, so
 * the fix has two halves and this object is the second one:
 *
 *  1. The launcher script raises the cap (`-Dkotlinx.coroutines.io.parallelism`) far above the
 *     number of tools any target declares, which is what the knob exists for. That is set before
 *     the JVM starts, because the property is read once when `Dispatchers.IO` is first touched.
 *  2. [reserve] refuses a launch that provably cannot fit, naming the numbers. Any embedding that
 *     does not come through the launcher — a test, an Android host, a bare `java -jar` — still
 *     gets a legible error instead of a silent permanent hang.
 *
 * The permit pool is per-JVM, so the accounting is too. A daemon holds one runtime per live MCP
 * session and a multi-target run launches one per plan entry, so checking each launch against the
 * *whole* cap would admit any number of batches that individually fit and collectively exhaust it —
 * which is the state this exists to make impossible. [reserve] therefore counts against a
 * process-wide tally that is only returned when a runtime shuts down.
 */
object SubprocessIoCapacity {
  /**
   * IO permits held back for the host's own blocking work while subprocess sessions are live:
   * `adb` calls, session-log writes, screenshot capture, and the daemon's own HTTP handlers.
   *
   * Sized as headroom rather than a measured figure — the host's concurrent blocking work is
   * bursty and not enumerable here. The point is that "every permit is spoken for by a transport"
   * must never be reachable, since that is the state that hangs the daemon.
   */
  const val HOST_RESERVED_IO_PERMITS: Int = 16

  /** The property `kotlinx.coroutines.io.parallelism` is read from, mirrored here to compute the cap. */
  const val IO_PARALLELISM_PROPERTY: String = "kotlinx.coroutines.io.parallelism"

  /** Live subprocess permits across every runtime in this JVM. Returned by [SubprocessIoReservation.release]. */
  private val outstandingPermits = AtomicInteger(0)

  /** Subprocess permits currently reserved process-wide. Diagnostics and tests. */
  fun outstandingPermits(): Int = outstandingPermits.get()

  /**
   * The effective `Dispatchers.IO` parallelism for this JVM.
   *
   * Mirrors kotlinx.coroutines' own resolution — the [IO_PARALLELISM_PROPERTY] override, else
   * `max(64, availableProcessors)`. Mirrored rather than queried because the value is not exposed
   * by any public API, and a wrong mirror is visible in the error text this feeds rather than
   * silently changing behavior.
   *
   * Only an ABSENT property falls back. Anything else is parsed exactly the way kotlinx parses it,
   * including the cases that look like nothing: `systemProp` reads the raw value and calls
   * `toLongOrNull()` on it with no trimming, so `""` (what `-Dfoo=` sets), `" "`, and `" 512 "` are
   * all fatal there. Being more forgiving here would be the worse bug of the two — it would plan
   * against a 64 this JVM is never going to have, because it is going to die the moment anything
   * touches `Dispatchers.IO`. Failing here names the knob the operator actually set.
   */
  fun effectiveIoParallelism(): Int {
    // Deliberately not trimmed: see above. `" 512 "` is a value kotlinx rejects, so it must be a
    // value this rejects.
    val raw = System.getProperty(IO_PARALLELISM_PROPERTY) ?: return defaultIoParallelism()
    val parsed = raw.toIntOrNull()
    check(parsed != null && parsed >= 1) {
      val shown = if (raw.isBlank()) "'$raw' (set, but with no usable value)" else "'$raw'"
      "-D$IO_PARALLELISM_PROPERTY is $shown, which kotlinx.coroutines rejects: it must be an " +
        "integer >= 1. This JVM will fail with \"System property '$IO_PARALLELISM_PROPERTY' has " +
        "unrecognized value\" as soon as anything touches Dispatchers.IO. If the `trailblaze` " +
        "launcher started it, TRAILBLAZE_IO_PARALLELISM is where the value came from."
    }
    return parsed
  }

  private fun defaultIoParallelism(): Int = maxOf(64, Runtime.getRuntime().availableProcessors())

  /**
   * Claim [subprocessCount] IO permits for a runtime that is about to spawn that many subprocess
   * MCP sessions, or refuse when they would not fit alongside what other runtimes already hold.
   *
   * The returned [SubprocessIoReservation] must be released when the runtime shuts down — including
   * on a partial-launch failure — or the tally leaks and later launches are refused against permits
   * nothing is using.
   *
   * Throws rather than degrading because [McpSubprocessRuntimeLauncher.launchAll] is already
   * fail-fast: a partial tool set is worse than a named startup failure, and the alternative here
   * is not a partial tool set but a daemon that stops answering.
   */
  fun reserve(
    subprocessCount: Int,
    /** The JVM's cap. Resolved from the property when absent — but only if anything is launched. */
    ioParallelism: Int? = null,
  ): SubprocessIoReservation {
    require(subprocessCount >= 0) { "subprocessCount must be >= 0, got $subprocessCount" }
    // Before resolving the cap, so a session with no scripted tools is not failed by a malformed
    // property it was never going to consult.
    if (subprocessCount == 0) return SubprocessIoReservation(0)
    val cap = ioParallelism ?: effectiveIoParallelism()
    val budget = cap - HOST_RESERVED_IO_PERMITS
    while (true) {
      val held = outstandingPermits.get()
      val total = held + subprocessCount
      if (total > budget) {
        throw SubprocessIoCapacityException(
          subprocessCount = subprocessCount,
          alreadyHeldPermits = held,
          ioParallelism = cap,
          reserved = HOST_RESERVED_IO_PERMITS,
        )
      }
      if (outstandingPermits.compareAndSet(held, total)) {
        // Warn while it still works. Crossing halfway into the budget is the last observable
        // moment before a launch starts being refused, and it is worth a line in the log because
        // the count that matters is process-wide — invisible from any one session's point of view.
        if (total > budget / 2) {
          Console.error(
            "[SubprocessIoCapacity] $total scripted-tool subprocesses are live across this JVM, " +
              "each holding one Dispatchers.IO permit for its whole session, out of $budget " +
              "available ($cap total, $HOST_RESERVED_IO_PERMITS reserved for the host). " +
              "Raise -D$IO_PARALLELISM_PROPERTY before adding many more tools or sessions.",
          )
        }
        return SubprocessIoReservation(subprocessCount)
      }
    }
  }

  internal fun releasePermits(permits: Int) {
    if (permits > 0) outstandingPermits.addAndGet(-permits)
  }
}

/**
 * A claim on [permits] `Dispatchers.IO` permits, held for as long as the subprocess sessions that
 * park them are alive.
 *
 * Releasing is idempotent, and idempotent *per child*: teardown paths overlap (a partial-launch
 * failure unwinds, and the caller may still call `shutdownAll`), and double-releasing would hand
 * the tally permits that are still parked — the one error that makes the guard admit a launch it
 * should refuse. A count alone cannot deliver that, because the two ways a permit comes back — a
 * teardown that finds the child already dead, and that same child's exit callback — would each
 * return one for the same child, and the extra one belongs to a sibling that is still parking it.
 * So the accounting is by process identity, and both paths go through the same book.
 */
class SubprocessIoReservation internal constructor(val permits: Int) {
  /**
   * Guards [outstanding], [parked] and [refundOnExit] as one fact: how many permits this
   * reservation still counts, and which children are parking them. Held only by teardown paths and
   * exit callbacks, never while blocking.
   */
  private val lock = Any()

  /** Permits this reservation still counts against the JVM-wide tally. Only ever decreases. */
  private var outstanding: Int = permits

  /**
   * The children whose permits make up [outstanding] once teardown has named survivors. `Process`
   * has no `equals`, so this is identity-keyed — exactly what is wanted, since the question is
   * whether THIS child's permit is still counted.
   *
   * Empty until [releaseAllBut] names them: before teardown a reservation covers *entries*, some of
   * which may not have spawned a process at all.
   */
  private val parked = mutableSetOf<Process>()

  /** Whether [parked] has been populated, so an empty set means "nothing survives" and not "unknown". */
  private var survivorsNamed = false

  /** Children already signed up for an on-exit refund, so a second ask adds no second callback. */
  private val refundOnExit = mutableSetOf<Process>()

  /** Permits this reservation still holds. Diagnostics and tests. */
  fun outstanding(): Int = synchronized(lock) { outstanding }

  /**
   * Give [process]'s permit back once the OS reaps it — at most once, however many times this is
   * asked, and never if a teardown got there first.
   *
   * Teardowns overlap: a partial-launch unwind and a later `shutdownAll` both see the same
   * still-alive child and both want its permit reclaimed. Ignores a child whose permit this
   * reservation no longer counts, because there is nothing left to give back for it and the permit
   * that would come back is a live sibling's.
   */
  fun refundWhenExited(process: Process) {
    val register = synchronized(lock) { process in parked && refundOnExit.add(process) }
    if (register) process.onExit().thenRun { refundParked(process) }
  }

  /** Return every permit. Only correct once every subprocess this reservation covered has exited. */
  fun release() = releaseAllBut(emptyList())

  /**
   * Return the permit of a child that has now exited, unless a teardown already returned it.
   *
   * Reclaims the permit of a child that outlived SIGKILL's wait and was therefore kept counted at
   * teardown: without this the tally would never free itself, and a daemon that hit the case a few
   * times would refuse launches forever against permits nothing holds.
   */
  private fun refundParked(process: Process) {
    val refund = synchronized(lock) {
      when {
        // A teardown that ran after this child died already gave its permit back.
        !parked.remove(process) -> false
        outstanding == 0 -> false
        else -> {
          outstanding -= 1
          true
        }
      }
    }
    if (refund) SubprocessIoCapacity.releasePermits(1)
  }

  /**
   * Return every permit except the ones [survivors] are still parking — children that are alive
   * after teardown. A permit a live transport is parking is not free, whatever the runtime's
   * lifecycle says: handing it back would let a later launch be admitted against IO capacity that
   * does not exist, which is the hang the tally exists to prevent.
   *
   * Repeated calls never release more than the first did. A survivor of an earlier call that has
   * since exited is released here; one whose permit already came back through [refundWhenExited] is
   * not released twice, because it is no longer in the book. Naming a child that is not in the book
   * cannot re-reserve a permit that was already returned.
   */
  fun releaseAllBut(survivors: List<Process>) {
    val released = synchronized(lock) {
      val keep = if (survivorsNamed) survivors.filterTo(mutableSetOf()) { it in parked } else survivors.toMutableSet()
      survivorsNamed = true
      parked.clear()
      parked.addAll(keep)
      val next = minOf(outstanding, keep.size)
      val released = outstanding - next
      outstanding = next
      released
    }
    if (released > 0) SubprocessIoCapacity.releasePermits(released)
  }
}

/**
 * Thrown when the subprocess sessions a launch needs would not fit in what is left of this JVM's
 * `Dispatchers.IO` permits.
 *
 * The message carries every number that went into the decision and the knob that changes it,
 * because the symptom it replaces — a daemon answering `/ping` while every other route hangs
 * forever — points at nothing.
 */
class SubprocessIoCapacityException(
  val subprocessCount: Int,
  val alreadyHeldPermits: Int,
  val ioParallelism: Int,
  val reserved: Int,
) : IllegalStateException(
  buildString {
    val budget = ioParallelism - reserved
    append("Refusing to launch $subprocessCount scripted-tool subprocesses: each one holds a ")
    append("Dispatchers.IO permit for its whole lifetime. This JVM has $ioParallelism permits; ")
    append("$reserved are held back for the host's own adb, file, and HTTP work")
    if (alreadyHeldPermits > 0) {
      append(" and $alreadyHeldPermits are already held by live sessions")
    }
    append(", leaving ${(budget - alreadyHeldPermits).coerceAtLeast(0)} for this launch. ")
    append("Going past that does not fail — it eats the headroom the host needs to answer at ")
    append("all, and enough of it hangs every request while /ping keeps returning 200. Raise the ")
    append("cap with -D${SubprocessIoCapacity.IO_PARALLELISM_PROPERTY}=<n> (the `trailblaze` ")
    append("launcher sets this from TRAILBLAZE_IO_PARALLELISM; a bare `java -jar` or an embedding ")
    append("host must set it too), or declare fewer scripted tools.")
  },
)
