package xyz.block.trailblaze.playwright

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import xyz.block.trailblaze.util.DesktopOsType
import xyz.block.trailblaze.util.isArm

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

  /**
   * Ensures the Chromium browser binary is installed in the Playwright browsers cache.
   *
   * Playwright separates the "driver" (Node.js runtime, ~7 MB) from the "browser" (Chromium
   * binary, ~150 MB). [ensureDriverAvailable] handles the driver; this method handles the
   * browser. It checks that both `chromium-*` and `chromium_headless_shell-*` directories exist
   * in the ms-playwright cache (or in [PLAYWRIGHT_BROWSERS_PATH] if set) with a completed
   * installation marker, and runs `playwright install chromium` if either is missing.
   *
   * Both variants are required because headless mode (the default) uses the headless shell,
   * while headed mode uses the full Chromium browser.
   *
   * Must be called after [ensureDriverAvailable] so that `playwright.cli.dir` is already set.
   * Blocks the calling thread until the download completes (one-time, ~150 MB).
   *
   * @param onProgress invoked on each progress update with (percentComplete 0–100, statusMessage).
   *   Called from a background reader thread. Implementations must be thread-safe. May be null.
   */
  fun ensureBrowserInstalled(onProgress: ((Int, String) -> Unit)? = null) {
    if (isChromiumInstalled()) return

    println("[Playwright] Chromium not found — installing (one-time, ~150 MB)...")
    onProgress?.invoke(0, "Downloading Chromium...")
    runPlaywrightInstallChromium(onProgress)
    println("[Playwright] Chromium installed successfully.")
  }

  /** Prefix for the standard Chromium browser directory in the Playwright cache. */
  private const val CHROMIUM_DIR_PREFIX = "chromium-"

  /** Prefix for the headless-shell Chromium directory in the Playwright cache. */
  private const val CHROMIUM_HEADLESS_SHELL_DIR_PREFIX = "chromium_headless_shell-"

  /** Marker file Playwright writes after a successful browser download. */
  private const val INSTALLATION_COMPLETE_MARKER = "INSTALLATION_COMPLETE"

  /**
   * Returns the Playwright browser cache directory for the current platform.
   *
   * Honors `PLAYWRIGHT_BROWSERS_PATH` when set (and not the sentinel `"0"`),
   * otherwise falls back to the platform-specific default `ms-playwright` cache location.
   */
  fun getPlaywrightBrowsersCacheDir(): File {
    val browsersPathEnv = System.getenv("PLAYWRIGHT_BROWSERS_PATH")
    if (!browsersPathEnv.isNullOrBlank() && browsersPathEnv != "0") {
      return File(browsersPathEnv)
    }
    return when (DesktopOsType.current()) {
      DesktopOsType.MAC_OS ->
        File(System.getProperty("user.home"), "Library/Caches/ms-playwright")
      DesktopOsType.LINUX ->
        File(System.getProperty("user.home"), ".cache/ms-playwright")
      DesktopOsType.WINDOWS -> {
        val localAppData = System.getenv("LOCALAPPDATA")
          ?: (System.getProperty("user.home") + "/AppData/Local")
        File(localAppData, "ms-playwright")
      }
    }
  }

  private fun isChromiumInstalled(): Boolean {
    val cacheDir = getPlaywrightBrowsersCacheDir()
    if (!cacheDir.exists() || !cacheDir.isDirectory) return false
    // Playwright writes an INSTALLATION_COMPLETE marker file after downloading a browser.
    // Checking only the directory name is insufficient — the directory can exist without
    // the actual binary (e.g., partial download, interrupted install, or cleanup).
    // We need *both* chromium and chromium_headless_shell because headless mode (the default)
    // uses the headless shell variant, while headed mode needs the full browser.
    val dirs = cacheDir.listFiles() ?: return false
    val hasChromium = dirs.any { file ->
      file.isDirectory &&
        file.name.startsWith(CHROMIUM_DIR_PREFIX) &&
        File(file, INSTALLATION_COMPLETE_MARKER).exists()
    }
    val hasHeadlessShell = dirs.any { file ->
      file.isDirectory &&
        file.name.startsWith(CHROMIUM_HEADLESS_SHELL_DIR_PREFIX) &&
        File(file, INSTALLATION_COMPLETE_MARKER).exists()
    }
    return hasChromium && hasHeadlessShell
  }

  private val progressRegex = Regex("""(\d{1,3})\s*%""")

  private fun parseProgressPercent(line: String): Int? {
    val value = progressRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return if (value in 0..100) value else null
  }

  /**
   * Spawns a subprocess to run `playwright install chromium`.
   *
   * Must run as a subprocess (not via [com.microsoft.playwright.CLI.main]) because the CLI
   * calls [System.exit] on completion, which would terminate the host JVM.
   */
  private fun runPlaywrightInstallChromium(onProgress: ((Int, String) -> Unit)? = null) {
    val javaHome = System.getProperty("java.home")
    val javaBin = File(javaHome, "bin/java").absolutePath
    val classPath = System.getProperty("java.class.path")
    val cliDir = System.getProperty("playwright.cli.dir")
    val driverTmpdir = System.getProperty("playwright.driver.tmpdir")

    val process = ProcessBuilder(
      buildList {
        add(javaBin)
        add("-cp")
        add(classPath)
        if (cliDir != null) {
          add("-Dplaywright.cli.dir=$cliDir")
        } else if (driverTmpdir != null) {
          add("-Dplaywright.driver.tmpdir=$driverTmpdir")
        }
        add("com.microsoft.playwright.CLI")
        add("install")
        add("chromium")
      }
    ).redirectErrorStream(true).start()

    // Read stdout on a background thread so the timeout on the main thread can actually
    // fire. If we block on useLines{} here, waitFor() is never reached until the process
    // closes stdout — which never happens if it hangs.
    val readerThread = Thread {
      try {
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            println("[Playwright] $line")
            if (onProgress != null && line.isNotBlank()) {
              onProgress(parseProgressPercent(line) ?: 0, line.trim())
            }
          }
        }
      } catch (_: Exception) {
        // Stream closed — process ended or was destroyed; nothing to do.
      }
    }
    readerThread.isDaemon = true
    readerThread.start()

    val finished = process.waitFor(15, TimeUnit.MINUTES)
    if (!finished) {
      process.destroyForcibly()
      readerThread.join(5_000)
      error(
        "playwright install chromium timed out after 15 minutes. " +
          "Run `playwright install chromium` manually to install the browser."
      )
    }
    readerThread.join(5_000)
    val exitCode = process.exitValue()
    if (exitCode != 0) {
      error(
        "playwright install chromium failed (exit code $exitCode). " +
          "Run `playwright install chromium` manually to install the browser."
      )
    }
  }

  private fun detectPlatform(): String {
    return when (DesktopOsType.current()) {
      DesktopOsType.MAC_OS -> if (isArm()) "mac-arm64" else "mac"
      DesktopOsType.LINUX -> if (isArm()) "linux-arm64" else "linux"
      DesktopOsType.WINDOWS -> "win32_x64"
    }
  }
}
