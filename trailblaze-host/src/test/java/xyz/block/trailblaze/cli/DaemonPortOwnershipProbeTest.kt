package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test

/**
 * The signal the auto-start veto is built on. Two probes answer it, and each is unreliable in the
 * direction the other covers:
 *  - a completed connect proves the port is held; a failed connect proves nothing, because a
 *    listener that stops calling `accept()` fills its backlog and the kernel then drops SYNs;
 *  - a failed bind proves the port is unavailable, but not that a *listener* is what holds it, so
 *    it must not be believed until it has been given a moment to clear.
 *
 * The states those probes have to keep apart, since acting on the wrong one either stacks a second
 * daemon onto an owned port or refuses to start one on a free port.
 */
class DaemonPortOwnershipProbeTest {

  @Test
  fun `a listening socket reads as listening`() {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
      assertThat(daemonPortHold(listener.localPort)).isEqualTo(DaemonPortHold.LISTENING)
    }
  }

  @Test
  fun `a port with nothing on it reads as free`() {
    // Bind to get a port the OS just confirmed is assignable, then release it. Racy in principle
    // (something could claim it in between) but not in practice on an ephemeral port, and the
    // alternative — hardcoding a port number — is far more likely to collide on a CI agent.
    val released = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    assertThat(daemonPortHold(released)).isEqualTo(DaemonPortHold.FREE)
  }

  @Test
  fun `a listener whose accept backlog is full is not free`() {
    // The case a connect-only probe gets wrong. Once a listener stops calling accept() its backlog
    // fills, and the kernel then drops further SYNs — a connect against it times out exactly as it
    // does against an empty port. Ownership has to survive that, because a saturated backlog is
    // what a wedged daemon with hung CLI commands queued behind it actually looks like.
    //
    // Asserted as "not free" rather than as a specific answer: on a host that keeps accepting past
    // the requested backlog this reads LISTENING instead, and either answer vetoes the spawn.
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { wedged ->
      val queued = mutableListOf<Socket>()
      try {
        repeat(BACKLOG_SATURATING_CONNECTS) {
          val client = Socket()
          try {
            client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), wedged.localPort), 200)
            queued += client
          } catch (e: Exception) {
            // Already refusing connections, which is the state this test wants.
            client.close()
          }
        }
        assertThat(daemonPortHold(wedged.localPort)).isNotEqualTo(DaemonPortHold.FREE)
      } finally {
        queued.forEach { runCatching { it.close() } }
      }
    }
  }

  @Test
  fun `a port held only as an outbound source port is never reported as listening, and clears`() {
    // The daemon port sits inside the OS ephemeral range, so an unrelated outbound connection can
    // hold it as its source port. Nothing is listening on it, and this must never be confused with
    // a daemon that owns the port, because that confusion is what refuses an auto-start that
    // should have succeeded.
    //
    // Which of the two remaining answers comes back is platform-dependent, and both are
    // defensible, so neither is pinned: an established socket is not a listening one, so BSD lets
    // a server bind past it (FREE — accurate, a daemon really could start here), while Linux
    // wants SO_REUSEADDR on both sockets before it allows that and reports the port in use
    // (HELD_SILENT — which is exactly what the settle window exists to re-read).
    //
    // What IS pinned is that it does not read as a listener, and that it clears once the squatter
    // is gone. A sticky reading would refuse auto-starts long after the collision ended.
    val destination = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val squatter = Socket()
    val squatted: Int
    try {
      squatter.reuseAddress = false
      squatter.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
      squatted = squatter.localPort
      squatter.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), destination.localPort), 1_000)

      assertThat(daemonPortHold(squatted)).isNotEqualTo(DaemonPortHold.LISTENING)
    } finally {
      squatter.close()
      destination.close()
    }

    assertThat(daemonPortHold(squatted)).isEqualTo(DaemonPortHold.FREE)
  }

  @Test
  fun `a port held by a listener cannot be bound`() {
    // The fallback probe on its own, with no dependence on whether the queue happens to be full:
    // bind is the direct form of the only question the veto needs answered.
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { held ->
      assertThat(isDaemonPortBindable(held.localPort)).isFalse()
    }
  }

  @Test
  fun `a released port can be bound`() {
    val released = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    assertThat(isDaemonPortBindable(released)).isTrue()
  }

  @Test
  fun `a port left in TIME_WAIT by a closed listener reads as free`() {
    // The probe has to agree with the daemon about what "bindable" means. A listener that closes
    // with a connection established leaves the port in TIME_WAIT for 30-60s; that refuses a bind
    // made without SO_REUSEADDR and accepts one made with it. The daemon sets the option, so this
    // port is genuinely free to it — and reading it as held is what made every
    // `trailblaze app --stop` followed by a command refuse to auto-start for the linger.
    val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port = listener.localPort
    val client = Socket()
    client.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
    val accepted = listener.accept()

    // Close the listener while the connection is still open — that ordering is what puts the
    // listening port itself into TIME_WAIT rather than only the accepted socket's. And close the
    // ACCEPTED side before the client's, so the daemon-side port is the active closer: TIME_WAIT
    // belongs to whichever end sends the first FIN, so closing the client first leaves the port
    // this test is about untouched on Linux and the whole thing passes with the fix reverted.
    listener.close()
    accepted.close()
    client.close()

    assertThat(daemonPortHold(port)).isEqualTo(DaemonPortHold.FREE)
  }
}

/**
 * The settle window's own contract, with the probe injected so it is decided by the sequence of
 * readings rather than by real elapsed time.
 */
class SettleDaemonPortHoldTest {

  @Test
  fun `a held-silent port that clears is reported free`() {
    // The transient collision: an unrelated socket releases the port, so the auto-start that was
    // about to be refused should go ahead instead.
    val readings = mutableListOf(
      DaemonPortHold.HELD_SILENT,
      DaemonPortHold.HELD_SILENT,
      DaemonPortHold.FREE,
    )
    assertThat(settle(readings)).isEqualTo(DaemonPortHold.FREE)
  }

  @Test
  fun `a held-silent port that never clears stays held`() {
    // The wedged listener: it never releases the port, so the veto still stands after the window.
    assertThat(settle(List(20) { DaemonPortHold.HELD_SILENT })).isEqualTo(DaemonPortHold.HELD_SILENT)
  }

  @Test
  fun `a held-silent port that starts answering is reported listening`() {
    assertThat(settle(listOf(DaemonPortHold.HELD_SILENT, DaemonPortHold.LISTENING)))
      .isEqualTo(DaemonPortHold.LISTENING)
  }

  @Test
  fun `the budget bounds the wait even when it is not a whole number of polls`() {
    // The budget has to bound elapsed time, not just count polls. Sleeping while `now() < deadline`
    // overshoots by up to a poll on the last iteration — invisible at the production constants,
    // where 3000 is a whole multiple of 250, and a 20% overrun here.
    var clock = 0L
    var slept = 0L
    settleDaemonPortHold(
      budgetMs = 1_000,
      pollMs = 400,
      now = { clock },
      sleep = { clock += it; slept += it },
    ) { DaemonPortHold.HELD_SILENT }

    assertThat(slept).isLessThanOrEqualTo(1_000L)
  }

  @Test
  fun `time spent probing counts against the budget`() {
    // The probe is the expensive half: on the wedged listener that reaches here, `daemonPortHold`
    // spends a connect timeout per loopback family. Counting only the sleeps let a 3s budget run
    // past 5s while managing two re-reads instead of eleven — the opposite of what the budget says.
    var clock = 0L
    var probes = 0
    settleDaemonPortHold(
      budgetMs = 1_000,
      pollMs = 100,
      now = { clock },
      sleep = { clock += it },
    ) {
      probes++
      clock += 450 // a slow probe, as a wedged listener's is
      DaemonPortHold.HELD_SILENT
    }

    assertThat(clock).isLessThanOrEqualTo(1_450L) // the budget, plus the one probe it can't cut short
    assertThat(probes).isLessThanOrEqualTo(3)
  }

  @Test
  fun `an unambiguous first reading is returned without waiting`() {
    // The paths every normal command takes must not pay the settle window at all.
    var probes = 0
    var slept = 0L
    val hold = settleDaemonPortHold(
      budgetMs = 3_000,
      pollMs = 250,
      now = { 0L },
      sleep = { slept += it },
    ) {
      probes++
      DaemonPortHold.FREE
    }
    assertThat(hold).isEqualTo(DaemonPortHold.FREE)
    assertThat(probes).isEqualTo(1)
    assertThat(slept).isEqualTo(0L)
  }

  /** Drives the real loop with a virtual clock advanced by its own sleeps. */
  private fun settle(readings: List<DaemonPortHold>): DaemonPortHold {
    var clock = 0L
    val remaining = readings.toMutableList()
    return settleDaemonPortHold(
      budgetMs = 1_000,
      pollMs = 250,
      now = { clock },
      sleep = { clock += it },
    ) {
      remaining.removeFirstOrNull() ?: DaemonPortHold.HELD_SILENT
    }
  }
}

/**
 * Enough connects to overrun a backlog of 1. Kernels round the requested backlog up and allow a
 * pending connection or two beyond it, so this is deliberately larger than the number that should
 * be needed.
 */
private const val BACKLOG_SATURATING_CONNECTS = 8
