package xyz.block.trailblaze.ui.devices

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.ui.recording.decodeFrameBytes
import xyz.block.trailblaze.ui.theme.TrailblazeTheme
import xyz.block.trailblaze.util.Console

/**
 * Cadence hint for the per-tile [RemoteDeviceScreenStream]. Slower than the single-device
 * detail view (80 ms) on purpose — N simultaneous full-fps streams would burn host capture
 * cycles for thumbnails the user is glancing at. The daemon's idle-clamp already converges
 * to ~1 s when nothing changes, so this also keeps thumbnails of static screens cheap.
 */
private const val GRID_FRAME_INTERVAL_MS: Long = 1_000L

/**
 * Min cell width — drives the column count on [GridCells.Adaptive]. Tuned bigger than the
 * old 240.dp default so a typical 1440px window lays out roughly four tiles per row at a
 * size where status bars and primary content are actually legible. Combined with square
 * tiles ([TILE_ASPECT]), this is the "easier to see what's happening" knob.
 */
private val TILE_MIN_WIDTH = 360.dp

/**
 * Aspect ratio of every preview box. Squares (1:1) so the grid stays uniform regardless
 * of whether a device is portrait (phones), landscape (web), or near-square (tablets).
 * Per-device frames are fitted into the square via [ContentScale.Fit], so the device's
 * own aspect is preserved — just with letterbox/pillarbox bars when the frame doesn't
 * match. The visual cost is real (phones get ~30% horizontal padding) but the win is a
 * tidy grid that doesn't reflow as devices come and go.
 */
private const val TILE_ASPECT: Float = 1f

/**
 * Page-lifetime coroutine scope for fire-and-forget tile cleanup. Cannot use
 * `rememberCoroutineScope()` because it cancels at the same `onDispose` we'd launch from —
 * the cleanup coroutine would be cancelled before `disconnectDevice` ever suspended, leaving
 * the daemon-side capture session running until the browser tab closes. Mirrors the
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` pattern used in
 * [xyz.block.trailblaze.ui.devices.HostRpcWsClient].
 */
private val tileCleanupScope: CoroutineScope =
  CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Multi-device live grid served at `/devices/all`.
 *
 * Fetches the device list from the daemon, then renders one tile per device. Every tile
 * owns its own [HostRpcClient] so the daemon's per-session "currently-connected device"
 * binding never collides — connecting deviceB on the same client that already connected
 * deviceA replaces the binding for deviceA, which made `SubscribeFramesRequest` fail
 * with "Device not connected" on the prior device. One client per tile = one WS session
 * per tile = independent bindings.
 *
 * **Long-term**: this per-tile-client workaround should go away once the daemon supports
 * multi-device subscriptions on a single WS session — then a page-level client could
 * subscribe to N devices and demultiplex frames by `trailblazeDeviceId`.
 *
 * Click a tile to drop into the single-device picker at `/devices` (deep-linking by id is
 * a follow-up — `/devices` still uses an in-page dropdown). Tiles in the `Unavailable`
 * state are non-interactive — clicking them would land on a dead `/devices` picker with
 * no device context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDevicesGridPage() {
  // A dedicated client for the cheap "list" RPC only. Per-tile clients handle their own
  // connect/subscribe so this one is never used for anything device-bound.
  val listClient = remember { HostRpcClient() }
  var loading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var devices by remember { mutableStateOf<List<TrailblazeConnectedDeviceSummary>>(emptyList()) }

  LaunchedEffect(Unit) {
    val response = listClient.getConnectedDevices()
    if (response == null) {
      errorMessage = "Could not reach Trailblaze daemon. Is it running?"
    } else {
      devices = response.devices
    }
    loading = false
  }

  // Close the page-level list client when the page leaves composition. It never connects
  // to a device so there's no `disconnectDevice` to call — just the Ktor `HttpClient` to
  // release. Done on a non-composition scope for the same reason as the per-tile cleanup.
  DisposableEffect(listClient) {
    onDispose {
      tileCleanupScope.launch {
        runCatching { listClient.close() }
          .onFailure {
            Console.log("[WebDevicesGridPage] listClient close failed: ${it.message}")
          }
      }
    }
  }

  TrailblazeTheme {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
          title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Trailblaze", style = MaterialTheme.typography.titleLarge)
              Spacer(modifier = Modifier.padding(horizontal = 4.dp))
              Text(
                text = "All devices",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
          ),
        )

        when {
          loading -> CenteredSpinner("Loading devices…")
          errorMessage != null -> CenteredMessage(errorMessage!!)
          devices.isEmpty() -> CenteredMessage("No devices connected. Connect a device and refresh.")
          else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(
              items = devices,
              key = { it.trailblazeDeviceId.toFullyQualifiedDeviceId() },
            ) { device ->
              DeviceTile(device = device)
            }
          }
        }
      }
    }
  }
}

/** Per-tile lifecycle status. */
private sealed interface TileStatus {
  /** Connecting to the device — show a spinner. */
  data object Connecting : TileStatus

  /** Connected and subscribed; frames are flowing (or about to). */
  data object Streaming : TileStatus

  /** The connect call failed or returned null — show "Preview unavailable". */
  data class Unavailable(val reason: String?) : TileStatus
}

/**
 * Single device tile. Owns its own [HostRpcClient] for the lifetime of the composition,
 * so the WS session binding can't collide with siblings. The client is leaked on dispose
 * (no public close on the JS side) — the WS socket is closed by GC when the page navigates
 * away, and `disconnectDevice` is best-effort to free the daemon-side capture session
 * sooner.
 */
@Composable
private fun DeviceTile(device: TrailblazeConnectedDeviceSummary) {
  val client = remember(device.trailblazeDeviceId) { HostRpcClient() }
  var status by remember(device.trailblazeDeviceId) {
    mutableStateOf<TileStatus>(TileStatus.Connecting)
  }
  var bitmap by remember(device.trailblazeDeviceId) { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(device.trailblazeDeviceId) {
    val connectResponse = try {
      client.connectToDevice(device.trailblazeDeviceId)
    } catch (e: Exception) {
      Console.log("[WebDevicesGridPage] connect threw for ${device.description}: ${e.message}")
      null
    }
    if (connectResponse == null) {
      // `lastErrorMessage` is set by the RPC layer on transport errors but can be null
      // on a clean "request returned null" path (rare but possible). Fall back to a
      // generic reason so the tile never renders "Preview unavailable" with no detail.
      status = TileStatus.Unavailable(
        reason = client.lastErrorMessage?.takeIf { it.isNotBlank() } ?: "Failed to connect",
      )
      return@LaunchedEffect
    }
    val stream = RemoteDeviceScreenStream(
      client = client,
      trailblazeDeviceId = device.trailblazeDeviceId,
      deviceWidth = connectResponse.deviceWidth,
      deviceHeight = connectResponse.deviceHeight,
      frameIntervalMs = GRID_FRAME_INTERVAL_MS,
    )
    status = TileStatus.Streaming
    // Wrap the collect so a mid-stream WS failure (daemon restart, device unplug, socket
    // close) flips the tile to Unavailable instead of silently freezing on the last frame.
    // The stream's HTTP-fallback path can already throw past its internal handling.
    try {
      stream.frames().collect { bytes ->
        val decoded = bytes.decodeFrameBytes()
        if (decoded != null) bitmap = decoded
      }
    } catch (e: kotlinx.coroutines.CancellationException) {
      // Composition leaving — propagate so structured cancellation cleans up.
      throw e
    } catch (e: Exception) {
      Console.log("[WebDevicesGridPage] frame stream ended for ${device.description}: ${e.message}")
      status = TileStatus.Unavailable(reason = e.message ?: "Connection lost")
    }
  }

  DisposableEffect(device.trailblazeDeviceId) {
    onDispose {
      // Launch on [tileCleanupScope] — NOT `rememberCoroutineScope()`, which cancels at
      // this same onDispose and would kill the coroutine before `disconnectDevice` even
      // suspended (#3448 review feedback). The cleanup pair is:
      //   1) `disconnectDevice` so the daemon-side capture session releases sooner than
      //      it would on socket-close,
      //   2) `client.close()` to release the per-tile Ktor `HttpClient` + WS client
      //      (which itself cancels its read loop and any pending RPC) — otherwise these
      //      resources only get GC'd whenever the JS runtime feels like it.
      tileCleanupScope.launch {
        runCatching { client.disconnectDevice(device.trailblazeDeviceId) }
          .onFailure {
            Console.log("[WebDevicesGridPage] disconnect failed: ${it.message}")
          }
        runCatching { client.close() }
          .onFailure {
            Console.log("[WebDevicesGridPage] client close failed: ${it.message}")
          }
      }
    }
  }

  // Bind `status` to a local so the conditional click handler reads a stable value (avoids
  // smart-cast issues on the delegate-backed property and keeps the click target consistent
  // for the lifetime of this composition pass).
  val tileStatus = status
  val cardModifier = Modifier.fillMaxWidth().let { base ->
    // Unavailable tiles are non-interactive — clicking them would land on the bare
    // `/devices` picker with no device context, which is just a confusing dead-end.
    if (tileStatus is TileStatus.Unavailable) {
      base
    } else {
      base.clickable {
        // Drop into the single-device picker — deep-linking by device id is a follow-up.
        window.location.assign("/devices")
      }
    }
  }
  Card(
    modifier = cardModifier,
    shape = RoundedCornerShape(10.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(modifier = Modifier.padding(8.dp)) {
      Text(
        text = device.description,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = device.instanceId,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(TILE_ASPECT)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
      ) {
        val frame = bitmap
        // `tileStatus` is captured once at the top of this composition pass so smart-cast
        // on the `Unavailable` branch flows through to its `reason` access.
        if (frame != null) {
          Image(
            bitmap = frame,
            contentDescription = "${device.description} live preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
          )
        } else if (tileStatus is TileStatus.Unavailable) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
          ) {
            Text(
              text = "Preview unavailable",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val reason = tileStatus.reason
            if (!reason.isNullOrBlank()) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
        } else {
          CircularProgressIndicator(strokeWidth = 2.dp)
        }
      }
    }
  }
}

@Composable
private fun CenteredSpinner(label: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator()
      Spacer(modifier = Modifier.height(12.dp))
      Text(text = label)
    }
  }
}

@Composable
private fun CenteredMessage(message: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(24.dp),
    )
  }
}

