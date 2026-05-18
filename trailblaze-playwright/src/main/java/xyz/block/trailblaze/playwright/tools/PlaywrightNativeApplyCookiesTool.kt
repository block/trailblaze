package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.SameSiteAttribute
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("web_applyCookies")
@LLMDescription(
  """
Injects a set of cookies into the current browser context. Pass [cookiesJson] as a JSON
string in Playwright's storage-state cookie array shape, e.g.:

  [{"name":"sid","value":"abc","domain":".example.com","path":"/","httpOnly":true,"secure":true,"sameSite":"Lax"}]

Typically used to replay a previously-saved authenticated session so a trail can skip a full
UI login. Pair with web_getStorageState (for capture) and web_navigate (to drive to the
authenticated landing page after applying).
""",
)
data class PlaywrightNativeApplyCookiesTool(
  @param:LLMDescription(
    "JSON array of cookies in Playwright's storage-state cookie shape. May be the full " +
      "storageState JSON object (in which case the `cookies` field is read), or the bare " +
      "cookies array.",
  )
  val cookiesJson: String = "",
) : PlaywrightExecutableTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (cookiesJson.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown("cookiesJson is required")
    }
    return try {
      val parsed = Json { ignoreUnknownKeys = true; isLenient = true }
        .parseToJsonElement(cookiesJson)
      val cookiesArray =
        when {
          parsed is kotlinx.serialization.json.JsonArray -> parsed
          parsed is JsonObject && parsed["cookies"] != null -> parsed["cookies"]!!.jsonArray
          else ->
            return TrailblazeToolResult.Error.ExceptionThrown(
              "cookiesJson must be a JSON array or a storageState object with a `cookies` field"
            )
        }
      val cookies = cookiesArray.mapNotNull { parseCookie(it.jsonObject) }
      if (cookies.isNotEmpty()) page.context().addCookies(cookies)
      TrailblazeToolResult.Success(message = "Applied ${cookies.size} cookie(s)")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("web_applyCookies failed: ${e.message}")
    }
  }

  private fun parseCookie(obj: JsonObject): Cookie? {
    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
    val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: return null
    val cookie = Cookie(name, value)
    obj["url"]?.jsonPrimitive?.contentOrNull?.let { cookie.setUrl(it) }
    obj["domain"]?.jsonPrimitive?.contentOrNull?.let { cookie.setDomain(it) }
    obj["path"]?.jsonPrimitive?.contentOrNull?.let { cookie.setPath(it) }
    obj["expires"]?.jsonPrimitive?.doubleOrNull?.let { cookie.setExpires(it) }
    obj["httpOnly"]?.jsonPrimitive?.booleanOrNull?.let { cookie.setHttpOnly(it) }
    obj["secure"]?.jsonPrimitive?.booleanOrNull?.let { cookie.setSecure(it) }
    obj["sameSite"]?.jsonPrimitive?.contentOrNull?.let { ss ->
      runCatching { SameSiteAttribute.valueOf(ss.uppercase()) }
        .getOrNull()
        ?.let { cookie.setSameSite(it) }
    }
    return cookie
  }
}
