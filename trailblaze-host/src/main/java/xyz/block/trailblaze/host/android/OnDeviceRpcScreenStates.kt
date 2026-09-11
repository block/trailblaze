package xyz.block.trailblaze.host.android

import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.transport.AndroidWireTransport
import xyz.block.trailblaze.util.Console
import java.util.Base64

/**
 * Captures a screen by asking the on-device RPC server for it, or null when the capture fails.
 *
 * Shared by all three Android descriptors: the device hosts the RPC server, so what differs
 * between them is the wire transport that server accepts — [AndroidWireTransport.modeFor] answers
 * that from [driverType] — not how the host asks.
 *
 * Returns null rather than throwing on RPC failure because the caller is a screen-state read that
 * degrades to "no screen available"; a capture that cannot happen is not a reason to fail the
 * surface asking for it.
 */
internal suspend fun onDeviceRpcScreenState(
  trailblazeDeviceId: TrailblazeDeviceId,
  driverType: TrailblazeDriverType,
): ScreenState? {
  return try {
    // Closed on every exit: the client owns a WebSocket and an HTTP engine, and this helper runs
    // on every host-side screen-state read, so one leaked client per capture accumulates
    // connections for the life of the daemon.
    OnDeviceRpcClient(
      trailblazeDeviceId = trailblazeDeviceId,
      sendProgressMessage = { },
      wireTransportMode = AndroidWireTransport.modeFor(driverType),
    ).use { rpcClient ->
      val request = GetScreenStateRequest(includeScreenshot = true)
        .withScreenshotScalingConfig(EffectiveScreenshotScalingConfig.effective)

      when (val result = rpcClient.rpcCall(request)) {
        is RpcResult.Success -> {
          val response = result.data
          val screenshotBytes = response.screenshotBytes ?: response.screenshotBase64?.let {
            Base64.getDecoder().decode(it)
          }

          object : ScreenState {
            override val screenshotBytes: ByteArray? = screenshotBytes
            override val deviceWidth: Int = response.deviceWidth
            override val deviceHeight: Int = response.deviceHeight
            override val viewHierarchy: ViewHierarchyTreeNode = response.viewHierarchy
            override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
              trailblazeDeviceId.trailblazeDevicePlatform
            override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
          }
        }
        is RpcResult.Failure -> {
          Console.log("❌ Failed to get screen state via RPC: ${result.message}")
          null
        }
      }
    }
  } catch (e: CancellationException) {
    // A capture is cancellable work; swallowing this leaves the caller's coroutine looking
    // alive and makes daemon shutdown unresponsive.
    throw e
  } catch (e: Exception) {
    Console.log("❌ Exception getting screen state via RPC: ${e.message}")
    e.printStackTrace()
    null
  }
}
