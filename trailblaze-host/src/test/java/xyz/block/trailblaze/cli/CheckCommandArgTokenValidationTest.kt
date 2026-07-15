package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the check-time arg-token validation contract — [CheckCommand.validateArgTokensInTrail], the
 * pure per-trail scan the `check` phase runs over every workspace trail. Observable behavior:
 *
 *  1. A `{{args.x}}` / `${args.x}` token whose top-level name is DECLARED under `config.args:` is
 *     clean.
 *  2. A token referencing an UNDECLARED arg is a finding (naming the missing arg + declared set).
 *  3. A MALFORMED token (an expression, not a plain dotted path) is a finding — tokens are dotted
 *     paths only, permanently, because they're LLM-writable.
 *  4. A declared-but-UNUSED arg is fine (declaring a superset is legitimate for shared arg sets).
 *  5. Bare `{{x}}` / `{{memory.x}}` tokens are the pre-existing memory grammar and are left alone.
 *  6. A trail with no `config.args:` block and no `args.` tokens is clean.
 *
 * Trails are written in the unified format (the parseable shape `extractTrailConfig` lowers); the
 * token scan itself is format-agnostic since it runs over the raw YAML text.
 */
class CheckCommandArgTokenValidationTest {

  private val command = CheckCommand()

  private fun validate(yaml: String): List<String> = command.validateArgTokensInTrail(yaml)

  @Test
  fun `declared arg referenced by a token is clean`() {
    val yaml = """
      config:
        id: test/args
        args:
          recipient:
            type: string
      trail:
      - step: Send a message to {{args.recipient}}
    """.trimIndent()
    assertEquals(emptyList(), validate(yaml))
  }

  @Test
  fun `undeclared arg reference is a finding`() {
    val yaml = """
      config:
        id: test/args
        args:
          recipient:
            type: string
      trail:
      - step: Reply to {{args.sender}}
    """.trimIndent()
    val errors = validate(yaml)
    assertEquals(1, errors.size)
    assertTrue(errors.single().contains("Undeclared arg reference"), errors.single())
    assertTrue(errors.single().contains("sender"), errors.single())
  }

  @Test
  fun `arg token with no config args block at all is a finding`() {
    val yaml = """
      config:
        id: test/args
      trail:
      - step: Send to {{args.recipient}}
    """.trimIndent()
    val errors = validate(yaml)
    assertEquals(1, errors.size)
    assertTrue(errors.single().contains("Undeclared arg reference"), errors.single())
  }

  @Test
  fun `expression token is a malformed finding`() {
    val yaml = """
      config:
        id: test/args
        args:
          count:
            type: integer
      trail:
      - step: Use {{args.count + 1}} retries
    """.trimIndent()
    val errors = validate(yaml)
    assertEquals(1, errors.size)
    assertTrue(errors.single().contains("Malformed args token"), errors.single())
  }

  @Test
  fun `dotted-path access into a declared object arg is clean`() {
    // v1 executes string/integer/boolean, but the grammar accepts object today; dot-path access
    // resolves against the top-level declared name, which is all check-time validation asserts.
    val yaml = """
      config:
        id: test/args
        args:
          reply_to:
            type: object
      trail:
      - step: Email {{args.reply_to.email}}
    """.trimIndent()
    assertEquals(emptyList(), validate(yaml))
  }

  @Test
  fun `declared-but-unused arg is not a finding`() {
    val yaml = """
      config:
        id: test/args
        args:
          recipient:
            type: string
          unused:
            type: string
      trail:
      - step: Send to {{args.recipient}}
    """.trimIndent()
    assertEquals(emptyList(), validate(yaml))
  }

  @Test
  fun `bare and memory tokens are left untouched`() {
    val yaml = """
      config:
        id: test/args
        memory:
          base_url: https://example.com
      trail:
      - step: Open {{base_url}} then {{memory.base_url}}
    """.trimIndent()
    assertEquals(emptyList(), validate(yaml))
  }

  @Test
  fun `trail with neither args block nor args tokens is clean`() {
    val yaml = """
      config:
        id: test/args
      trail:
      - step: Tap the login button
    """.trimIndent()
    assertEquals(emptyList(), validate(yaml))
  }

  @Test
  fun `multiple findings across tokens are all reported`() {
    val yaml = """
      config:
        id: test/args
        args:
          recipient:
            type: string
      trail:
      - step: Send to {{args.recipient}} and cc {{args.cc}} with {{args.count * 2}}
    """.trimIndent()
    val errors = validate(yaml)
    assertEquals(2, errors.size)
    assertTrue(errors.any { it.contains("Undeclared arg reference") && it.contains("cc") }, "$errors")
    assertTrue(errors.any { it.contains("Malformed args token") }, "$errors")
  }

  // --- Phase-level exit-code contract (runArgTokenValidationPhase) ---
  // The per-trail scan above is the finding engine; these pin that a finding actually FAILS the
  // build (EXIT_TYPE_ERROR flows into check's worst-of-all-phases max) and that the no-trails
  // workspace shape stays clean.

  @Test
  fun `phase fails the build when any workspace trail carries an arg-token finding`() {
    val workspace = java.nio.file.Files.createTempDirectory("trailblaze-check-args").toFile()
    try {
      val trailsDir = java.io.File(workspace, "trails").apply { mkdirs() }
      java.io.File(trailsDir, "clean.trail.yaml").writeText(
        """
        config:
          id: test/clean
          args:
            recipient:
              type: string
        trail:
        - step: Send a message to {{args.recipient}}
        """.trimIndent(),
      )
      assertEquals(CheckCommand.EXIT_OK, command.runArgTokenValidationPhase(workspace))

      java.io.File(trailsDir, "bad.trail.yaml").writeText(
        """
        config:
          id: test/bad
        trail:
        - step: Reply to {{args.sender}}
        """.trimIndent(),
      )
      assertEquals(CheckCommand.EXIT_TYPE_ERROR, command.runArgTokenValidationPhase(workspace))
    } finally {
      workspace.deleteRecursively()
    }
  }

  @Test
  fun `phase is clean for a workspace with no trails directory`() {
    val workspace = java.nio.file.Files.createTempDirectory("trailblaze-check-args-empty").toFile()
    try {
      assertEquals(CheckCommand.EXIT_OK, command.runArgTokenValidationPhase(workspace))
    } finally {
      workspace.deleteRecursively()
    }
  }
}
