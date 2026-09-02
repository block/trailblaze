package xyz.block.trailblaze.inprocess.apk

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Read-only view of an APK as the zip archive it is.
 *
 * An APK large enough to matter here (a large first-party debug build we probe is over half a
 * gigabyte across 59 dex files) must never be held in memory whole, so every accessor either names
 * one entry or streams. [forEachDex] in particular reads one dex at a time and lets it go.
 */
internal class ApkArchive private constructor(
  val file: File,
  private val zip: ZipFile,
) : Closeable {

  companion object {
    /**
     * Opens [file], or throws [ApkReadException] naming it — callers surface that message to a
     * human, so "not a zip" has to say which file and what was expected.
     */
    fun open(file: File): ApkArchive {
      if (!file.isFile) {
        throw ApkReadException("$file is not a file, so there is no APK to read.")
      }
      // Every IOException, not just ZipException: a file the agent cannot read throws plain
      // IOException, and letting that escape exits 1 ("this app is unfit") for what is really a
      // permission on a CI agent.
      val zip = try {
        ZipFile(file)
      } catch (e: IOException) {
        throw ApkReadException(
          "$file is not a readable zip archive, so it cannot be an APK (${e.message}).",
          e,
        )
      }
      return ApkArchive(file, zip)
    }
  }

  /** Every entry name in the archive, in central-directory order. */
  fun entryNames(): List<String> = zip.entries().asSequence().map { it.name }.toList()

  /** Bytes of [name], or null when the archive has no such entry. */
  fun bytes(name: String): ByteArray? {
    val entry = zip.getEntry(name) ?: return null
    return zip.getInputStream(entry).use { it.readBytes() }
  }

  /**
   * Text of [name] trimmed, or null when absent. The `META-INF/<group>_<artifact>.version` files
   * AGP packages are one short line each.
   */
  fun text(name: String): String? = bytes(name)?.toString(Charsets.UTF_8)?.trim()

  /**
   * Invokes [block] once per `classes*.dex` entry with that dex's bytes, releasing each before
   * reading the next.
   */
  fun forEachDex(block: (name: String, bytes: ByteArray) -> Unit) {
    val dexNames = entryNames().filter { it.matches(DEX_ENTRY) }.sorted()
    for (name in dexNames) {
      val bytes = bytes(name) ?: continue
      block(name, bytes)
    }
  }

  override fun close() = zip.close()
}

/** Top-level dex entries only — a nested `assets/foo/classes.dex` is some other APK's payload. */
private val DEX_ENTRY = Regex("""classes\d*\.dex""")

/** An APK could not be read far enough to describe it. Message is meant for a human. */
class ApkReadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
