package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Known MCP clients that support sampling.
 *
 * When an MCP client connects and uses sampling (delegating LLM calls back to the client),
 * we identify the client for tracking and analytics purposes.
 *
 * New clients should be added here as they become known. Unknown clients
 * are represented by [UNKNOWN].
 *
 * To add a new client:
 * 1. Add an enum entry with `id`, `displayName`, and `matchPatterns`
 * 2. The `matchPatterns` should include substrings that appear in the client's `clientInfo.name`
 */
@Serializable
enum class McpClient(
  /** Lowercase identifier used in model IDs and logs */
  val id: String,
  /** Human-readable display name */
  val displayName: String,
  /**
   * Patterns to match against the raw clientInfo.name (case-insensitive).
   * A client matches if the lowercased name contains ALL patterns in any inner list.
   * Example: `listOf(listOf("claude", "desktop"))` matches "Claude Desktop" but not just "Claude".
   */
  @Transient val matchPatterns: List<List<String>> = emptyList(),
) {
  /** Block's Goose AI agent */
  GOOSE(
    id = "goose",
    displayName = "Goose",
    matchPatterns = listOf(listOf("goose")),
  ),

  /** Visual Studio Code with Copilot Chat */
  VSCODE(
    id = "vscode",
    displayName = "VS Code",
    matchPatterns = listOf(listOf("vscode"), listOf("visual studio code")),
  ),

  /** Cursor IDE */
  CURSOR(
    id = "cursor",
    displayName = "Cursor",
    matchPatterns = listOf(listOf("cursor")),
  ),

  /** Anthropic's Claude Desktop app */
  CLAUDE_DESKTOP(
    id = "claude-desktop",
    displayName = "Claude Desktop",
    matchPatterns = listOf(listOf("claude", "desktop")),
  ),

  /** Firebender coding agent */
  FIREBENDER(
    id = "firebender",
    displayName = "Firebender",
    matchPatterns = listOf(listOf("firebender")),
  ),

  /** Windsurf IDE */
  WINDSURF(
    id = "windsurf",
    displayName = "Windsurf",
    matchPatterns = listOf(listOf("windsurf")),
  ),

  /** Unknown or unrecognized MCP client */
  UNKNOWN(
    id = "unknown",
    displayName = "Unknown Client",
    matchPatterns = emptyList(),
  );

  /**
   * Checks if this client matches the given raw client name.
   *
   * @param rawName The raw clientInfo.name from MCP handshake (case-insensitive)
   * @return true if any of [matchPatterns] match
   */
  fun matches(rawName: String): Boolean {
    val lower = rawName.lowercase()
    return matchPatterns.any { patterns ->
      patterns.all { pattern -> lower.contains(pattern) }
    }
  }

  companion object {
    /**
     * Identifies the MCP client from the raw clientInfo.name string.
     *
     * @param clientName The raw client name from MCP initialize handshake
     * @return The identified [McpClient], or [UNKNOWN] if not recognized
     */
    fun fromClientName(clientName: String?): McpClient {
      if (clientName.isNullOrBlank()) return UNKNOWN
      return knownClients.firstOrNull { it.matches(clientName) } ?: UNKNOWN
    }

    /**
     * Returns all known clients (excluding [UNKNOWN]).
     */
    val knownClients: List<McpClient>
      get() = entries.filter { it != UNKNOWN }
  }
}
