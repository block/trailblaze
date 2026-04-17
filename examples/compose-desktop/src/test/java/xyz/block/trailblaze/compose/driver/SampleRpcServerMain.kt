package xyz.block.trailblaze.compose.driver
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.INFINITE
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
  val sampleApp = args.firstOrNull()?.lowercase() ?: "todo"
  TrailblazeSerializationInitializer.initialize()
  runComposeUiTest(testTimeout = INFINITE) {
    when (sampleApp) {
      "todo" -> setContent { SampleTodoApp() }
      "showcase" -> setContent { SampleWidgetShowcase() }
      else -> error("Unknown sample app '$sampleApp'. Use 'todo' or 'showcase'.")
    }
    waitForIdle()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = ComposeRpcServer.COMPOSE_DEFAULT_PORT)
    server.start(wait = false)
    val previewWindow = createPreviewWindow(sampleApp)
    val previewThread =
      startPreviewPolling(
        label = previewWindow?.second,
        rpcPort = ComposeRpcServer.COMPOSE_DEFAULT_PORT,
      )
    Console.log("Compose sample app '$sampleApp' is running with RPC on port ${ComposeRpcServer.COMPOSE_DEFAULT_PORT}")
    Console.log("Launch Trailblaze desktop with: ./gradlew :trailblaze-desktop:run")
    Console.log("Then select the Compose (RPC) target in Trailblaze.")
    try {
      while (true) {
        Thread.sleep(60_000)
      }
    } finally {
      previewThread?.interrupt()
      previewWindow?.first?.dispose()
      server.stop()
    }
  }
}
private fun createPreviewWindow(sampleApp: String): Pair<JFrame, JLabel>? {
  if (GraphicsEnvironment.isHeadless()) {
    Console.log("Headless environment detected, skipping preview window.")
    return null
  }
  val label = JLabel("Waiting for first frame...")
  val panel = JPanel(BorderLayout())
  panel.add(JScrollPane(label), BorderLayout.CENTER)
  val frame = JFrame("Compose Sample Preview ($sampleApp)")
  frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
  frame.contentPane = panel
  frame.setSize(1100, 850)
  frame.setLocationRelativeTo(null)
  frame.addWindowListener(
    object : WindowAdapter() {
      override fun windowClosing(e: WindowEvent?) {
        System.exit(0)
      }
    },
  )
  SwingUtilities.invokeLater { frame.isVisible = true }
  return frame to label
}
private fun startPreviewPolling(
  label: JLabel?,
  rpcPort: Int,
): Thread? {
  if (label == null) return null
  val thread = Thread {
    val client = ComposeRpcClient("http://localhost:$rpcPort")
    try {
      while (!Thread.currentThread().isInterrupted) {
        val response = runBlocking { client.getScreenState() }
        if (response is RpcResult.Success) {
          response.data.screenshotBase64?.let { screenshotBase64 ->
            val bytes = Base64.getDecoder().decode(screenshotBase64)
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            if (image != null) {
              SwingUtilities.invokeLater {
                label.icon = ImageIcon(image)
                label.text = null
              }
            }
          }
        }
        Thread.sleep(250)
      }
    } catch (_: InterruptedException) {
      // Exit quietly when shutting down.
    } finally {
      client.close()
    }
  }
  thread.isDaemon = true
  thread.name = "compose-preview-poller"
  thread.start()
  return thread
}