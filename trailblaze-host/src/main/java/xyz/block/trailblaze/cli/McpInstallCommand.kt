package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

/**
 * Install Trailblaze MCP configuration for AI coding tools.
 *
 * Examples:
 *   trailblaze mcp install              - Configure all supported tools
 *   trailblaze mcp install claude      - Configure Claude Desktop
 *   trailblaze mcp install cursor      - Configure Cursor
 *   trailblaze mcp install goose       - Print Goose configuration instructions
 */
@Command(
  name = "install",
  mixinStandardHelpOptions = true,
  description = ["Install MCP configuration for AI coding tools"],
)
class McpInstallCommand : Callable<Int> {

  @Parameters(
    index = "0",
    arity = "0..1",
    description = ["Target tool: claude, cursor, goose (omit to install all)"],
  )
  var target: String? = null

  private val json = Json { prettyPrint = true }

  override fun call(): Int {
    val targets = if (target != null) listOf(target!!) else listOf("claude", "cursor", "goose")

    var hasError = false
    for (t in targets) {
      when (t.lowercase()) {
        "claude" -> if (!installClaude()) hasError = true
        "cursor" -> if (!installCursor()) hasError = true
        "goose" -> printGooseInstructions()
        else -> {
          Console.log("Unknown target: $t (supported: claude, cursor, goose)")
          hasError = true
        }
      }
    }

    return if (hasError) 1 else 0
  }

  private fun claudeDesktopConfigDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
      os.contains("mac") -> File(home, "Library/Application Support/Claude")
      os.contains("win") -> File(System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path, "Claude")
      else -> File(home, ".config/Claude") // Linux and other Unix-like systems
    }
  }

  private fun installClaude(): Boolean {
    var installed = false

    // Claude Desktop
    val desktopConfigDir = claudeDesktopConfigDir()
    if (desktopConfigDir.exists()) {
      val configFile = File(desktopConfigDir, "claude_desktop_config.json")
      writeMcpConfig(configFile, includeType = false)
      Console.log("Configured Claude Desktop: $configFile")
      installed = true
    } else {
      Console.log("Skipping Claude Desktop: config directory not found at $desktopConfigDir")
    }

    // Claude Code project-level config (.mcp.json in current directory)
    val mcpJsonFile = File(System.getProperty("user.dir"), ".mcp.json")
    writeMcpConfig(mcpJsonFile, includeType = false)
    Console.log("Configured Claude Code (project): $mcpJsonFile")
    installed = true

    return installed
  }

  private fun writeMcpConfig(configFile: File, includeType: Boolean) {
    val config = if (configFile.exists()) {
      json.parseToJsonElement(configFile.readText()).jsonObject
    } else {
      JsonObject(emptyMap())
    }

    val mcpServers = config["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())

    val updated = buildJsonObject {
      for ((key, value) in config) {
        if (key != "mcpServers") put(key, value)
      }
      putJsonObject("mcpServers") {
        for ((key, value) in mcpServers) {
          if (key != "trailblaze") put(key, value)
        }
        putJsonObject("trailblaze") {
          if (includeType) put("type", "stdio")
          put("command", "trailblaze")
          putJsonArray("args") { add(JsonPrimitive("mcp")) }
        }
      }
    }

    configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))
  }

  private fun installCursor(): Boolean {
    val configDir = File(System.getProperty("user.home"), ".cursor")
    val configFile = File(configDir, "mcp.json")

    if (!configDir.exists()) {
      Console.log("Skipping Cursor: config directory not found at $configDir")
      return false
    }

    writeMcpConfig(configFile, includeType = false)
    Console.log("Configured Cursor: $configFile")
    return true
  }

  private fun printGooseInstructions() {
    val configPath = "~/.config/goose/config.yaml"
    Console.log("Goose requires manual configuration (YAML format).")
    Console.log("Add the following to $configPath under the 'extensions' section:")
    Console.log("")
    Console.log("  trailblaze:")
    Console.log("    type: stdio")
    Console.log("    cmd: trailblaze")
    Console.log("    args:")
    Console.log("      - mcp")
    Console.log("    enabled: true")
  }
}
