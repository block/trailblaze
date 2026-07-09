package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Endpoint tests for [CliShowWindowEndpoint]. The `success` flag is load-bearing: `trailblaze
 * app` uses it to distinguish "window shown" from "daemon has no window" (attach-GUI-alongside
 * detection), and a duplicate instance losing the port-bind race polls it to hand the user's
 * window intent to the winner. A no-op (no window handler installed) must therefore report
 * `success = false`, not "Window shown".
 */
class CliShowWindowEndpointTest {

  private val json: Json = Json { ignoreUnknownKeys = true }

  /** The real server installs ContentNegotiation; the endpoint's `call.respond(dataClass)` needs it. */
  private fun Application.installJson() = install(ContentNegotiation) { json() }

  @Test fun `handler ran reports success`() = testApplication {
    application {
      installJson()
      routing {
        CliShowWindowEndpoint.register(this) { true }
      }
    }
    val response = client.post(CliEndpoints.SHOW_WINDOW)
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString(CliShowWindowResponse.serializer(), response.bodyAsText())
    assertTrue(body.success)
  }

  @Test fun `no window handler reports success=false`() = testApplication {
    application {
      installJson()
      routing {
        CliShowWindowEndpoint.register(this) { false }
      }
    }
    val response = client.post(CliEndpoints.SHOW_WINDOW)
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString(CliShowWindowResponse.serializer(), response.bodyAsText())
    assertFalse(body.success, "A no-op show-window must not report success")
    assertTrue(body.message.isNotBlank(), "The failure message should explain why no window was shown")
  }

  @Test fun `handler exception reports 500 with success=false`() = testApplication {
    application {
      installJson()
      routing {
        CliShowWindowEndpoint.register(this) { error("window subsystem exploded") }
      }
    }
    val response = client.post(CliEndpoints.SHOW_WINDOW)
    assertEquals(HttpStatusCode.InternalServerError, response.status)
    val body = json.decodeFromString(CliShowWindowResponse.serializer(), response.bodyAsText())
    assertFalse(body.success)
  }
}
