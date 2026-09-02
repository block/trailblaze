package xyz.block.trailblaze.inprocess

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Copies an APK entry by entry, swapping some entries and adding others.
 *
 * This is where APK zip hygiene lives, not in the signer:
 *
 * - **`resources.arsc` and the native libraries under `lib/` must stay `STORED`.** API 30+ requires
 *   the resource table uncompressed and refuses to install otherwise, and native libraries are
 *   mapped rather than read. A rewrite that re-compresses them installs on some API levels and fails
 *   on others, which reads as a flaky device rather than a broken tool. Every entry keeps its source
 *   method.
 * - **The previous signature has to go.** The `META-INF` signature entries (`.SF`, `.RSA`, `.DSA`,
 *   `.EC`) and `MANIFEST.MF` describe the bytes as they were; leaving them makes the APK look signed
 *   and fail verification.
 *
 * Alignment is the signer's job: `ApkSigner` re-aligns on output by default. See [ApkSigning].
 */
object ApkZipRewriter {

  private val SIGNATURE_ENTRY = Regex("^META-INF/([^/]+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)$")

  /** Reads one entry out of [apk], or null when it has no such entry. */
  fun readEntry(apk: File, name: String): ByteArray? = ZipFile(apk).use { zip ->
    zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }
  }

  fun entryNames(apk: File): List<String> = ZipFile(apk).use { zip ->
    zip.entries().toList().map { it.name }
  }

  /**
   * Runs [predicate] over each entry's name and its first [prefixBytes] bytes, returning the names
   * it accepted.
   *
   * One [ZipFile] for the whole scan, and never more than [prefixBytes] decompressed per entry — a
   * per-entry [readEntry] would re-parse the central directory and inflate every dex and native
   * library in a hundreds-of-megabyte APK to look at four bytes.
   */
  fun entryNamesMatching(apk: File, prefixBytes: Int, predicate: (String, ByteArray) -> Boolean): List<String> =
    ZipFile(apk).use { zip ->
      zip.entries().toList().filter { entry ->
        val prefix = if (entry.isDirectory) {
          ByteArray(0)
        } else {
          zip.getInputStream(entry).use { it.readNBytes(prefixBytes) }
        }
        predicate(entry.name, prefix)
      }.map { it.name }
    }

  /**
   * Writes [output] from [input] with [replacements] substituted by entry name and [additions]
   * appended. Adding an entry that already exists fails rather than producing a zip with two
   * entries of the same name — which resolves differently depending on which reader opens it.
   */
  fun rewrite(
    input: File,
    output: File,
    replacements: Map<String, ByteArray> = emptyMap(),
    additions: Map<String, ByteArray> = emptyMap(),
  ) {
    output.parentFile?.mkdirs()
    ZipFile(input).use { zip ->
      val entries = zip.entries().toList()
      val existing = entries.map { it.name }.toSet()
      replacements.keys.forEach { name ->
        require(name in existing) { "$input has no entry named $name to replace" }
      }
      additions.keys.forEach { name ->
        require(name !in existing) {
          "$input already carries $name. Injecting it would leave two entries with the same name, " +
            "and which one a reader sees is not defined."
        }
      }
      ZipOutputStream(output.outputStream().buffered()).use { out ->
        for (entry in entries) {
          if (SIGNATURE_ENTRY.matches(entry.name)) continue
          val replacement = replacements[entry.name]
          val data = replacement ?: zip.getInputStream(entry).use { it.readBytes() }
          out.putNextEntry(storedAwareEntry(entry.name, entry.method, entry.time, data))
          out.write(data)
          out.closeEntry()
        }
        for ((name, data) in additions) {
          out.putNextEntry(storedAwareEntry(name, ZipEntry.DEFLATED, ADDED_ENTRY_TIME, data))
          out.write(data)
          out.closeEntry()
        }
      }
    }
  }

  /**
   * Timestamp stamped on injected entries. Fixed, because the default is `System.currentTimeMillis()`
   * and that alone makes two runs over identical inputs produce byte-different APKs — which costs the
   * one property that makes a handed-around artifact checkable against its inputs.
   *
   * 1980-01-01, the zero of the MS-DOS date field a zip entry stores, so no reader has to represent
   * anything unusual.
   */
  private val ADDED_ENTRY_TIME: Long = java.util.GregorianCalendar(1980, 0, 1, 0, 0, 0).timeInMillis

  /** A `STORED` entry needs its size and CRC set before the first write; a deflated one does not. */
  private fun storedAwareEntry(name: String, method: Int, time: Long?, data: ByteArray): ZipEntry =
    ZipEntry(name).apply {
      this.method = method
      time?.let { this.time = it }
      if (method == ZipEntry.STORED) {
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        crc = CRC32().apply { update(data) }.value
      }
    }
}
