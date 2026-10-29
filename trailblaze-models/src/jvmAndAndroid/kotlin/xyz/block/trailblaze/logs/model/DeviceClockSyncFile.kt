package xyz.block.trailblaze.logs.model

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Reads and appends a session's [DeviceClockSync.FILE_NAME].
 *
 * Appends read-modify-write under one process-wide lock and publish through a rename, because a
 * multi-device session samples each device from its own thread into the same file, and a reader
 * polling the session must never see a half-written one.
 */
object DeviceClockSyncFile {

  private val LOCK = Any()

  /**
   * A day of 60s samples on a few devices is well under 1 MB. Anything past this is not a file this
   * code wrote, and an imported session must not make every events poll load it.
   */
  const val MAX_BYTES: Long = 4L * 1024 * 1024

  /**
   * The session's measured clock readings, or null when it took none, or the file is damaged or
   * over [MAX_BYTES].
   */
  fun read(sessionDir: File): DeviceClockSync? {
    val file = File(sessionDir, DeviceClockSync.FILE_NAME)
    if (!file.isFile || file.length() > MAX_BYTES) return null
    return runCatching { DeviceClockSync.decodeOrNull(file.readText()) }.getOrNull()
  }

  fun appendSample(sessionDir: File, deviceId: String, sample: DeviceClockSample) {
    update(sessionDir, deviceId) { it.copy(samples = it.samples + sample) }
  }

  fun appendFailure(sessionDir: File, deviceId: String, failure: DeviceClockReadFailure) {
    update(sessionDir, deviceId) { it.copy(failures = it.failures + failure) }
  }

  /**
   * Records the names of a multi-device session's devices, and which one it started on, so a
   * reader can attribute each device's `<stream>.<name>` capture to that device's clock. A named
   * device with no samples yet still claims its streams, so they are never read on another
   * device's clock.
   */
  fun nameDevices(sessionDir: File, startDeviceId: String, namesByDeviceId: Map<String, String>) {
    synchronized(LOCK) {
      var sync = current(sessionDir).copy(startDeviceId = startDeviceId)
      namesByDeviceId.forEach { (deviceId, name) ->
        sync = sync.withTrack(deviceId) { it.copy(deviceName = name) }
      }
      write(sessionDir, sync)
    }
  }

  private fun update(sessionDir: File, deviceId: String, change: (DeviceClockTrack) -> DeviceClockTrack) {
    synchronized(LOCK) {
      write(sessionDir, current(sessionDir).withTrack(deviceId, change))
    }
  }

  /**
   * What an update starts from. A file over [MAX_BYTES] is refused rather than read as empty,
   * which would overwrite its history. A damaged one starts over: there is nothing in it to keep.
   */
  private fun current(sessionDir: File): DeviceClockSync {
    val file = File(sessionDir, DeviceClockSync.FILE_NAME)
    check(!file.isFile || file.length() <= MAX_BYTES) {
      "${DeviceClockSync.FILE_NAME} is over $MAX_BYTES bytes; leaving it as it is"
    }
    return read(sessionDir) ?: DeviceClockSync()
  }

  private fun DeviceClockSync.withTrack(
    deviceId: String,
    change: (DeviceClockTrack) -> DeviceClockTrack,
  ): DeviceClockSync {
    val existing = devices.firstOrNull { it.deviceId == deviceId }
    val updated = change(existing ?: DeviceClockTrack(deviceId))
    return copy(
      devices =
        if (existing == null) devices + updated
        else devices.map { if (it.deviceId == deviceId) updated else it }
    )
  }

  private fun write(sessionDir: File, sync: DeviceClockSync) {
    synchronized(LOCK) {
      if (!sessionDir.isDirectory) sessionDir.mkdirs()
      val target = File(sessionDir, DeviceClockSync.FILE_NAME)
      val temp = File(sessionDir, "${DeviceClockSync.FILE_NAME}.tmp")
      val bytes = DeviceClockSync.encode(sync).toByteArray(Charsets.UTF_8)
      // Readers skip a file past the cap, so publishing one would hide every sample in it.
      check(bytes.size <= MAX_BYTES) {
        "${DeviceClockSync.FILE_NAME} would pass $MAX_BYTES bytes; keeping the previous file"
      }
      temp.writeBytes(bytes)
      // Replaces in one step, so a failed publish leaves the previous file whole.
      try {
        try {
          Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
          Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
        }
      } finally {
        temp.delete()
      }
    }
  }
}
