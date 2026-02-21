package xyz.block.trailblaze.host.revyl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client that communicates with a Revyl cloud device worker
 * and the Revyl backend for session provisioning and AI-powered target resolution.
 *
 * Uses the same HTTP endpoints as the revyl-cli Go implementation
 * (see revyl-cli/internal/mcp/device_session.go).
 *
 * @property apiKey Revyl API key for backend authentication.
 * @property backendBaseUrl Base URL for the Revyl backend API.
 */
class RevylWorkerClient(
  private val apiKey: String,
  private val backendBaseUrl: String = DEFAULT_BACKEND_URL,
) {

  private val json = Json { ignoreUnknownKeys = true }

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  private var currentSession: RevylSession? = null

  /**
   * Returns the currently active session, or null if none is provisioned.
   */
  fun getSession(): RevylSession? = currentSession

  // ---------------------------------------------------------------------------
  // Session lifecycle
  // ---------------------------------------------------------------------------

  /**
   * Provisions a new cloud-hosted device and polls until the worker is reachable.
   *
   * @param platform "ios" or "android".
   * @param appUrl Optional direct URL to an .apk/.ipa to install.
   * @param appLink Optional deep-link to open after launch.
   * @return The newly created [RevylSession].
   * @throws RevylApiException If provisioning fails or the worker never becomes ready.
   */
  fun startSession(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
  ): RevylSession {
    val body = buildJsonObject {
      put("platform", platform.lowercase())
      put("is_simulation", true)
      if (!appUrl.isNullOrBlank()) put("app_url", appUrl)
      if (!appLink.isNullOrBlank()) put("app_link", appLink)
    }

    val response = backendPost("/api/v1/execution/start-device", body)
    val workflowRunId = response["workflow_run_id"]?.jsonPrimitive?.content ?: ""
    if (workflowRunId.isBlank()) {
      val error = response["error"]?.jsonPrimitive?.content ?: "unknown error"
      throw RevylApiException("Failed to start device: $error")
    }

    val workerBaseUrl = pollForWorkerUrl(workflowRunId, maxWaitSeconds = 120)
    waitForDeviceReady(workerBaseUrl, maxWaitSeconds = 30)

    val viewerUrl = "$backendBaseUrl/tests/execute?workflowRunId=$workflowRunId&platform=$platform"

    val session = RevylSession(
      index = 0,
      sessionId = workflowRunId,
      workflowRunId = workflowRunId,
      workerBaseUrl = workerBaseUrl,
      viewerUrl = viewerUrl,
      platform = platform.lowercase(),
    )
    currentSession = session
    return session
  }

  /**
   * Stops (cancels) the current device session and releases cloud resources.
   *
   * @throws RevylApiException If the cancellation request fails.
   */
  fun stopSession() {
    val session = currentSession ?: return
    backendPost(
      "/cancel-device",
      buildJsonObject { put("workflow_run_id", session.workflowRunId) },
    )
    currentSession = null
  }

  // ---------------------------------------------------------------------------
  // Device actions — delegated to the worker HTTP API
  // ---------------------------------------------------------------------------

  /**
   * Captures a PNG screenshot of the current device screen.
   *
   * @return Raw PNG bytes.
   * @throws RevylApiException If no session is active or the request fails.
   */
  fun screenshot(): ByteArray {
    val session = requireSession()
    val request = Request.Builder()
      .url("${session.workerBaseUrl}/screenshot")
      .get()
      .build()

    val response = httpClient.newCall(request).execute()
    if (!response.isSuccessful) {
      throw RevylApiException("Screenshot failed: HTTP ${response.code}")
    }
    return response.body?.bytes() ?: throw RevylApiException("Screenshot returned empty body")
  }

  /**
   * Taps at the given coordinates on the device screen.
   *
   * @param x Horizontal pixel coordinate.
   * @param y Vertical pixel coordinate.
   * @throws RevylApiException If the request fails.
   */
  fun tap(x: Int, y: Int) {
    workerPost("/tap", buildJsonObject { put("x", x); put("y", y) })
  }

  /**
   * Taps a UI element identified by a natural language description
   * using the Revyl AI grounding model.
   *
   * @param target Natural language description (e.g. "Sign In button").
   * @throws RevylApiException If grounding or the tap fails.
   */
  fun tapTarget(target: String) {
    val (x, y) = resolveTarget(target)
    tap(x, y)
  }

  /**
   * Types text into the currently focused input field or a targeted element.
   *
   * @param text The text to type.
   * @param targetX Optional x coordinate to tap before typing.
   * @param targetY Optional y coordinate to tap before typing.
   * @param clearFirst If true, clears the field content before typing.
   * @throws RevylApiException If the request fails.
   */
  fun typeText(text: String, targetX: Int? = null, targetY: Int? = null, clearFirst: Boolean = false) {
    val body = buildJsonObject {
      put("text", text)
      if (targetX != null && targetY != null) {
        put("x", targetX)
        put("y", targetY)
      }
      if (clearFirst) {
        put("clear_first", true)
      }
    }
    workerPost("/input", body)
  }

  /**
   * Swipes in the given direction from the center or a specific point.
   *
   * @param direction One of "up", "down", "left", "right".
   * @param startX Optional starting x coordinate.
   * @param startY Optional starting y coordinate.
   * @throws RevylApiException If the request fails.
   */
  fun swipe(direction: String, startX: Int? = null, startY: Int? = null) {
    val body = buildJsonObject {
      put("direction", direction)
      if (startX != null) put("x", startX)
      if (startY != null) put("y", startY)
    }
    workerPost("/swipe", body)
  }

  /**
   * Long-presses at the given coordinates.
   *
   * @param x Horizontal pixel coordinate.
   * @param y Vertical pixel coordinate.
   * @param durationMs Duration of the press in milliseconds.
   * @throws RevylApiException If the request fails.
   */
  fun longPress(x: Int, y: Int, durationMs: Int = 1500) {
    val body = buildJsonObject {
      put("x", x)
      put("y", y)
      put("duration", durationMs)
    }
    workerPost("/longpress", body)
  }

  /**
   * Installs an app from a URL onto the device.
   *
   * @param appUrl Direct download URL for the .apk or .ipa.
   * @throws RevylApiException If the request fails.
   */
  fun installApp(appUrl: String) {
    workerPost("/install", buildJsonObject { put("app_url", appUrl) })
  }

  /**
   * Launches an installed app by its bundle/package ID.
   *
   * @param bundleId The app's bundle identifier (iOS) or package name (Android).
   * @throws RevylApiException If the request fails.
   */
  fun launchApp(bundleId: String) {
    workerPost("/launch", buildJsonObject { put("bundle_id", bundleId) })
  }

  /**
   * Resolves a natural language target description to screen coordinates
   * using the Revyl AI grounding model on the worker.
   *
   * Falls back to the backend grounding endpoint if the worker doesn't support
   * native target resolution.
   *
   * @param target Natural language element description (e.g. "the search bar").
   * @return Pair of (x, y) pixel coordinates.
   * @throws RevylApiException If grounding fails.
   */
  fun resolveTarget(target: String): Pair<Int, Int> {
    val session = requireSession()

    // Try worker-native grounding first
    try {
      val body = buildJsonObject { put("target", target) }
      val responseBody = workerPostRaw("/resolve_target", body)
      val parsed = json.parseToJsonElement(responseBody).jsonObject
      val x = parsed["x"]!!.jsonPrimitive.int
      val y = parsed["y"]!!.jsonPrimitive.int
      return Pair(x, y)
    } catch (_: Exception) {
      // Fall back to backend grounding
    }

    // Backend grounding fallback: send screenshot + target to the backend
    val screenshotBytes = screenshot()
    val base64Screenshot = java.util.Base64.getEncoder().encodeToString(screenshotBytes)
    val groundBody = buildJsonObject {
      put("screenshot_base64", base64Screenshot)
      put("target", target)
      put("workflow_run_id", session.workflowRunId)
    }

    val response = backendPost("/api/v1/execution/ground-element", groundBody)
    val x = response["x"]!!.jsonPrimitive.int
    val y = response["y"]!!.jsonPrimitive.int
    return Pair(x, y)
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  private fun requireSession(): RevylSession =
    currentSession ?: throw RevylApiException("No active device session. Call startSession() first.")

  private fun backendPost(path: String, body: JsonObject): JsonObject {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val request = Request.Builder()
      .url("$backendBaseUrl$path")
      .addHeader("X-API-Key", apiKey)
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(mediaType))
      .build()

    val response = httpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: "{}"
    if (!response.isSuccessful) {
      throw RevylApiException("Backend request to $path failed (HTTP ${response.code}): $responseBody")
    }
    return json.parseToJsonElement(responseBody).jsonObject
  }

  private fun workerPost(path: String, body: JsonObject) {
    val responseBody = workerPostRaw(path, body)
    if (responseBody.isNotBlank()) {
      try {
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        if (parsed.containsKey("error")) {
          throw RevylApiException("Worker action $path failed: ${parsed["error"]!!.jsonPrimitive.content}")
        }
      } catch (_: kotlinx.serialization.SerializationException) {
        // Non-JSON response is fine for success
      }
    }
  }

  private fun workerPostRaw(path: String, body: JsonObject): String {
    val session = requireSession()
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val request = Request.Builder()
      .url("${session.workerBaseUrl}$path")
      .addHeader("Content-Type", "application/json")
      .post(body.toString().toRequestBody(mediaType))
      .build()

    val response = httpClient.newCall(request).execute()
    val responseBody = response.body?.string() ?: ""
    if (!response.isSuccessful) {
      throw RevylApiException("Worker request to $path failed (HTTP ${response.code}): $responseBody")
    }
    return responseBody
  }

  /**
   * Polls the backend until a worker URL is available for the given workflow run.
   */
  private fun pollForWorkerUrl(workflowRunId: String, maxWaitSeconds: Int): String {
    val deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L)
    while (System.currentTimeMillis() < deadline) {
      try {
        val request = Request.Builder()
          .url("$backendBaseUrl/api/v1/execution/worker-ws-url/$workflowRunId")
          .addHeader("X-API-Key", apiKey)
          .get()
          .build()

        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
          val body = response.body?.string() ?: ""
          val parsed = json.parseToJsonElement(body).jsonObject
          val workerUrl = parsed["worker_url"]?.jsonPrimitive?.content ?: ""
          if (workerUrl.isNotBlank()) {
            return workerUrl.trimEnd('/')
          }
        }
      } catch (_: IOException) {
        // Retry
      }
      Thread.sleep(2000)
    }
    throw RevylApiException("Worker URL not available after ${maxWaitSeconds}s for workflow $workflowRunId")
  }

  /**
   * Waits until the worker's health endpoint reports a connected device.
   */
  private fun waitForDeviceReady(workerBaseUrl: String, maxWaitSeconds: Int) {
    val deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L)
    while (System.currentTimeMillis() < deadline) {
      try {
        val request = Request.Builder()
          .url("$workerBaseUrl/health")
          .get()
          .build()

        val response = httpClient.newCall(request).execute()
        if (response.isSuccessful) {
          return
        }
      } catch (_: IOException) {
        // Retry
      }
      Thread.sleep(2000)
    }
  }

  companion object {
    const val DEFAULT_BACKEND_URL = "https://backend.revyl.ai"
  }
}

/**
 * Exception thrown when a Revyl API or worker request fails.
 *
 * @property message Human-readable description of the failure.
 */
class RevylApiException(message: String) : RuntimeException(message)
