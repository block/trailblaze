package xyz.block.trailblaze.http

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Factory for creating a Trailblaze HttpClient.
 */
object TrailblazeHttpClientFactory {

  private fun OkHttpClient.Builder.configureOkHttpClient(
    timeoutInSeconds: Long,
    trustAllCerts: Boolean,
  ) {
    // Needed to call Logs Server which uses a self-signed certificate
    if (trustAllCerts) {
      trustAllCerts()
    }
    setTimeoutsInSeconds(timeoutInSeconds)
  }

  fun createDefaultHttpClient(
    timeoutInSeconds: Long,
  ) = HttpClient(OkHttp) {
    enablePerfettoTracing()
    enableNetworkLogging()
    engine {
      config {
        configureOkHttpClient(timeoutInSeconds, false)
      }
    }
  }
  fun HttpClientConfig<*>.enablePerfettoTracing() {
    install(PerfettoHttpTracing) {
      category = "http"
      redactQuery = true
      commonArgs = mapOf("svc" to "mobile")
    }
  }

  fun HttpClientConfig<*>.enableNetworkLogging() {
    install(Logging) {
      logger = object : Logger {
        override fun log(message: String) {
          // Log the request and response details
          println("TrailblazeClient: $message")
        }
      }
      level = LogLevel.INFO
    }
  }

  fun createInsecureTrustAllCertsHttpClient(
    timeoutInSeconds: Long,
  ) = HttpClient(OkHttp) {
    enablePerfettoTracing()
    enableNetworkLogging()
    engine {
      config {
        configureOkHttpClient(timeoutInSeconds, true)
      }
    }
  }

  fun createInsecureTrustAllCertsHttpClient(
    timeoutInSeconds: Long,
    reverseProxyUrl: String?,
  ) = HttpClient(OkHttp) {
    enablePerfettoTracing()
    enableNetworkLogging()
    if (reverseProxyUrl != null) {
      install(ReverseProxyPlugin) {
        this.reverseProxyEnabled = true // Disable reverse proxy for this client
        this.reverseProxyUrl = reverseProxyUrl // No reverse proxy URL needed
      }
    }
    engine {
      config {
        configureOkHttpClient(timeoutInSeconds, true)
      }
    }
  }

  private fun OkHttpClient.Builder.setTimeoutsInSeconds(timeoutInSeconds: Long) {
    // Short timeouts because this should be a local call
    writeTimeout(timeoutInSeconds, java.util.concurrent.TimeUnit.SECONDS)
    readTimeout(timeoutInSeconds, java.util.concurrent.TimeUnit.SECONDS)
    connectTimeout(timeoutInSeconds, java.util.concurrent.TimeUnit.SECONDS)
  }

  /**
   * Disabling SSL Verification for our Self-Signed Logging Certificate
   */
  private fun OkHttpClient.Builder.trustAllCerts() {
    val trustAllCerts = arrayOf<TrustManager>(
      @Suppress("CustomX509TrustManager")
      object : X509TrustManager {
        @Suppress("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @Suppress("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      },
    )

    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, trustAllCerts, SecureRandom())
    val sslSocketFactory = sslContext.socketFactory

    sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
    hostnameVerifier { _, _ -> true }
  }
}
