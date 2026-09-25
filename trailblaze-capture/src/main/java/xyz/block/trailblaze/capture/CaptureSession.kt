package xyz.block.trailblaze.capture

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console

/**
 * Orchestrates multiple [CaptureStream]s for a single test session.
 *
 * Start all streams before test execution, stop all after. Artifacts are saved alongside session
 * logs.
 */
class CaptureSession internal constructor(
  private val streams: List<CaptureStream>,
  private val options: CaptureOptions,
  private val platform: TrailblazeDevicePlatform?,
) {

  constructor(streams: List<CaptureStream>, options: CaptureOptions) : this(streams, options, null)

  fun startAll(sessionDir: File, deviceId: String, appId: String?) {
    for (stream in streams) {
      try {
        Console.log("Starting ${stream.type} capture...")
        stream.start(sessionDir, deviceId, appId)
      } catch (e: Exception) {
        Console.log("Failed to start ${stream.type} capture: ${e.message}")
      }
    }
  }

  /**
   * Tells every [ToolCallAwareCaptureStream] that a top-level tool is about to run / has just
   * returned. Failures are logged, never propagated: a capture must not fail a tool call.
   */
  fun onToolCall(phase: ToolCallPhase, toolName: String, traceId: String? = null) {
    for (stream in streams) {
      if (stream !is ToolCallAwareCaptureStream) continue
      try {
        stream.onToolCall(phase, toolName, traceId)
      } catch (e: Exception) {
        Console.log("Failed to sample ${stream.type} capture ${phase.name.lowercase()} $toolName: ${e.message}")
      }
    }
  }

  fun stopAll(): List<CaptureArtifact> {
    val artifacts = mutableListOf<CaptureArtifact>()
    var appScopedDeviceLog: CaptureArtifact? = null
    for (stream in streams) {
      try {
        Console.log("Stopping ${stream.type} capture...")
        stream.stop(options)?.let { artifact ->
          // A stream can finalize a file and still have captured nothing — the recorder's output
          // is created when it is spawned, not when the first byte arrives. Registering an empty
          // file gives the report a clip it cannot decode and ships a dead artifact in the session
          // zip, so the file goes and the artifact is never listed. The session keeps its
          // screenshots, which is what a session with no footage should show.
          if (!artifact.file.isFile || artifact.file.length() == 0L) {
            Console.log("${stream.type} captured nothing; discarding empty ${artifact.file.name}")
            artifact.file.delete()
            return@let
          }
          artifacts.add(artifact)
          if (
            artifact.type == xyz.block.trailblaze.capture.model.CaptureType.LOGCAT &&
            stream is AppScopedCaptureStream &&
            stream.isAppScoped
          ) {
            appScopedDeviceLog = artifact
          }
          Console.log(
            "${stream.type} captured: ${artifact.file.name} (${artifact.file.length() / 1024}KB)"
          )
        }
      } catch (e: Exception) {
        Console.log("Failed to stop ${stream.type} capture: ${e.message}")
      }
    }
    // Write metadata for timeline integration
    if (artifacts.isNotEmpty()) {
      writeCaptureMetadata(artifacts)
      writeCrashEvents(appScopedDeviceLog)
    }
    return artifacts
  }

  private fun writeCrashEvents(deviceLogArtifact: CaptureArtifact?) {
    val devicePlatform = platform ?: return
    if (devicePlatform != TrailblazeDevicePlatform.ANDROID && devicePlatform != TrailblazeDevicePlatform.IOS) {
      return
    }
    val deviceLog = deviceLogArtifact?.file ?: return
    val sessionDir = deviceLog.parentFile ?: return
    xyz.block.trailblaze.capture.logcat.CrashEventArtifactWriter.write(
      sessionDir = sessionDir,
      deviceLog = deviceLog,
      platform = devicePlatform,
    )
  }

  private fun writeCaptureMetadata(artifacts: List<CaptureArtifact>) {
    val sessionDir = artifacts.first().file.parentFile ?: return
    val metadata =
      CaptureMetadata(
        artifacts =
          artifacts.map { artifact ->
            CaptureMetadata.ArtifactEntry(
              filename = artifact.file.name,
              type = artifact.type.name,
              startTimestampMs = artifact.startTimestampMs,
              endTimestampMs = artifact.endTimestampMs,
            )
          }
      )
    val metadataFile = File(sessionDir, "capture_metadata.json")
    val json = Json { prettyPrint = true }
    metadataFile.writeText(json.encodeToString(CaptureMetadata.serializer(), metadata))
  }

  companion object {
    /**
     * Creates a [CaptureSession] from [CaptureOptions], building the appropriate platform-specific
     * streams.
     *
     * @param platform The device platform, used to select the correct platform-specific
     *   capture implementation. Pass `null` for platforms that don't support capture.
     * @param iosVideoStreamOverride Replaces the default iOS video recorder ([IosVideoCapture],
     *   simctl) when non-null. The host module injects a baguette-stream recorder here — it can't
     *   be built in this module because it depends on host-only baguette plumbing. Ignored on
     *   non-iOS platforms and when video capture is off.
     * @param androidMemoryProbeOverride Replaces the adb-based memory probe on Android when
     *   non-null. The host module injects a probe that asks the on-device instrumentation runner
     *   first (no adb round trips) and falls back to adb — it can't be built here because the RPC
     *   client lives in the host. Ignored on non-Android platforms and when memory capture is off.
     */
    fun fromOptions(
      options: CaptureOptions,
      platform: TrailblazeDevicePlatform?,
      iosVideoStreamOverride: CaptureStream? = null,
      androidMemoryProbeOverride: xyz.block.trailblaze.capture.memory.MemoryProbe? = null,
    ): CaptureSession? {
      if (!options.hasAnyCaptureEnabled) return null
      val streams = mutableListOf<CaptureStream>()
      if (options.captureVideo) {
        when (platform) {
          TrailblazeDevicePlatform.ANDROID ->
            streams.add(xyz.block.trailblaze.capture.video.AndroidVideoCapture())
          TrailblazeDevicePlatform.IOS ->
            streams.add(iosVideoStreamOverride ?: xyz.block.trailblaze.capture.video.IosVideoCapture())
          TrailblazeDevicePlatform.WEB ->
            // Record from the live CDP screencast (Android's one-encoder model), falling back to
            // Playwright's setRecordVideoDir recorder when no screencast feed is registered for the
            // device (e.g. the report-export path).
            streams.add(
              xyz.block.trailblaze.capture.video.WebScreencastVideoCapture(
                fallback = xyz.block.trailblaze.capture.video.PlaywrightVideoCapture(),
              ),
            )
          else -> Unit
        }
      }
      if (options.captureLogcat && platform == TrailblazeDevicePlatform.ANDROID) {
        streams.add(xyz.block.trailblaze.capture.logcat.AndroidLogcatCapture())
      }
      if (options.captureIosLogs && platform == TrailblazeDevicePlatform.IOS) {
        streams.add(xyz.block.trailblaze.capture.logcat.IosLogCapture())
      }
      // The environment kill-switch is read here, where memory capture actually starts, so it
      // covers every route in — see [CaptureOptions.ENV_CAPTURE_MEMORY].
      val memoryOffInEnv = options.captureMemory && CaptureOptions.memoryCaptureDisabledInEnv()
      if (memoryOffInEnv) {
        Console.log("[memory-capture] off: ${CaptureOptions.ENV_CAPTURE_MEMORY} is set to a falsey value")
      }
      if (options.captureMemory && !memoryOffInEnv) {
        when (platform) {
          TrailblazeDevicePlatform.ANDROID ->
            streams.add(
              xyz.block.trailblaze.capture.memory.MemoryCapture(
                probe = androidMemoryProbeOverride ?: xyz.block.trailblaze.capture.memory.AdbMemoryProbe(),
                forceGc = options.memoryDiagnostics,
                synchronousToolSamples = options.memoryDiagnostics,
              ),
            )
          TrailblazeDevicePlatform.IOS ->
            streams.add(
              xyz.block.trailblaze.capture.memory.MemoryCapture(
                probe = xyz.block.trailblaze.capture.memory.IosSimulatorMemoryProbe(),
                synchronousToolSamples = options.memoryDiagnostics,
              ),
            )
          else -> Unit
        }
      }
      if (streams.isEmpty()) return null
      return CaptureSession(streams, options, platform)
    }
  }
}

@Serializable
data class CaptureMetadata(val artifacts: List<ArtifactEntry>) {
  @Serializable
  data class ArtifactEntry(
    val filename: String,
    val type: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long?,
  )
}
