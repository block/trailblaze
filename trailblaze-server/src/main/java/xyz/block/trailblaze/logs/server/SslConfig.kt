package xyz.block.trailblaze.logs.server

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import java.io.File

object SslConfig {
  fun ApplicationEngine.Configuration.configureForSelfSignedSsl(requestedHttpPort: Int, requestedHttpsPort: Int) {
    // Always configure the HTTP connector first so the server starts even if SSL setup fails.
    connector {
      host = "::"
      port = requestedHttpPort
    }

    // Store the keystore under ~/.trailblaze/ so it works regardless of the working directory.
    // Previously used a relative "build/keystore.jks" path which failed when the daemon was
    // launched from a directory without a build/ folder (e.g., via the MCP stdio proxy).
    val trailblazeDir = File(System.getProperty("user.home"), ".trailblaze")
    trailblazeDir.mkdirs()
    val keyStoreFile = File(trailblazeDir, "keystore.jks")

    try {
      val keyStore = buildKeyStore {
        certificate("sampleAlias") {
          password = "foobar"
          domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
        }
      }
      keyStore.saveToFile(keyStoreFile, "123456")

      // We use SSL so Android devices don't need to allowlist the server as a trusted host.
      sslConnector(
        keyStore = keyStore,
        keyAlias = "sampleAlias",
        keyStorePassword = { "123456".toCharArray() },
        privateKeyPassword = { "foobar".toCharArray() },
      ) {
        port = requestedHttpsPort
        keyStorePath = keyStoreFile
      }
    } catch (e: Exception) {
      System.err.println("[SslConfig] HTTPS setup failed (HTTP still available): ${e.message}")
    }
  }
}
