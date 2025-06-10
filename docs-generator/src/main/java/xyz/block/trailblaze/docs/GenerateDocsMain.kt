package xyz.block.trailblaze.docs

import java.io.File

fun main() {
  val currDir = File(System.getProperty("user.dir"))
  val gitDir = if (currDir.name == "docs-generator") {
    currDir.parentFile
  } else {
    currDir
  }
  val docsDir = File(gitDir, "docs").apply { mkdirs() }
  val generatedDir = File(docsDir, "generated").apply { mkdirs() }
  val generatedFunctionsDocsDir = File(generatedDir, "functions").apply { mkdirs() }

  // Generate
  DocsGenerator(
    generatedDir = generatedDir,
    generatedFunctionsDocsDir = generatedFunctionsDocsDir
  ).generate()
}
