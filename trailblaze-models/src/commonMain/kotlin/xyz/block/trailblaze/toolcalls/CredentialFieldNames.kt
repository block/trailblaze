package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a field named [name] holds a credential wherever it turns up: a tool's argument, a key in
 * a captured request body, a URL query parameter. Such a field is masked in what Trailblaze writes
 * down even when nothing declared it secret, so a tool that forgets `sensitiveArgNames` doesn't
 * leak its `password` into a session that ships as a CI artifact.
 *
 * Deliberately narrow. A masked argument also comes back masked in a recording generated from the
 * log, so a broad rule (anything ending in `token`, `pin`, `code`) would quietly break replays of
 * tools whose identifiers merely look secret. Short credentials such as passcodes and PINs stay
 * the declaring tool's call.
 *
 * Case, `_` and `-` are ignored: `password`, `merchant_password`, `newPassword` and `X-Api-Key`
 * all match.
 */
fun isCredentialFieldName(name: String): Boolean {
  val normalized = name.lowercase().filter { it != '_' && it != '-' }
  if (normalized.endsWith("password") || normalized.endsWith("passwd")) return true
  return normalized in CREDENTIAL_FIELD_NAMES
}

/** This JSON with the value of every credential-named key ([isCredentialFieldName]), at any depth, replaced by [placeholder]. */
fun JsonElement.withCredentialFieldsMasked(placeholder: String): JsonElement = when (this) {
  is JsonObject -> JsonObject(
    mapValues { (key, value) ->
      if (isCredentialFieldName(key)) JsonPrimitive(placeholder) else value.withCredentialFieldsMasked(placeholder)
    },
  )
  is JsonArray -> JsonArray(map { it.withCredentialFieldsMasked(placeholder) })
  is JsonPrimitive -> this
}

private val CREDENTIAL_FIELD_NAMES = setOf(
  "secret",
  "clientsecret",
  "apikey",
  "xapikey",
  "accesstoken",
  "refreshtoken",
  "sessiontoken",
  "authtoken",
  "idtoken",
  "bearertoken",
  "csrf",
  "csrftoken",
  "xsrftoken",
  "xcsrftoken",
  "xxsrftoken",
  "cookie",
  "cookies",
  "cookiesjson",
  "setcookie",
  "authorization",
  "proxyauthorization",
)
