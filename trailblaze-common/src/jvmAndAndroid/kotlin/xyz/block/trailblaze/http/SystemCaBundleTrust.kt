package xyz.block.trailblaze.http

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust for HTTPS clients that also honours the OS CA bundle, as curl, Python and Node do.
 *
 * The JVM only trusts its own `cacerts`. Machines whose network presents certificates from a
 * corporate or proxy CA trust that CA through the OS bundle instead, usually named by
 * `SSL_CERT_FILE`, so a stock JDK fails those calls with "PKIX path building failed" while curl
 * succeeds against the same URL.
 */
object SystemCaBundleTrust {

  /**
   * The JVM defaults plus the OS bundle, or null when there is no bundle to add. Built once per
   * process: the bundle is a few hundred certificates and every default client would reparse it.
   */
  val trustManager: X509TrustManager? by lazy { trustManagerWithCaBundle(osCaBundle()) }

  /** `$SSL_CERT_FILE` when it names a file, else the Debian/Ubuntu system bundle when present. */
  fun osCaBundle(
    sslCertFile: String? = System.getenv("SSL_CERT_FILE"),
    fallback: File = File(DEBIAN_CA_BUNDLE),
  ): File? =
    sslCertFile?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
      ?: fallback.takeIf { it.isFile }

  /**
   * A trust manager over the union of [defaults]' anchors and every parsable certificate in the PEM
   * [caBundle]. Null when the bundle is absent, unreadable, or holds no certificates, so callers
   * keep the platform trust untouched and a bad bundle can never cost the defaults.
   */
  fun trustManagerWithCaBundle(
    caBundle: File?,
    defaults: X509TrustManager = x509TrustManager(null),
  ): X509TrustManager? {
    val extra = caBundle?.let { runCatching { readPemCertificates(it.readText()) }.getOrNull() }
    if (extra.isNullOrEmpty()) return null
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
    (defaults.acceptedIssuers.asList() + extra).forEachIndexed { index, cert ->
      keyStore.setCertificateEntry("ca-$index", cert)
    }
    return x509TrustManager(keyStore)
  }

  /** Every `BEGIN CERTIFICATE` block in [pem]; blocks that do not decode are skipped. */
  internal fun readPemCertificates(pem: String): List<X509Certificate> {
    val factory = CertificateFactory.getInstance("X.509")
    return PEM_CERTIFICATE.findAll(pem)
      .mapNotNull { match ->
        try {
          val der = Base64.getMimeDecoder().decode(match.groupValues[1])
          factory.generateCertificate(der.inputStream()) as? X509Certificate
        } catch (e: IllegalArgumentException) {
          null
        } catch (e: CertificateException) {
          null
        }
      }
      .toList()
  }

  private fun x509TrustManager(keyStore: KeyStore?): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      .apply { init(keyStore) }
      .trustManagers
      .filterIsInstance<X509TrustManager>()
      .first()

  private val PEM_CERTIFICATE =
    Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----")
  private const val DEBIAN_CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"
}
