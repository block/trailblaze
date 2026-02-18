package xyz.block.trailblaze.http

import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.takeFrom
import xyz.block.trailblaze.devices.TrailblazeDevicePort

class ReverseProxyPluginConfig {
  var reverseProxyEnabled: Boolean = true
  var reverseProxyUrl: String? = "https://localhost:${TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT}/reverse-proxy"
}

val ReverseProxyPlugin = createClientPlugin(
  name = "ReverseProxyPlugin",
  createConfiguration = ::ReverseProxyPluginConfig,
) {
  val pluginConfig = this.pluginConfig
  val reverseProxyEnabled = pluginConfig.reverseProxyEnabled
  val newUrl = pluginConfig.reverseProxyUrl
  if (reverseProxyEnabled && newUrl != null) {
    onRequest { request, _ ->
      if (reverseProxyEnabled) {
        val originalUri = request.url.buildString()
        // Rewrite the request URL
        request.url.takeFrom(newUrl)

        // Add header with original URI
        request.headers.append(ReverseProxyHeaders.ORIGINAL_URI, originalUri)
      }
    }
  }
}
