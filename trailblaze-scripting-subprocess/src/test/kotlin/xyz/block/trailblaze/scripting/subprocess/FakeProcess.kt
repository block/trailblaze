package xyz.block.trailblaze.scripting.subprocess

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [Process] whose death is scripted, for teardown tests that need a child the OS does not give up
 * on demand.
 *
 * A real subprocess cannot stand in here: SIGKILL always lands on one, so "outlived SIGKILL's wait"
 * — an unreaped zombie, a child in an uninterruptible system call — is not a state a test can
 * arrange. This one stays alive until [exit] is called; [survivesSigkill] decides whether
 * [destroyForcibly] counts as that call.
 *
 * Liveness is a latch rather than a flag so the inherited [onExit] works: its default implementation
 * calls the no-argument [waitFor] on a pooled thread, which is exactly the notification the permit
 * accounting uses to reclaim a survivor's slot once the OS finally reaps it.
 */
internal class FakeProcess(
  private val survivesSigkill: Boolean,
  private val pid: Long = 424242L,
  /**
   * Hold the [onExit] notification back until [notifyExitListeners] instead of letting the
   * inherited pooled-thread wait fire it. Opens the window a real reap has and a test otherwise
   * cannot stage: the child is gone — `isAlive` already answers false — but its exit callback has
   * not run yet, so anything that consults liveness in between sees a state the callback has not
   * caught up with.
   */
  private val manualExitNotification: Boolean = false,
) : Process() {
  private val exited = CountDownLatch(1)
  private val manualExit = CompletableFuture<Process>()
  var destroyCalls: Int = 0
    private set
  var destroyForciblyCalls: Int = 0
    private set

  /** The child finally goes away — the OS reaped it, or its blocked system call returned. */
  fun exit() {
    exited.countDown()
  }

  /**
   * Run the `onExit()` callbacks of a child that has already gone away. Only for
   * [manualExitNotification]; otherwise the inherited notification fires on its own once [exit] does.
   */
  fun notifyExitListeners() {
    check(manualExitNotification) { "this FakeProcess notifies on its own; nothing to deliver" }
    manualExit.complete(this)
  }

  override fun onExit(): CompletableFuture<Process> =
    if (manualExitNotification) manualExit.copy() else super.onExit()

  override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
  override fun getInputStream(): InputStream = InputStream.nullInputStream()
  override fun getErrorStream(): InputStream = InputStream.nullInputStream()

  override fun waitFor(): Int {
    exited.await()
    return EXIT_CODE
  }

  /** Answers immediately from current state, so an escalation ladder costs a test no wall clock. */
  override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

  override fun exitValue(): Int {
    if (isAlive) throw IllegalThreadStateException("still running")
    return EXIT_CODE
  }

  override fun destroy() {
    destroyCalls++
  }

  override fun destroyForcibly(): Process {
    destroyForciblyCalls++
    if (!survivesSigkill) exit()
    return this
  }

  override fun isAlive(): Boolean = exited.count > 0L
  override fun pid(): Long = pid

  private companion object {
    /** 128 + SIGKILL, the status a shell reports for a force-killed child. */
    const val EXIT_CODE = 137
  }
}
