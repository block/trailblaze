package xyz.block.trailblaze.host.recording.rpc

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DevicesPageEndpointTest {

  @Test
  fun `devices serves the standalone websocket mirror without a wasm bundle`() = testApplication {
    application { routing { DevicesPageEndpoint.register(this) } }

    val response = client.get("/devices")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
    assertEquals("no-store", response.headers["Cache-Control"])
    val html = response.bodyAsText()
    assertTrue(html.contains("<title>Trailblaze Device Mirror</title>"))
    assertTrue(html.contains("/devices/api/stream"))
    assertTrue(html.contains("new VideoDecoder"))
    assertTrue(html.contains("/rpc/SubscribeFramesRequest"))
    assertTrue(html.contains("NavigateWebUrlRequest"))
    assertTrue(html.contains("GetToolCatalogRequest"))
    assertTrue(html.contains("RunTrailYamlRequest"))
    assertFalse(html.contains("composeApp.js"))
  }

  @Test
  fun `devices all serves the same client-routed mirror page`() = testApplication {
    application { routing { DevicesPageEndpoint.register(this) } }

    val response = client.get("/devices/all")

    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("const allMode"))
  }
}
