package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.util.Console
import java.io.File

object TrailFavorites {

  private val storeFile = File(System.getProperty("user.home"), ".trailblaze/trailrunner-favorites.json")
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
  fun add(id: String): List<String> {
    val current = list()
    if (id.isBlank() || id in current) return current
    val next = current + id
    persist(next)
    return next
  }

  @Synchronized
  fun remove(id: String): List<String> {
    val current = list()
    val next = current.filterNot { it == id }
    if (next.size == current.size) return current
    persist(next)
    return next
  }

  private fun readFromDisk(): List<String> {
    if (!storeFile.exists()) return emptyList()
    return try {
      val body = storeFile.readText()
      if (body.isBlank()) emptyList() else json.decodeFromString(Stored.serializer(), body).ids
    } catch (e: Exception) {
      Console.log("[TrailFavorites] could not read ${storeFile.absolutePath}: ${e.message}")
      emptyList()
    }
  }

  private fun persist(ids: List<String>) {
    try {
      storeFile.parentFile?.mkdirs()
      val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
      tmp.writeText(json.encodeToString(Stored.serializer(), Stored(ids)))
      if (!tmp.renameTo(storeFile)) {
        storeFile.writeText(tmp.readText())
        tmp.delete()
      }
      cached = ids
    } catch (e: Exception) {
      Console.log("[TrailFavorites] could not write ${storeFile.absolutePath}: ${e.message}")
    }
  }

  @Serializable
  private data class Stored(val ids: List<String>)
}
