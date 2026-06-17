package xyz.block.trailblaze.android.maestro.orchestra

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.device.Platform
import maestro.orchestra.BackPressCommand
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Guards the local divergence from upstream Maestro 2.6.1 documented in this package's README:
 * `runFlow`'s `catch (CancellationException)` routes the exception through the `exception` var so the
 * `finally`'s `exception?.let { throw it }` re-throws it BEFORE the trailing `return`. Upstream does a
 * bare `throw e` there, which the `return` in the `finally` silently swallows — making a cancelled run
 * report an ordinary completion instead of propagating cancellation to structured concurrency.
 *
 * If a future Maestro sync reverts this catch back to upstream's bare `throw e`, this test fails.
 */
class OrchestraCancellationTest {

  private fun newOrchestra(): Orchestra {
    val maestro = Maestro(
      driver = ViewHierarchyOnlyDriver(
        rootTreeNode = TreeNode(),
        deviceInfo = DeviceInfo(
          platform = Platform.ANDROID,
          widthPixels = 1080,
          heightPixels = 2400,
          widthGrid = 1080,
          heightGrid = 2400,
        ),
      ),
    )
    return Orchestra(maestro = maestro)
  }

  @Test
  fun `cancelled runFlow propagates CancellationException instead of returning normally`() {
    runBlocking {
      val orchestra = newOrchestra()
      val commands = listOf(MaestroCommand(backPressCommand = BackPressCommand()))

      var caught: CancellationException? = null
      // UNDISPATCHED runs runFlow synchronously until its first suspension — the yield() at the top of
      // executeCommands — so the coroutine is parked there (before the command runs) when we cancel it.
      val job = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
          orchestra.runFlow(commands)
        } catch (e: CancellationException) {
          caught = e
          throw e
        }
      }
      job.cancel()
      job.join()

      assertNotNull(
        caught,
        "runFlow must propagate CancellationException when its coroutine is cancelled mid-flow, " +
          "rather than swallowing it via the return in the finally block.",
      )
    }
  }
}
