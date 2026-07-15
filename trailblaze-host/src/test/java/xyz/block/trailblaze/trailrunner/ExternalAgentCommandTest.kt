package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The command line handed to the vendor CLI is the external contract of the supervisor — these
 * tests pin the permission translation and MCP wiring, not the argument order.
 */
class ExternalAgentCommandTest {
  private val cwd = File(".")

  private fun request(
    agentType: ExternalAgentType,
    sandbox: String? = null,
    extraDirs: List<String> = emptyList(),
  ) = ExternalAgentRunRequest(agentType = agentType, prompt = "compose a trail", sandbox = sandbox, extraDirs = extraDirs)

  @Test
  fun claudeGetsTrailblazeMcpServerAndAllowlist() {
    val command = externalAgentCommand(request(ExternalAgentType.CLAUDE), cwd, "claude")

    // Headless -p mode cannot prompt for approval, so the injected MCP server's tools must be
    // pre-allowed or every call is silently denied.
    assertTrue(command.containsSequence("--allowedTools", "mcp__trailblaze"))
    // The MCP server re-enters the daemon's own classpath (never a PATH-resolved `trailblaze`,
    // which can be a different build) and is told which port this daemon serves on.
    assertTrue(mcpConfig(command).contains(MCP_PROXY_MAIN_CLASS))
    assertTrue(mcpConfig(command).contains("\"-cp\""))
    assertTrue(mcpConfig(command).contains("TRAILRUNNER_DAEMON_PORT"))
    assertEquals("compose a trail", command.last())
  }

  @Test
  fun claudeWorkspaceWriteMapsToAcceptEdits() {
    val command = externalAgentCommand(request(ExternalAgentType.CLAUDE, sandbox = "workspace-write"), cwd, "claude")

    assertTrue(command.containsSequence("--permission-mode", "acceptEdits"))
    assertFalse(command.contains("--dangerously-skip-permissions"))
  }

  @Test
  fun claudeReadOnlyStaysInDefaultPermissionMode() {
    val command = externalAgentCommand(request(ExternalAgentType.CLAUDE, sandbox = "read-only"), cwd, "claude")

    assertFalse(command.contains("--permission-mode"))
    assertFalse(command.contains("--dangerously-skip-permissions"))
    assertTrue(command.containsSequence("--allowedTools", "mcp__trailblaze"))
  }

  @Test
  fun claudeDangerFullAccessSkipsPermissionChecks() {
    val command = externalAgentCommand(request(ExternalAgentType.CLAUDE, sandbox = "danger-full-access"), cwd, "claude")

    assertTrue(command.contains("--dangerously-skip-permissions"))
  }

  @Test
  fun codexGetsSandboxAndTrailblazeMcpServer() {
    val command = externalAgentCommand(request(ExternalAgentType.CODEX, sandbox = "read-only"), cwd, "codex")

    assertTrue(command.containsSequence("--sandbox", "read-only"))
    val overrides = command.windowed(2).filter { it[0] == "-c" }.map { it[1] }
    assertTrue(overrides.any { it.startsWith("mcp_servers.trailblaze.command=") })
    // Same self-classpath spawn the Claude branch gets, carried as TOML overrides.
    assertTrue(overrides.any { it.startsWith("""mcp_servers.trailblaze.args=["-cp",""") && it.contains(MCP_PROXY_MAIN_CLASS) })
    assertTrue(overrides.any { it.startsWith("mcp_servers.trailblaze.env={TRAILRUNNER_DAEMON_PORT=") })
    assertTrue(command.last().endsWith("compose a trail"))
  }

  @Test
  fun claudeExtraDirsAreGrantedViaAddDir() {
    val command = externalAgentCommand(request(ExternalAgentType.CLAUDE, extraDirs = listOf("/tmp/tb-tape")), cwd, "claude")

    assertTrue(command.containsSequence("--add-dir", "/tmp/tb-tape"))
  }

  @Test
  fun claudeWithoutExtraDirsOmitsAddDir() {
    val command = externalAgentCommand(request(ExternalAgentType.CLAUDE), cwd, "claude")

    assertFalse(command.contains("--add-dir"))
  }

  @Test
  fun codexIgnoresExtraDirsSinceItsSandboxDoesNotBlockReads() {
    val command = externalAgentCommand(request(ExternalAgentType.CODEX, extraDirs = listOf("/tmp/tb-tape")), cwd, "codex")

    assertFalse(command.contains("--add-dir"))
  }

  @Test
  fun unknownAccessLevelFallsBackToWorkspaceWrite() {
    val command = externalAgentCommand(request(ExternalAgentType.CODEX, sandbox = "yolo"), cwd, "codex")

    assertTrue(command.containsSequence("--sandbox", "workspace-write"))
  }

  @Test
  fun claudeReplyResumesTheThreadWithTheRawPromptAndKeepsItsGrants() {
    val command = externalAgentCommand(
      request(ExternalAgentType.CLAUDE, sandbox = "workspace-write"),
      cwd,
      "claude",
      resume = ExternalAgentResume(threadId = "session-1", prompt = "make the verify stricter"),
    )

    assertTrue(command.containsSequence("--resume", "session-1"))
    assertTrue(command.containsSequence("--allowedTools", "mcp__trailblaze"))
    assertTrue(command.containsSequence("--permission-mode", "acceptEdits"))
    assertEquals("make the verify stricter", command.last())
  }

  @Test
  fun artifactsRootReachesBothVendorsSystemContext() {
    val logs = File("/tmp/tb-logs")

    val claude = externalAgentCommand(request(ExternalAgentType.CLAUDE), cwd, "claude", artifactsRoot = logs)
    val appended = claude[claude.indexOf("--append-system-prompt") + 1]
    assertTrue(appended.contains(logs.absolutePath))

    val codex = externalAgentCommand(request(ExternalAgentType.CODEX), cwd, "codex", artifactsRoot = logs)
    assertTrue(codex.last().contains(logs.absolutePath))
  }

  @Test
  fun promptPreambleRidesTheChildPromptAheadOfTheHumansWords() {
    // The preamble is the UI's under-the-hood session recipe: the child must receive it, and the
    // human's own message must still close the prompt (it completes the recipe's lead-in).
    val request = ExternalAgentRunRequest(
      agentType = ExternalAgentType.CLAUDE,
      prompt = "add someone to a pool",
      promptPreamble = "You are my partner for composing a trail.\n\nThe flow I want to build:",
    )

    val claude = externalAgentCommand(request, cwd, "claude")
    assertTrue(claude.last().startsWith("You are my partner for composing a trail."))
    assertTrue(claude.last().endsWith("add someone to a pool"))

    val codex = externalAgentCommand(request.copy(agentType = ExternalAgentType.CODEX), cwd, "codex")
    assertTrue(codex.last().contains("You are my partner for composing a trail."))
    assertTrue(codex.last().endsWith("add someone to a pool"))
  }

  @Test
  fun codexReplyResumesTheThreadWithTheRawPrompt() {
    val command = externalAgentCommand(
      request(ExternalAgentType.CODEX),
      cwd,
      "codex",
      resume = ExternalAgentResume(threadId = "thread-1", prompt = "now run it"),
    )

    // Exec-level options must precede the subcommand: `codex exec resume` rejects options like
    // --sandbox, so the resume token (and the prompt) come after everything clap parses at the
    // exec level.
    assertTrue(command.containsSequence("resume", "thread-1"))
    assertTrue(command.indexOf("--sandbox") < command.indexOf("resume"))
    // The reply must go verbatim — re-prepending the UI contract would pollute the conversation.
    assertEquals("now run it", command.last())
    assertFalse(command.contains("-C"))
  }

  @Test
  fun bothVendorsTerminateFlagsBeforeThePrompt() {
    // A reply can legitimately start with "-" ("- step 3 is wrong…"). Without the "--" terminator
    // the CLI parses it as flags: the turn fails, or worse, a flag-shaped reply overrides the
    // sandbox level the request pinned.
    val reply = "--dangerously-looking reply text"
    for (agentType in listOf(ExternalAgentType.CLAUDE, ExternalAgentType.CODEX)) {
      val command = externalAgentCommand(
        request(agentType),
        cwd,
        "cli",
        resume = ExternalAgentResume(threadId = "t-1", prompt = reply),
      )
      assertTrue(command.containsSequence("--", reply), "$agentType must pass the prompt after --")
      assertEquals(reply, command.last())
    }
  }

  @Test
  fun claudeUnderPermissionsRoutesApprovalsThroughTheProxyToolAndCarriesTheRunId() {
    val command = externalAgentCommand(
      request(ExternalAgentType.CLAUDE, sandbox = "workspace-write"),
      cwd,
      "claude",
      runId = "run-abc123",
    )

    // The headless CLI's "requires approval" prompt is routed to the proxy-injected tool so a human
    // can approve it in Trail Runner.
    assertTrue(command.containsSequence("--permission-prompt-tool", "mcp__trailblaze__approval_prompt"))
    // The spawned MCP server must know which run to route approvals through.
    assertTrue(mcpConfig(command).contains("TRAILRUNNER_PERMISSION_RUN_ID"))
    assertTrue(mcpConfig(command).contains("run-abc123"))
  }

  @Test
  fun claudeReadOnlyStillRoutesApprovalsThroughTheProxyTool() {
    val command = externalAgentCommand(
      request(ExternalAgentType.CLAUDE, sandbox = "read-only"),
      cwd,
      "claude",
      runId = "run-ro",
    )

    assertTrue(command.containsSequence("--permission-prompt-tool", "mcp__trailblaze__approval_prompt"))
    assertTrue(mcpConfig(command).contains("run-ro"))
  }

  @Test
  fun claudeDangerFullAccessNeitherPromptsNorCarriesARunId() {
    val command = externalAgentCommand(
      request(ExternalAgentType.CLAUDE, sandbox = "danger-full-access"),
      cwd,
      "claude",
      runId = "run-danger",
    )

    // Nothing to approve when every permission check is skipped.
    assertFalse(command.contains("--permission-prompt-tool"))
    assertFalse(mcpConfig(command).contains("TRAILRUNNER_PERMISSION_RUN_ID"))
  }

  @Test
  fun codexIsUnaffectedByPermissionRouting() {
    val command = externalAgentCommand(
      request(ExternalAgentType.CODEX, sandbox = "read-only"),
      cwd,
      "codex",
      runId = "run-codex",
    )

    assertFalse(command.contains("--permission-prompt-tool"))
    assertFalse(command.any { it.contains("TRAILRUNNER_PERMISSION_RUN_ID") })
  }

  /** The single JSON argument that follows `--mcp-config`. */
  private fun mcpConfig(command: List<String>): String =
    command[command.indexOf("--mcp-config") + 1]

  private fun List<String>.containsSequence(vararg tokens: String): Boolean =
    windowed(tokens.size).any { it == tokens.toList() }
}
