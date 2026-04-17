package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Ignore
import kotlin.test.Test
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.util.Console

/**
 * Starts the [SampleTodoApp] with an embedded [ComposeRpcServer] on the default compose port.
 *
 * Run this test, then in a separate terminal execute:
 * ```
 * ./trailblaze trail opensource/trails/compose-desktop/test-add-todo/desktop.trail.yaml --use-recorded-steps
 * ```
 */
@Ignore("Interactive server launchers for local CLI testing — not meant for CI")
@OptIn(ExperimentalTestApi::class)
class ComposeRpcServerLauncher {

  init {
    TrailblazeSerializationInitializer.initialize()
  }

  @Test
  fun `start SampleTodoApp server`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = ComposeRpcServer.COMPOSE_DEFAULT_PORT)
    server.start(wait = false)

    Console.log("ComposeRpcServer started on port ${ComposeRpcServer.COMPOSE_DEFAULT_PORT}")
    Console.log("Ready for CLI connections. Press Ctrl+C to stop.")

    // Keep the server alive for CLI testing (5 minutes max)
    Thread.sleep(5 * 60 * 1000L)

    server.stop()
  }

  @Test
  fun `start SampleWidgetShowcase server`() = runComposeUiTest {
    setContent { SampleWidgetShowcase() }
    waitForIdle()

    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = ComposeRpcServer.COMPOSE_DEFAULT_PORT)
    server.start(wait = false)

    Console.log("ComposeRpcServer (WidgetShowcase) started on port ${ComposeRpcServer.COMPOSE_DEFAULT_PORT}")
    Console.log("Ready for CLI connections. Press Ctrl+C to stop.")

    // Keep the server alive for CLI testing (5 minutes max)
    Thread.sleep(5 * 60 * 1000L)

    server.stop()
  }
}
