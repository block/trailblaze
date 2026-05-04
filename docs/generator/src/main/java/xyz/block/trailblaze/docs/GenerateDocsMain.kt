package xyz.block.trailblaze.docs

import java.io.File

fun main() {
  val currDir = File(System.getProperty("user.dir"))
  val gitDir = currDir.parentFile.parentFile
  val docsDir = File(gitDir, "docs").apply { mkdirs() }
  val generatedDir = File(docsDir, "generated").apply { mkdirs() }
  val generatedFunctionsDocsDir = File(generatedDir, "functions").apply { mkdirs() }

  // Generate Tools documentation
  DocsGenerator(
    generatedDir = generatedDir,
    generatedFunctionsDocsDir = generatedFunctionsDocsDir
  ).generate()

  // Generate LLM Models documentation
  LlmConfigDocsGenerator(
    generatedDir = generatedDir,
  ).generate()

  // Generate external-config documentation for binary users
  ExternalConfigDocsGenerator(
    generatedDir = generatedDir,
    opensourceRoot = gitDir,
  ).generate()

  // Generate CLI documentation (goes in main docs folder, not generated/)
  CliDocsGenerator(
    docsDir = docsDir
  ).generate()

  // Generate CLI Scenarios documentation (from @Scenario test annotations)
  CliScenariosGenerator(
    generatedDir = generatedDir,
    gitDir = gitDir,
  ).generate()

}
