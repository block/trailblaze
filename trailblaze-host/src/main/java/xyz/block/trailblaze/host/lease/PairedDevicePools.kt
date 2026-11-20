package xyz.block.trailblaze.host.lease

import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The lending daemons this machine has paired with (`trailblaze device pair`), one URL per line in
 * the Trailblaze home directory. The daemon re-reads it on every device listing, so pairing takes
 * effect without a restart.
 */
class PairedDevicePools(
  private val file: File = File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), FILE_NAME),
) {

  fun urls(): List<String> =
    if (file.isFile) RemoteDevicePools.parseUrls(file.readLines().joinToString(",")) else emptyList()

  /** Adds [url]; false when it was already paired. */
  fun add(url: String): Boolean {
    val current = urls()
    if (url in current) return false
    write(current + url)
    return true
  }

  /** Removes [url]; false when it was not paired. */
  fun remove(url: String): Boolean {
    val current = urls()
    if (url !in current) return false
    write(current - url)
    return true
  }

  private fun write(urls: List<String>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.${ProcessHandle.current().pid()}.tmp")
    tmp.writeText(urls.joinToString("\n", postfix = if (urls.isEmpty()) "" else "\n"))
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  companion object {
    const val FILE_NAME = "device-pools.txt"

    /**
     * [url] as stored: `http(s)://host[:port]` without a trailing slash. Null when it is not such a
     * URL, so the caller can say what it expected.
     */
    fun normalize(url: String): String? {
      val trimmed = url.trim().trimEnd('/')
      val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
      if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
      if (!uri.path.isNullOrEmpty() || uri.query != null) return null
      return trimmed
    }
  }
}
