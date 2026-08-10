package xyz.block.trailblaze.util

import java.io.File

/**
 * Temp files for output written by CoreSimulator's own processes (SimRender etc.), not by this JVM.
 *
 * `simctl io <udid> recordVideo` and `simctl io <udid> screenshot` hand the output path to
 * CoreSimulator, whose helper processes can only write to the boot volume: a path on a mounted
 * volume (e.g. a CI workspace under `/Volumes/...`) fails with NSCocoaErrorDomain 513 /
 * OSStatus -12204 ("The file couldn't be saved because you don't have permission") even though
 * this JVM writes the same directory fine. Plain `File.createTempFile(...)` is NOT safe for these
 * outputs — it resolves against `java.io.tmpdir`, which embedders redirect (e.g. a CLI wrapper
 * running `java -Djava.io.tmpdir=<workspace>/tmp`), landing the "temp" file right back on the
 * forbidden volume.
 *
 * This helper instead resolves against the `TMPDIR` environment variable (the per-user
 * `/var/folders/...` dir, always on the boot volume on macOS, and unaffected by
 * `-Djava.io.tmpdir`), falling back to `/private/tmp` when TMPDIR is unset or unusable. Write the
 * CoreSimulator output here, then move/read it from the JVM (which can write anywhere) — see
 * `IosVideoCapture.stop()` for the move-into-session-dir pattern.
 */
object CoreSimulatorTempFiles {

  /**
   * Creates an empty temp file at a location CoreSimulator processes can write. Created via NIO
   * (owner-only 0600 permissions) rather than [File.createTempFile] (world-readable): the
   * `/private/tmp` fallback branch is shared across local users, and in-progress session video
   * shouldn't be readable by everyone on the host.
   */
  fun createTempFile(prefix: String, suffix: String): File =
    java.nio.file.Files.createTempFile(bootVolumeTempDir().toPath(), prefix, suffix).toFile()

  /**
   * The boot-volume temp dir selection: `TMPDIR` env when it names a writable directory, else
   * `/private/tmp`, else (non-macOS test environments only) the JVM temp dir. Parameterized on the
   * env value so the selection is unit-testable.
   */
  internal fun bootVolumeTempDir(tmpdirEnv: String? = System.getenv("TMPDIR")): File {
    val fromEnv = tmpdirEnv?.takeIf { it.isNotBlank() }?.let(::File)
    if (fromEnv != null && fromEnv.isDirectory && fromEnv.canWrite()) return fromEnv
    val privateTmp = File("/private/tmp")
    if (privateTmp.isDirectory && privateTmp.canWrite()) return privateTmp
    return File(System.getProperty("java.io.tmpdir"))
  }
}
