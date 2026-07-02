package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.util.Console
import java.io.File

object ExtraTrailRoots {

  private val storeFile: File = File(System.getProperty("user.home"), ".trailblaze/trailrunner-trail-roots.json")
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

  @Volatile
  private var cached: List<String>? = null

  @Synchronized
  fun list(): List<String> {
    cached?.let { return it }
    val loaded = readFromDisk()
    cached = loaded
    return loaded
  }

  @Synchronized
  fun add(path: String): List<String> {
    val canonical = canonicalize(path) ?: return list()
    val current = list()
    if (canonical in current) return current
    val next = current + canonical
    persist(next)
    return next
  }

  @Synchronized
  fun remove(path: String): List<String> {
    val target = canonicalize(path) ?: path
    val current = list()
    val next = current.filterNot { stored ->
      stored == path || stored == target || (canonicalize(stored) ?: stored) == target
    }
    if (next.size == current.size) return current
    persist(next)
    return next
  }

  private fun canonicalize(path: String): String? = try {
    File(path).canonicalPath
  } catch (e: Exception) {
    Console.log("[ExtraTrailRoots] could not canonicalize $path: ${e.message}")
    null
  }

  private fun readFromDisk(): List<String> {
    if (!storeFile.exists()) return emptyList()
    return try {
      val body = storeFile.readText()
      if (body.isBlank()) emptyList()
      else json.decodeFromString(StoredRoots.serializer(), body).roots
    } catch (e: Exception) {
      Console.log("[ExtraTrailRoots] could not read ${storeFile.absolutePath}: ${e.message}")
      emptyList()
    }
  }

  private fun persist(roots: List<String>) {
    try {
      storeFile.parentFile?.mkdirs()
      val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
      tmp.writeText(json.encodeToString(StoredRoots.serializer(), StoredRoots(roots)))
      if (!tmp.renameTo(storeFile)) {
        storeFile.writeText(tmp.readText())
        tmp.delete()
      }
      cached = roots
    } catch (e: Exception) {
      Console.log("[ExtraTrailRoots] could not write ${storeFile.absolutePath}: ${e.message}")
    }
  }

  @Serializable
  private data class StoredRoots(val roots: List<String>)
}
