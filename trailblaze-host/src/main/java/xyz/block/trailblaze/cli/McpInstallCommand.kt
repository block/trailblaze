package xyz.block.trailblaze.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
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

  @Option(
    names = ["--port"],
    description = ["MCP endpoint port (default: 52525)"],
  )
  var port: Int = 52525

  private val json = Json { prettyPrint = true }

  override fun call(): Int {
    val endpoint = "http://localhost:$port/mcp"
    val targets = if (target != null) listOf(target!!) else listOf("claude", "cursor", "goose")

    var hasError = false
    for (t in targets) {
      when (t.lowercase()) {
        "claude" -> if (!installClaude(endpoint)) hasError = true
        "cursor" -> if (!installCursor(endpoint)) hasError = true
        "goose" -> printGooseInstructions(endpoint)
        else -> {
          Console.log("Unknown target: $t (supported: claude, cursor, goose)")
          hasError = true
        }
      }
    }

    return if (hasError) 1 else 0
  }

  private fun installClaude(endpoint: String): Boolean {
    val configDir = File(System.getProperty("user.home"), "Library/Application Support/Claude")
    val configFile = File(configDir, "claude_desktop_config.json")

    if (!configDir.exists()) {
      Console.log("Skipping Claude Desktop: config directory not found at $configDir")
      return false
    }

    val config = if (configFile.exists()) {
      json.parseToJsonElement(configFile.readText()).jsonObject
    } else {
      JsonObject(emptyMap())
    }

    val mcpServers = config["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())

    val updated = buildJsonObject {
      // Preserve existing top-level keys
      for ((key, value) in config) {
        if (key != "mcpServers") put(key, value)
      }
      putJsonObject("mcpServers") {
        // Preserve existing servers
        for ((key, value) in mcpServers) {
          if (key != "trailblaze") put(key, value)
        }
        putJsonObject("trailblaze") {
          put("url", endpoint)
        }
      }
    }

    configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))
    Console.log("Configured Claude Desktop: $configFile")
    return true
  }

  private fun installCursor(endpoint: String): Boolean {
    val configDir = File(System.getProperty("user.home"), ".cursor")
    val configFile = File(configDir, "mcp.json")

    if (!configDir.exists()) {
      Console.log("Skipping Cursor: config directory not found at $configDir")
      return false
    }

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
          put("url", endpoint)
        }
      }
    }

    configFile.writeText(json.encodeToString(JsonObject.serializer(), updated))
    Console.log("Configured Cursor: $configFile")
    return true
  }

  private fun printGooseInstructions(endpoint: String) {
    val configPath = "~/.config/goose/config.yaml"
    Console.log("Goose requires manual configuration (YAML format).")
    Console.log("Add the following to $configPath under the 'mcpServers' section:")
    Console.log("")
    Console.log("  trailblaze:")
    Console.log("    type: streamable_http")
    Console.log("    url: $endpoint")
  }
}
