package xyz.block.trailblaze.docs

/**
 * Marks a test method as a CLI usage scenario for documentation generation.
 *
 * The docs generator reads these annotations from test source files and produces
 * cli-scenarios.md with a 1-1 mapping between documented usage patterns and passing tests.
 *
 * @param title User-facing scenario title (e.g., "Discover available tools")
 * @param commands Example CLI/MCP commands shown in docs (one per line)
 * @param description Optional longer description of the scenario
 * @param category Grouping category (e.g., "Tool Discovery", "Direct Tool Execution")
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Scenario(
  val title: String,
  val commands: Array<String> = [],
  val description: String = "",
  val category: String = "",
)
