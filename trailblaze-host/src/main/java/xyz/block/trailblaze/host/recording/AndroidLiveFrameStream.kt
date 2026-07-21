package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.capture.video.AndroidVideoCapture
import xyz.block.trailblaze.capture.video.H264AccessUnit
import xyz.block.trailblaze.capture.video.H264AccessUnitConsumer
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.LiveFrameConsumer
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Streams the freshest decoded JPEGs from Android's shared H.264 screen-recording encoder.
 *
 * The callback from [LiveFrameConsumer] runs on its ffmpeg drain thread, so it must never block on
 * a slow WebSocket. A one-frame, drop-oldest channel keeps latency bounded: consumers see the most
 * recent screen rather than replaying a backlog. Both the generic `/rpc-ws` device viewer and Trail
 * Runner's recorder use this bridge so they share the same capture and backpressure behavior.
 */
internal suspend fun streamAndroidLiveJpegFrames(
  deviceId: TrailblazeDeviceId,
  deviceWidth: Int,
  deviceHeight: Int,
  onFrame: suspend (ByteArray) -> Unit,
): Nothing = coroutineScope {
  val videoSize = AndroidVideoCapture.scaleToRecordingSize(deviceWidth, deviceHeight)
  val tee = H264Tee.forDevice(deviceId, videoSize = videoSize, bitRate = "4000000")
  val outbound =
    Channel<ByteArray>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val sender = launch {
    for (frame in outbound) {
      onFrame(frame)
    }
  }
  val consumer = LiveFrameConsumer(tee = tee, onFrame = { jpeg, _ -> outbound.trySend(jpeg) })

  try {
    consumer.start()
    awaitCancellation()
  } finally {
    withContext(NonCancellable) {
      runCatching { consumer.stop() }
      outbound.close()
      sender.cancel()
    }
  }
}

/**
 * Streams complete Annex-B H.264 access units without decoding or re-encoding them on the host.
 *
 * The bounded channel preserves access-unit order and applies backpressure rather than dropping
 * predictive frames, which would make the browser decoder wait for another IDR. Under normal local
 * daemon use the WebSocket drains much faster than the 4 Mbps encoder produces data.
 */
internal suspend fun streamAndroidLiveH264AccessUnits(
  deviceId: TrailblazeDeviceId,
  deviceWidth: Int,
  deviceHeight: Int,
  onAccessUnit: suspend (H264AccessUnit) -> Unit,
): Nothing = coroutineScope {
  val videoSize = AndroidVideoCapture.scaleToRecordingSize(deviceWidth, deviceHeight)
  val tee = H264Tee.forDevice(deviceId, videoSize = videoSize, bitRate = "4000000")
  val outbound = Channel<H264AccessUnit>(capacity = H264_OUTBOUND_ACCESS_UNITS)
  val sender = launch {
    for (accessUnit in outbound) {
      onAccessUnit(accessUnit)
    }
  }
  val consumer =
    H264AccessUnitConsumer(
      tee = tee,
      // runCatching: teardown closes [outbound] before consumer.stop(), whose drain finally-block
      // flushes one last access unit through splitter.finish(). That send races the close and would
      // throw ClosedSendChannelException on the drain thread. Swallowing it is correct — the stream
      // is ending and this trailing frame has nowhere to go.
      onAccessUnit = { accessUnit -> runCatching { runBlocking { outbound.send(accessUnit) } } },
    )

  try {
    consumer.start()
    awaitCancellation()
  } finally {
    withContext(NonCancellable) {
      // Close first so a producer blocked on a full channel wakes before [stop] joins it.
      outbound.close()
      runCatching { consumer.stop() }
      sender.cancel()
    }
  }
}

private const val H264_OUTBOUND_ACCESS_UNITS = 30
