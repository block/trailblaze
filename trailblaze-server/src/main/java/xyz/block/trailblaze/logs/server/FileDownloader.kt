package xyz.block.trailblaze.logs.server

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

object FileDownloader {

  fun downloadFile(url: String): String {
    val client = OkHttpClient()

    val request = Request.Builder()
      .url(url)
      .build()
    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected response: $response")

        return response.body?.string() ?: throw IOException("Could not download $url")
      }
    } catch (e: IOException) {
      throw IOException("Could not download $url", e)
    }
  }
}
