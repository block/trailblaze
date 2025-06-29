package xyz.block.trailblaze.mcp.utils

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpRequestUtils(
  private val baseUrl: String,
) {

  // Prepare the HTTP request to send the prompt
  private val client = OkHttpClient.Builder()
    .readTimeout(300, TimeUnit.SECONDS)
    .connectTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(300, TimeUnit.SECONDS)
    .build()

  fun postRequest(jsonPostBody: String, urlPath: String): String {
    val mediaType = "application/json".toMediaTypeOrNull()
    val body = jsonPostBody.toRequestBody(mediaType)

    val request = Request.Builder()
      .url("$baseUrl$urlPath")
      .addHeader("Content-Type", "application/json")
      .post(body)
      .build()

    try {
      val response = client.newCall(request).execute()
      val responseBody = response.body?.string()
      println("Response Body: $responseBody")
      println("Response Code: ${response.code}")
      println("Response Message: ${response.message}")
      return if (!response.isSuccessful) {
        """"Unexpected code $response""""
      } else {
        "$responseBody"
      }
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device. Error: ${e.message}"
      return errorMessage
    }
  }
}
