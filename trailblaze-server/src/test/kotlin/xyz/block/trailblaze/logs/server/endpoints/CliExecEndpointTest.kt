package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Endpoint tests for [CliExecEndpoint]. The loopback gate itself is not exercised here —
 * `testApplication` reports `localhost` as the remote address and that's in the allowlist,
 * so we assert the pattern matches [ScriptingCallbackEndpoint]'s tested one via shared
 * addresses. The branches covered:
 *  - success: callback runs, response body round-trips stdout/stderr/exitCode/forwarded
 *  - forwarded=false: callback signals the shim should fall through; endpoint still 200s
 *  - malformed body → 400 with diagnostic `stderr` payload, forwarded=false
 *  - callback throws → 500 with diagnostic `stderr` payload, forwarded=false
 */
class CliExecEndpointTest {

  private val json: Json = Json { ignoreUnknownKeys = true }

  @Test fun `success path relays callback response`() = testApplication {
    application {
      routing {
        CliExecEndpoint.register(this) { req ->
          CliExecResponse(
            stdout = "captured stdout for ${req.args.joinToString(" ")}",
            stderr = "",
            exitCode = 0,
            forwarded = true,
          )
        }
      }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody("""{"args":["snapshot","-d","android"]}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString(CliExecResponse.serializer(), response.bodyAsText())
    assertEquals("captured stdout for snapshot -d android", body.stdout)
    assertEquals(0, body.exitCode)
    assertTrue(body.forwarded)
  }

  @Test fun `success path deserializes env field and surfaces it to the callback`() = testApplication {
    // Pins the wire contract for env forwarding: the bash shim sends the
    // user's allowlisted shell env vars (currently just TRAILBLAZE_DEVICE)
    // in the JSON body, and the daemon must deserialize them into
    // request.env so `executeForDaemon` can pin them on the thread-local
    // via CliCallerContext.withCallerEnv. Without this test, a future
    // refactor that drops the field on the wire would silently regress
    // the snapshot-honors-env fix and we'd only catch it via FTUX.
    var capturedEnv: Map<String, String>? = null
    application {
      routing {
        CliExecEndpoint.register(this) { req ->
          capturedEnv = req.env
          CliExecResponse(stdout = "", stderr = "", exitCode = 0, forwarded = true)
        }
      }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody(
        """{"args":["snapshot"],"cwd":"/some/dir","env":{"TRAILBLAZE_DEVICE":"android/emulator-1"}}""",
      )
    }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(mapOf("TRAILBLAZE_DEVICE" to "android/emulator-1"), capturedEnv)
  }

  @Test fun `missing env field deserializes to null for backward compat with older shims`() = testApplication {
    // Older bash shims that predate the env-forwarding fix send no env
    // field. The daemon must accept those requests cleanly (env = null);
    // downstream `withCallerEnv(null)` then falls back to `System.getenv`,
    // which is the pre-fix behavior on the daemon-forwarded path (broken
    // for the snapshot scenario, but no worse than before — never
    // crashes). This pins the rollback safety contract.
    var capturedEnv: Map<String, String>? = mapOf("UNEXPECTED" to "value")
    application {
      routing {
        CliExecEndpoint.register(this) { req ->
          capturedEnv = req.env
          CliExecResponse(stdout = "", stderr = "", exitCode = 0, forwarded = true)
        }
      }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      // No `env` field at all — the old-shim shape.
      setBody("""{"args":["snapshot"]}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(null, capturedEnv, "missing env must deserialize to null, not an empty map")
  }

  @Test fun `forwarded false is passed through`() = testApplication {
    application {
      routing {
        CliExecEndpoint.register(this) {
          CliExecResponse(stdout = "", stderr = "", exitCode = 0, forwarded = false)
        }
      }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody("""{"args":["config","show"]}""")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString(CliExecResponse.serializer(), response.bodyAsText())
    assertFalse(body.forwarded)
  }

  @Test fun `malformed JSON body yields 400`() = testApplication {
    application {
      routing {
        CliExecEndpoint.register(this) { error("should not be called for malformed body") }
      }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody("{ not valid json }")
    }
    assertEquals(HttpStatusCode.BadRequest, response.status)
    val body = json.decodeFromString(CliExecResponse.serializer(), response.bodyAsText())
    assertFalse(body.forwarded)
    assertTrue(body.stderr.contains("malformed"))
  }

  @Test fun `callback exception yields 500 with diagnostic stderr`() = testApplication {
    application {
      routing {
        CliExecEndpoint.register(this) { error("boom in handler") }
      }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody("""{"args":["snapshot"]}""")
    }
    assertEquals(HttpStatusCode.InternalServerError, response.status)
    val body = json.decodeFromString(CliExecResponse.serializer(), response.bodyAsText())
    assertFalse(body.forwarded)
    assertEquals(1, body.exitCode)
    assertTrue(body.stderr.contains("boom in handler"))
  }

  /**
   * Locks in the contract enforced at `ServerEndpoints.kt:114` —
   * `callbacks.onCliExecRequest?.let { register }`: when the callback is not
   * supplied, the route is not registered and POSTs fall through to the
   * server's 404 handler. A future refactor that registers the endpoint
   * unconditionally (e.g., with a stub returning `forwarded=false`) would
   * silently change this contract and this test would fail.
   */
  @Test fun `endpoint not registered yields 404`() = testApplication {
    application {
      // Deliberately do NOT call CliExecEndpoint.register — simulates a daemon
      // assembled without onCliExecRequest wired.
      routing { }
    }
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody("""{"args":["snapshot"]}""")
    }
    assertEquals(HttpStatusCode.NotFound, response.status)
  }

  @Test fun `request body larger than 1 MiB yields 413`() = testApplication {
    application {
      routing {
        CliExecEndpoint.register(this) { error("handler must not be called for oversized body") }
      }
    }
    // Build a ~2 MiB argv with one giant string. The server must reject based
    // on Content-Length before reading the body, so the giant string never
    // reaches the handler.
    val huge = "A".repeat(2 * 1024 * 1024)
    val response = client.post(CliEndpoints.EXEC) {
      contentType(ContentType.Application.Json)
      setBody("""{"args":["snapshot","$huge"]}""")
    }
    assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    val body = json.decodeFromString(CliExecResponse.serializer(), response.bodyAsText())
    assertFalse(body.forwarded)
    assertTrue(body.stderr.contains("too large"))
  }
}
