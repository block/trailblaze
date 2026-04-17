package xyz.block.trailblaze.docs

import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Generates cli-scenarios.md from @Scenario annotations in test source files.
 *
 * Parses Kotlin test sources for @Scenario annotations and produces a markdown
 * document grouping scenarios by category. Each scenario shows the title,
 * description, example commands, and which test method verifies it.
 */
class CliScenariosGenerator(
  private val generatedDir: File,
  private val gitDir: File,
) {

  /** Parsed scenario entry extracted from a test source file. */
  private data class ScenarioEntry(
    val title: String,
    val commands: List<String>,
    val description: String,
    val category: String,
    val testClass: String,
    val testMethod: String,
  )

  /** Test source directories to scan for @Scenario annotations. */
  private val testDirs =
    listOf(
      "trailblaze-host/src/test/java",
      "trailblaze-server/src/test/kotlin",
    )

  fun generate() {
    val scenarios = mutableListOf<ScenarioEntry>()

    for (testDir in testDirs) {
      val dir = File(gitDir, testDir)
      if (!dir.exists()) continue
      dir
        .walkTopDown()
        .filter { it.extension == "kt" }
        .forEach { file -> scenarios.addAll(parseScenarios(file)) }
    }

    if (scenarios.isEmpty()) {
      Console.log("No @Scenario annotations found; skipping cli-scenarios.md generation.")
      return
    }

    writeMarkdown(scenarios, File(generatedDir, "cli-scenarios.md"))
    Console.log(
      "CLI scenarios generated: ${File(generatedDir, "cli-scenarios.md").absolutePath} " +
        "(${scenarios.size} scenarios)"
    )
  }

  // ---------------------------------------------------------------------------
  // Source parsing
  // ---------------------------------------------------------------------------

  /**
   * Extracts @Scenario annotations from a Kotlin test source file.
   *
   * The parser finds each @Scenario block, extracts its parameters, then looks
   * ahead for the next @Test fun declaration to capture the test method name.
   */
  private fun parseScenarios(file: File): List<ScenarioEntry> {
    val source = file.readText()
    val className = file.nameWithoutExtension
    val entries = mutableListOf<ScenarioEntry>()

    // Match @Scenario(...) blocks. The annotation can span multiple lines.
    // We find the start of each @Scenario and then match the balanced parens.
    val scenarioStarts = Regex("""@Scenario\s*\(""").findAll(source)

    for (match in scenarioStarts) {
      val annotationBody = extractBalancedParens(source, match.range.last)
        ?: continue

      val title = extractStringParam(annotationBody, "title") ?: continue
      val commands = extractArrayParam(annotationBody, "commands")
      val description = extractStringParam(annotationBody, "description") ?: ""
      val category = extractStringParam(annotationBody, "category") ?: ""

      // Find the test method name: look for @Test fun `...` or @Test fun name(
      // after the annotation end position.
      val afterAnnotation = source.substring(match.range.first)
      val testMethodMatch =
        Regex("""@Test\s+fun\s+`([^`]+)`|@Test\s+fun\s+(\w+)\s*\(""").find(afterAnnotation)
      val testMethod = testMethodMatch?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        ?: "unknown"

      entries.add(
        ScenarioEntry(
          title = title,
          commands = commands,
          description = description,
          category = category.ifEmpty { "General" },
          testClass = className,
          testMethod = testMethod,
        )
      )
    }

    return entries
  }

  /** Extracts text inside balanced parentheses starting at the given '(' position. */
  private fun extractBalancedParens(source: String, openParenIndex: Int): String? {
    if (openParenIndex >= source.length || source[openParenIndex] != '(') return null
    var depth = 1
    var i = openParenIndex + 1
    while (i < source.length && depth > 0) {
      when (source[i]) {
        '(' -> depth++
        ')' -> depth--
      }
      i++
    }
    return if (depth == 0) source.substring(openParenIndex + 1, i - 1) else null
  }

  /** Extracts a named String parameter value from an annotation body. */
  private fun extractStringParam(body: String, name: String): String? {
    // Match: name = "value" or name = \n "value" (multiline, possibly with concatenation)
    // Handle both simple strings and multi-line strings joined by Kotlin string templates.
    val pattern = Regex("""$name\s*=\s*"((?:[^"\\]|\\.)*)"""")
    val match = pattern.find(body) ?: return null
    return unescapeKotlinString(match.groupValues[1])
  }

  /** Extracts a named Array<String> parameter value from an annotation body. */
  private fun extractArrayParam(body: String, name: String): List<String> {
    // Find the array block: name = [ ... ] or name = \n [ ... ]
    val arrayStart = Regex("""$name\s*=\s*\[""").find(body) ?: return emptyList()
    val bracket = body.indexOf('[', arrayStart.range.first)
    if (bracket < 0) return emptyList()

    // Find matching ]
    var depth = 1
    var i = bracket + 1
    while (i < body.length && depth > 0) {
      when (body[i]) {
        '[' -> depth++
        ']' -> depth--
      }
      i++
    }
    if (depth != 0) return emptyList()

    val arrayContent = body.substring(bracket + 1, i - 1)

    // Extract individual string literals from the array content
    val stringPattern = Regex(""""((?:[^"\\]|\\.)*)"""")
    return stringPattern.findAll(arrayContent).map { match ->
      unescapeKotlinString(match.groupValues[1])
    }.toList()
  }

  /**
   * Unescapes a Kotlin string literal as it appears in source code.
   *
   * Processes escape sequences character-by-character so that `\\n` (source: backslash
   * backslash n) becomes the two-character literal `\n`, while `\n` (source: backslash n)
   * becomes a real newline.
   */
  private fun unescapeKotlinString(raw: String): String = buildString {
    var i = 0
    while (i < raw.length) {
      if (raw[i] == '\\' && i + 1 < raw.length) {
        when (raw[i + 1]) {
          'n' -> append('\n')
          't' -> append('\t')
          '\\' -> append('\\')
          '"' -> append('"')
          else -> {
            append(raw[i])
            append(raw[i + 1])
          }
        }
        i += 2
      } else {
        append(raw[i])
        i++
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Markdown output
  // ---------------------------------------------------------------------------

  private fun writeMarkdown(scenarios: List<ScenarioEntry>, outputFile: File) {
    val grouped = scenarios.groupBy { it.category }.toSortedMap()

    val content = buildString {
      appendLine("# CLI Usage Scenarios")
      appendLine()
      appendLine(
        "Auto-generated from `@Scenario` test annotations. " +
          "Each scenario has a passing test that verifies the behavior."
      )
      appendLine()

      for ((category, categoryScenarios) in grouped) {
        appendLine("## $category")
        appendLine()

        for (scenario in categoryScenarios) {
          appendLine("### ${scenario.title}")
          appendLine()

          if (scenario.description.isNotEmpty()) {
            appendLine(scenario.description)
            appendLine()
          }

          if (scenario.commands.isNotEmpty()) {
            // Separate CLI commands (start with "trailblaze") from MCP commands
            val cliCommands = scenario.commands.filter { it.startsWith("trailblaze") }
            val mcpCommands = scenario.commands.filter { !it.startsWith("trailblaze") }

            if (cliCommands.isNotEmpty()) {
              appendLine("**CLI:**")
              appendLine()
              appendLine("```bash")
              cliCommands.forEach { appendLine(it) }
              appendLine("```")
              appendLine()
            }

            if (mcpCommands.isNotEmpty()) {
              appendLine("**MCP:**")
              appendLine()
              appendLine("```")
              mcpCommands.forEach { appendLine(it) }
              appendLine("```")
              appendLine()
            }
          }

          appendLine("_Verified by: `${scenario.testClass}.${scenario.testMethod}`_")
          appendLine()
          appendLine("---")
          appendLine()
        }
      }

      appendLine(DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE)
    }

    outputFile.writeText(content)
  }
}
