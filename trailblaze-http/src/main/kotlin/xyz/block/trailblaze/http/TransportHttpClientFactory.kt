package xyz.block.trailblaze.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Shared transport policy. Runtime callers add instrumentation explicitly through [configure]. */
object TransportHttpClientFactory {
  fun createDefaultHttpClient(
    timeoutInSeconds: Long,
    trustManager: X509TrustManager? = SystemCaBundleTrust.trustManager,
    configure: HttpClientConfig<OkHttpConfig>.() -> Unit = {},
  ): HttpClient =
    HttpClient(OkHttp) {
      engine {
        config {
          writeTimeout(timeoutInSeconds, TimeUnit.SECONDS)
          readTimeout(timeoutInSeconds, TimeUnit.SECONDS)
          connectTimeout(timeoutInSeconds, TimeUnit.SECONDS)
          trustManager?.let { trust ->
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            sslSocketFactory(context.socketFactory, trust)
          }
        }
      }
      configure()
    }
}
