package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.util.CommandProcessResult
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File
import xyz.block.trailblaze.util.Console

object IosHostUtils {

  fun killAppOnSimulator(deviceId: String, appId: String) {
    TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "terminate",
        deviceId,
        appId,
      ),
    ).runProcess {}
  }

  fun getInstalledAppIds(deviceId: String): Set<String> {
    val output: CommandProcessResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "listapps",
        deviceId,
      ),
    ).runProcess {}

    return parseInstalledAppIdsFromListApps(output.outputLines)
  }

  /**
   * Parses bundle identifiers from `xcrun simctl listapps` output lines.
   *
   * The output is a plist-style format where each app is on a line like:
   * ```
   *     "com.example.app" =     {
   * ```
   *
   * This function extracts the quoted bundle identifiers, filtering out:
   * - App group identifiers (starting with "group.")
   * - Blank entries
   *
   * @param outputLines The lines from `xcrun simctl listapps` output
   * @return Set of bundle identifiers
   */
  internal fun parseInstalledAppIdsFromListApps(outputLines: List<String>): Set<String> {
    // Regex that handles:
    // - Variable whitespace at the beginning (one or more spaces/tabs)
    // - Quoted bundle identifier
    // - Variable whitespace around the equals sign
    // - Opening brace or other content after equals
    val regex = Regex("^\\s+\"([^\"]+)\"\\s*=\\s*.*")

    return outputLines
      .mapNotNull { line -> regex.matchEntire(line)?.groupValues?.get(1) }
      .filter { it.isNotBlank() && !it.startsWith("group.") }
      .toSet()
  }

  fun clearAppDataContainer(deviceId: String, appId: String) {
    val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf(
        "xcrun",
        "simctl",
        "get_app_container",
        deviceId,
        appId,
        "data",
      ),
    ).runProcess {}

    output.outputLines.firstOrNull()?.let { dataContainerPath ->
      val dataContainerDirectory = File(dataContainerPath)
      if (dataContainerDirectory.exists() && dataContainerDirectory.isDirectory) {
        Console.log("Clearing $appId data container under $dataContainerPath")
        dataContainerDirectory.listFiles()?.forEach { it.deleteRecursively() }
      }
    }
  }

  /**
   * Gets version information for an installed iOS app.
   *
   * Uses `xcrun simctl listapps` to find the app bundle path,
   * then reads the Info.plist to extract version information.
   *
   * @param trailblazeDeviceId The device ID for the simulator
   * @param appId The bundle identifier (e.g., "com.squareup.square")
   * @return AppVersionInfo with version details, or null if the app is not installed or parsing fails
   */
  fun getAppVersionInfo(trailblazeDeviceId: TrailblazeDeviceId, appId: String): AppVersionInfo? {
    val deviceId = trailblazeDeviceId.instanceId
    return try {
      // Get the app path from listapps output
      val listAppsOutput = TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf(
          "xcrun",
          "simctl",
          "listapps",
          deviceId,
        ),
      ).runProcess {}

      // Parse the app path from listapps output
      // Format: "com.squareup.square" = { ... Path = "/path/to/App.app"; ... }
      val appPath = parseAppPathFromListApps(listAppsOutput.fullOutput, appId)
      if (appPath.isNullOrBlank()) {
        return null
      }

      val infoPlistPath = "$appPath/Info.plist"

      // Read version info from Info.plist using defaults command
      val versionCode = readPlistKey(infoPlistPath, "CFBundleVersion")
      val versionName = readPlistKey(infoPlistPath, "CFBundleShortVersionString")

      if (versionCode != null) {
        AppVersionInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          versionCode = versionCode,
          versionName = versionName,
          appBundlePath = appPath,
        )
      } else {
        null
      }
    } catch (e: Exception) {
      Console.log("Failed to get version info for $appId: ${e.message}")
      null
    }
  }

  /**
   * Gets the app bundle path for an installed iOS app.
   * This can be used by app-specific implementations to read custom plist keys.
   *
   * @param deviceId The simulator device ID
   * @param appId The bundle identifier
   * @return The path to the app bundle, or null if not found
   */
  fun getAppBundlePath(deviceId: String, appId: String): String? {
    return try {
      val listAppsOutput = TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf("xcrun", "simctl", "listapps", deviceId),
      ).runProcess {}
      parseAppPathFromListApps(listAppsOutput.fullOutput, appId)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Parses the app bundle path from `xcrun simctl listapps` output.
   *
   * The output is a plist-style format where each app block looks like:
   * ```
   *     "com.example.app" = {
   *         ...
   *         Path = "/path/to/App.app";
   *         ...
   *     };
   * ```
   *
   * @param output The full output from `xcrun simctl listapps`
   * @param appId The bundle identifier to find
   * @return The path to the app bundle, or null if not found
   */
  internal fun parseAppPathFromListApps(output: String, appId: String): String? {
    // Find the section for this app
    val appSectionStart = output.indexOf("\"$appId\"")
    if (appSectionStart == -1) return null

    // Find the end of this app's section (next app section or end of output)
    val remainingOutput = output.substring(appSectionStart)
    val nextAppStart = remainingOutput.indexOf("\n    \"", 1)
    val appSection = if (nextAppStart != -1) {
      remainingOutput.substring(0, nextAppStart)
    } else {
      remainingOutput
    }

    // Extract the Path value
    // Format: Path = "/path/to/App.app";
    val pathRegex = Regex("Path\\s*=\\s*\"([^\"]+)\"")
    return pathRegex.find(appSection)?.groupValues?.get(1)
  }

  /**
   * Reads a single key from a plist file.
   * Supports both XML plist format (parsed in Kotlin) and binary plist format
   * (converted using plutil, which is bundled with Xcode/macOS).
   *
   * This can be used by app-specific implementations to read custom plist keys.
   *
   * @return The value for the key, or null if the key doesn't exist or an error occurs
   */
  fun readPlistKey(plistPath: String, key: String): String? {
    return try {
      val file = File(plistPath)
      if (!file.exists()) return null

      val content = file.readText()

      // Check if it's XML plist (starts with XML declaration or plist tag)
      if (content.trimStart().startsWith("<?xml") || content.trimStart().startsWith("<!DOCTYPE plist") || content.trimStart().startsWith("<plist")) {
        parseXmlPlistKey(content, key)
      } else {
        // Binary plist - convert to XML using plutil (bundled with Xcode/macOS)
        convertBinaryPlistAndReadKey(plistPath, key)
      }
    } catch (e: Exception) {
      // Key doesn't exist or other error
      null
    }
  }

  /**
   * Parses a key value from XML plist content.
   *
   * XML plist format:
   * ```xml
   * <plist version="1.0">
   * <dict>
   *     <key>CFBundleVersion</key>
   *     <string>6940515</string>
   *     <key>CFBundleShortVersionString</key>
   *     <string>6.94</string>
   * </dict>
   * </plist>
   * ```
   */
  internal fun parseXmlPlistKey(content: String, key: String): String? {
    // Find the key element
    val keyPattern = Regex("<key>\\s*${Regex.escape(key)}\\s*</key>\\s*<(string|integer|real)>([^<]*)</(string|integer|real)>")
    return keyPattern.find(content)?.groupValues?.get(2)?.trim()
  }

  /**
   * Converts a binary plist to XML using plutil and extracts the key value.
   * plutil is bundled with Xcode command line tools on macOS.
   */
  private fun convertBinaryPlistAndReadKey(plistPath: String, key: String): String? {
    return try {
      // Use plutil to convert binary plist to XML and output to stdout
      val output = TrailblazeProcessBuilderUtils.createProcessBuilder(
        listOf("plutil", "-convert", "xml1", "-o", "-", plistPath),
      ).runProcess {}

      parseXmlPlistKey(output.fullOutput, key)
    } catch (e: Exception) {
      null
    }
  }
}