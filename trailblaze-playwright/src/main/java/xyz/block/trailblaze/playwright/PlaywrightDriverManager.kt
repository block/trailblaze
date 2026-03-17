package xyz.block.trailblaze.playwright

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile

/**
 * Ensures the Playwright driver is available before [Playwright.create()][com.microsoft.playwright.Playwright.create]
 * is called.
 *
 * In development (running via Gradle), the `driver-bundle` JAR is on the classpath and Playwright
 * handles driver extraction automatically. In production (uber JAR), `driver-bundle` is excluded to
 * save ~194 MB. This manager downloads and caches the platform-specific driver on first use instead,
 * leveraging the `playwright.cli.dir` system property so Playwright uses the cached copy directly.
 *
 * The driver is cached at `~/.cache/trailblaze/playwright-driver/{version}/` and reused across runs.
 */
object PlaywrightDriverManager {

  private val CACHE_BASE: Path = Path.of(
    System.getProperty("user.home"), ".cache", "trailblaze", "playwright-driver"
  )

  /**
   * Ensures the Playwright driver is available for the current platform.
   *
   * - If `driver-bundle` is on the classpath, configures `playwright.driver.tmpdir` so Playwright
   *   extracts to a stable cache directory (avoids OS temp-dir cleanup issues).
   * - If `driver-bundle` is absent, downloads the driver from Maven Central (one-time, ~194 MB)
   *   and sets `playwright.cli.dir` to bypass the missing `DriverJar`.
   *
   * Safe to call multiple times; no-ops after the first successful setup.
   */
  fun ensureDriverAvailable() {
    // Already configured by a previous call in this JVM.
    if (System.getProperty("playwright.cli.dir") != null) return

    Files.createDirectories(CACHE_BASE)

    if (isDriverBundleOnClasspath()) {
      // Dev mode: driver-bundle is available — just set a stable extraction dir.
      System.setProperty("playwright.driver.tmpdir", CACHE_BASE.toString())
      return
    }

    // Uber JAR mode: driver-bundle excluded. Download & cache if needed.
    val version = getPlaywrightVersion()
    val driverDir = CACHE_BASE.resolve(version)

    if (!isDriverCached(driverDir)) {
      downloadAndExtractDriver(version, driverDir)
    }

    System.setProperty("playwright.cli.dir", driverDir.toAbsolutePath().toString())
  }

  private fun isDriverBundleOnClasspath(): Boolean = try {
    Class.forName("com.microsoft.playwright.impl.driver.jar.DriverJar")
    true
  } catch (_: ClassNotFoundException) {
    false
  }

  /**
   * Reads the Playwright version from the `driver` module's Maven metadata on the classpath.
   * The `driver` artifact (7 KB) is always present — only `driver-bundle` (194 MB) is excluded.
   */
  private fun getPlaywrightVersion(): String {
    val propsPath = "META-INF/maven/com.microsoft.playwright/driver/pom.properties"
    val props = Properties()
    PlaywrightDriverManager::class.java.classLoader
      .getResourceAsStream(propsPath)
      ?.use { props.load(it) }
    return props.getProperty("version")
      ?: error(
        "Cannot determine Playwright version — $propsPath not found on classpath. " +
          "Ensure com.microsoft.playwright:driver is a dependency."
      )
  }

  /** A cached driver directory is valid if it contains `node` (or `node.exe`) and `package/cli.js`. */
  private fun isDriverCached(driverDir: Path): Boolean =
    (Files.exists(driverDir.resolve("node")) || Files.exists(driverDir.resolve("node.exe"))) &&
      Files.exists(driverDir.resolve("package").resolve("cli.js"))

  /**
   * Downloads the `driver-bundle` JAR from Maven Central and extracts only the current platform's
   * driver files into [driverDir].
   */
  private fun downloadAndExtractDriver(version: String, driverDir: Path) {
    val platform = detectPlatform()
    val mavenUrl =
      "https://repo1.maven.org/maven2/com/microsoft/playwright/" +
        "driver-bundle/$version/driver-bundle-$version.jar"

    println("[Playwright] Driver not found — downloading for $platform (one-time)...")

    val tempJar = Files.createTempFile("playwright-driver-bundle-", ".jar")
    try {
      downloadFile(mavenUrl, tempJar)
      extractPlatformDriver(tempJar, driverDir, platform)
      println("[Playwright] Driver $version installed at $driverDir")
    } catch (e: Exception) {
      // Clean up partial extraction so the next run retries cleanly.
      driverDir.toFile().deleteRecursively()
      throw RuntimeException(
        "Failed to download Playwright driver from $mavenUrl. " +
          "You can manually place the driver at $driverDir or add " +
          "com.microsoft.playwright:driver-bundle:$version to the classpath.",
        e,
      )
    } finally {
      Files.deleteIfExists(tempJar)
    }
  }

  private fun downloadFile(url: String, target: Path) {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 30_000
    connection.readTimeout = 60_000
    try {
      val totalBytes = connection.contentLengthLong
      connection.inputStream.use { input ->
        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
      }
      if (totalBytes > 0) {
        val downloadedMB = Files.size(target) / (1024 * 1024)
        println("[Playwright] Downloaded ${downloadedMB}MB")
      }
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Opens the driver-bundle JAR and extracts entries under `driver/{platform}/` into [driverDir],
   * stripping the `driver/{platform}/` prefix so the structure matches what Playwright expects.
   */
  private fun extractPlatformDriver(jarPath: Path, driverDir: Path, platform: String) {
    val prefix = "driver/$platform/"
    Files.createDirectories(driverDir)

    ZipFile(jarPath.toFile()).use { zip ->
      zip.entries().asSequence()
        .filter { it.name.startsWith(prefix) && !it.isDirectory }
        .forEach { entry ->
          val relativePath = entry.name.removePrefix(prefix)
          if (relativePath.isEmpty()) return@forEach
          val target = driverDir.resolve(relativePath)
          Files.createDirectories(target.parent)
          zip.getInputStream(entry).use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
          }
        }
    }

    // Make the node binary executable on Unix platforms.
    listOf("node", "package/cli.js").forEach { name ->
      val file = driverDir.resolve(name)
      if (Files.exists(file)) {
        file.toFile().setExecutable(true)
      }
    }
  }

  private fun detectPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
      os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "mac-arm64"
      os.contains("mac") -> "mac"
      os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux-arm64"
      os.contains("linux") -> "linux"
      os.contains("win") -> "win32_x64"
      else -> error("Unsupported platform for Playwright driver: $os ($arch)")
    }
  }
}
