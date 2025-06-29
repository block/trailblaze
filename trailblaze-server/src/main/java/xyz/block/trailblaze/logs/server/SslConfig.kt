package xyz.block.trailblaze.logs.server

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import java.io.File

object SslConfig {
  fun ApplicationEngine.Configuration.configureForSelfSignedSsl(requestedHttpPort: Int, requestedHttpsPort: Int) {
    val keyStoreFile = File("build/keystore.jks")
    val keyStore = buildKeyStore {
      certificate("sampleAlias") {
        password = "foobar"
        domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
      }
    }
    keyStore.saveToFile(keyStoreFile, "123456")

    connector {
      port = requestedHttpPort
    }
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
  }
}
