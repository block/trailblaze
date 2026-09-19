package xyz.block.trailblaze.cli

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * A daemon that exits but is never reaped by its parent stays in the process table as a zombie, and
 * both of the signals the startup claim reads still answer for one: `ProcessHandle.isAlive` says
 * `true`, and `ps -o lstart=` still reports its start time — so the identity check confirms the
 * defunct owner and the claim is never released. Every later command then waits on a daemon that
 * cannot arrive, and a zombie cannot be killed, so the only way out is deleting the claim by hand.
 *
 * This is the Kotlin half of the bug that was fixed in the launcher script, `scripts/trailblaze`;
 * that fix changed no Kotlin, so this path — the one the CLI's own auto-start `cliTryStartDaemon`
 * takes — still believed a zombie.
 *
 * Asserted through the observable contracts rather than the private liveness helper: a claim held by
 * a defunct owner must be reclaimable, and the watcher that owns that claim must stop watching. The
 * live-owner control lives in `CompanionCommandTest` — a helper that always answered "dead" would
 * fail it by electing a second starter past a running one, and by releasing a live child's claim.
 */
class DaemonStartupClaimZombieTest {
  @Test
  fun `a claim held by a defunct owner is reclaimed, not waited on`() {
    withZombie { zombiePid, identity ->
      val dir = createTempDirectory("daemon-claim-zombie").toFile()
      try {
        val pidFile = dir.resolve("daemon-52525.pid.starting")
        assertTrue(pidFile.mkdirs(), "could not stage the claim directory")
        pidFile.resolve("owner-${UUID.randomUUID()}").writeText("$zombiePid\n$identity\n")

        val claim = claimDaemonStartup(pidFile)
        assertIs<DaemonStartupClaim.Owner>(
          claim,
          "a claim whose owner is defunct must be reclaimed, not reported as still starting",
        )
        assertEquals(
          ProcessHandle.current().pid().toString(),
          claim.ownerFile.readLines().first(),
          "the reclaiming process must publish itself as the new claimant",
        )
      } finally {
        dir.deleteRecursively()
      }
    }
  }

  /**
   * The claim is removed by a watcher the claimant itself spawned, so a zombie is exactly the case
   * where nobody else is positioned to release it: the parent that has not reaped the child is the
   * same process that owns the watcher. A watcher that reads `kill -0` plus a start time watches a
   * zombie forever, which pins the claim for every other CLI on this machine.
   */
  @Test
  fun `the startup claim reaper releases a defunct child's claim`() {
    withZombie { zombiePid, _ ->
      val dir = createTempDirectory("daemon-reaper-zombie").toFile()
      try {
        val pidFile = dir.resolve("daemon-52525.pid.starting")
        assertTrue(pidFile.mkdirs(), "could not stage the claim directory")
        val ownerFile = pidFile.resolve("owner-${UUID.randomUUID()}")
        ownerFile.writeText("$zombiePid\n${psField("lstart=", zombiePid)}\n")

        val reaper = assertNotNull(
          scheduleDaemonStartupClaimReaper(pidFile, ownerFile, zombiePid),
          "the reaper must still be schedulable for a PID `ps` can identify",
        )
        assertTrue(
          reaper.waitFor(30, SECONDS),
          "the reaper is still watching a process that has exited",
        )
        assertEquals(0, reaper.exitValue())
        assertFalse(pidFile.exists(), "a defunct child's claim must not outlive it")
      } finally {
        dir.deleteRecursively()
      }
    }
  }

  /**
   * The case a zombie fix cannot reach: a claimant that is genuinely alive and permanently wedged.
   * Nothing this CLI can do clears it, so the whole value of the refusal is the escape route it
   * prints — which process to look at, which signal actually ends it, and why deleting the claim
   * file is the wrong move. A refusal that named none of those would leave the user re-running a
   * command that waits out its full budget every time, with no way to find the cause.
   */
  @Test
  fun `a refused startup claim names the blocking process and the escape route`() {
    val claimFile = File("/tmp/trailblaze-test/daemon-52525.pid.starting")

    val reason = heldStartupClaimReason(claimantPid = 4242, port = 52525)
    val hint = heldStartupClaimHint(claimantPid = 4242, claimFile = claimFile)

    assertContains(reason, "4242", message = "the refusal must name the process that is blocking")
    assertContains(reason, "52525", message = "the refusal must name the port that stayed unserved")
    assertContains(hint, "kill -9 4242", message = "a plain kill cannot end a wedged shutdown hook")
    assertContains(
      hint,
      claimFile.absolutePath,
      message = "the user cannot inspect or clear a claim whose path is never printed",
    )
  }

  /**
   * The opposite failure to the zombie one, and the more damaging of the two: reclaiming a claim
   * that a **live** starter still holds. The identity probe is bounded, so it can come back empty
   * on a loaded machine — and an empty answer says nothing about who the process is, only that `ps`
   * did not finish. The PID is alive either way.
   *
   * Reading that as "not the claimant" deletes a live starter's owner file and elects a second
   * daemon against it, which is precisely what the claim exists to prevent. Holding the claim too
   * long is the recoverable direction: the refusal names the PID and the signal that ends it.
   */
  @Test
  fun `an unreadable identity retains the claim, and a mismatched one releases it`() {
    val recorded = "Thu Sep 11 09:14:02 2026"

    assertTrue(
      daemonStartupClaimStillHeld(probedIdentity = null, recordedIdentity = recorded),
      "a probe that timed out must leave a live claimant's claim alone, not elect a second starter",
    )
    assertTrue(
      daemonStartupClaimStillHeld(probedIdentity = recorded, recordedIdentity = recorded),
      "the claimant's own identity must keep its claim",
    )
    // The control: a positive, readable disagreement is the one case that DOES mean PID reuse, and
    // it has to still release — or the unknown-is-held rule above would have made the check inert.
    assertFalse(
      daemonStartupClaimStillHeld(probedIdentity = "Thu Sep 11 11:47:31 2026", recordedIdentity = recorded),
      "a different process on a recycled PID must not inherit the claim",
    )
  }

  /**
   * The PID a waiting CLI read at election time is not necessarily the one holding the claim when
   * the wait ends. The starter publishes its own PID, then hands the claim to the daemon child it
   * spawns — so a CLI that arrived during that window is holding the starter's PID. The refusal
   * tells the user to `kill -9` whatever it names, and naming the wrong process there is how a
   * stall turns into a killed sibling CLI while the real holder keeps the claim.
   */
  @Test
  fun `the refusal names the current claim holder, not the PID read before the handoff`() {
    val dir = createTempDirectory("daemon-claim-handoff").toFile()
    try {
      val pidFile = dir.resolve("daemon-52525.pid.starting")
      assertTrue(pidFile.mkdirs(), "could not stage the claim directory")
      val ownerFile = pidFile.resolve("owner-${UUID.randomUUID()}")
      ownerFile.writeText("9001\nThu Sep 11 09:14:02 2026\n")

      assertEquals(
        9001L,
        startupClaimHolderPid(pidFile, observedPid = 4242),
        "the holder is whoever the claim file names now, not the PID observed before the handoff",
      )

      // The claim being gone is the one case where there is no holder to re-read. The observed PID
      // is then the only identification left, so it must still be reported rather than dropped.
      assertTrue(pidFile.deleteRecursively(), "could not clear the staged claim")
      assertEquals(
        4242L,
        startupClaimHolderPid(pidFile, observedPid = 4242),
        "a released claim must fall back to the observed PID, not lose the process entirely",
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  /**
   * A parent that `exec`s away can no longer reap, so its already-exited child stays defunct for the
   * parent's lifetime — the state a desktop-app JVM leaves an exited daemon child in. Portable POSIX
   * shell on purpose: no interpreter beyond the `sh` and `ps` the rest of this claim suite uses.
   */
  private fun withZombie(body: (pid: Long, identity: String) -> Unit) {
    assumeTrue(
      "zombie process states, `ps -o stat=` and POSIX `sh` are not available on Windows",
      !System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    )
    val fixture = ProcessBuilder("sh", "-c", "sleep 0 & printf '%s\\n' \$!; exec sleep 600")
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    try {
      val zombiePid = fixture.inputStream.bufferedReader().readLine()?.trim()?.toLongOrNull()
      assertTrue(zombiePid != null, "the zombie fixture never reported a child PID")

      // Without a real zombie every assertion below is vacuous, so this is a failure, not a skip.
      // Bounded only to contain a hang — the fork and its exit are immediate.
      val deadline = System.nanoTime() + SECONDS.toNanos(30)
      var status = psField("stat=", zombiePid)
      while (!status.startsWith("Z") && System.nanoTime() < deadline) {
        Thread.sleep(50)
        status = psField("stat=", zombiePid)
      }
      assertTrue(
        status.startsWith("Z"),
        "PID $zombiePid never became defunct (stat='$status'); this case would be unfalsifiable",
      )

      // The identity the production code reads, written verbatim into the claim. If this were a
      // value `processStartIdentity` would not produce, the claim would be reclaimed for a
      // mismatched identity instead of a defunct process, and the case would pass without the fix.
      val identity = psField("lstart=", zombiePid)
      assertTrue(
        identity.isNotBlank(),
        "`ps -o lstart=` must still answer for a zombie, or this case tests the wrong rejection",
      )

      body(zombiePid, identity)
    } finally {
      fixture.destroyForcibly()
      fixture.waitFor()
    }
  }

  /** `ps` is how the production liveness and identity probes read both fields; match them. */
  private fun psField(field: String, pid: Long): String = runCatching {
    val process = ProcessBuilder("ps", "-o", field, "-p", pid.toString())
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .also { it.environment()["LC_ALL"] = "C" }
      .start()
    val value = process.inputStream.bufferedReader().use { it.readText().trim() }
    process.waitFor()
    value
  }.getOrElse { "" }
}
