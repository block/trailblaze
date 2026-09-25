package xyz.block.trailblaze.scripting

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins when [ScriptedToolImportAnalyzer] reuses an earlier verdict instead of forking esbuild.
 *
 * Every session start builds a fresh analyzer and analyzes every declared tool, so without reuse a
 * target with dozens of tools paid one esbuild fork per tool on every run. The fake esbuild here
 * records each invocation and writes whatever metafile the test staged, so the tests see exactly
 * how many analyses ran and can steer the verdict without a real toolchain.
 */
class ScriptedToolImportAnalyzerCacheTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var toolsDir: File
  private lateinit var invocations: File
  private lateinit var stagedMetafile: File
  private lateinit var fakeEsbuild: File

  @Before
  fun setup() {
    toolsDir = tempFolder.newFolder("tools")
    invocations = File(tempFolder.root, "invocations.log").apply { writeText("") }
    stagedMetafile = File(tempFolder.root, "staged-meta.json")
    fakeEsbuild = File(tempFolder.root, "esbuild").apply {
      writeText(
        """
        |#!/bin/sh
        |printf '%s\n' "${'$'}1" >> '${invocations.absolutePath}'
        |for arg in "${'$'}@"; do
        |  case "${'$'}arg" in --metafile=*) cp '${stagedMetafile.absolutePath}' "${'$'}{arg#--metafile=}" ;; esac
        |done
        """.trimMargin(),
      )
      setExecutable(true)
    }
  }

  @Test
  fun `a fresh analyzer reuses the verdict for an unchanged tool`() = runBlocking {
    val tool = writeTool("tool.ts", "export const a = 1;")
    stageMetafile(importsOf = mapOf("tool.ts" to listOf("node:fs")))

    val first = newAnalyzer().analyze(tool)
    val second = newAnalyzer().analyze(tool)

    assertTrue(first.requiresHost)
    assertEquals(first, second)
    assertEquals(1, analysesRun(), "the second session must not fork esbuild for an unchanged tool")
  }

  @Test
  fun `editing the tool re-analyzes it and picks up the new verdict`() = runBlocking {
    val tool = writeTool("tool.ts", "import 'node:fs';")
    stageMetafile(importsOf = mapOf("tool.ts" to listOf("node:fs")))
    assertTrue(newAnalyzer().analyze(tool).requiresHost)

    edit(tool, "export const onDeviceSafe = true;")
    stageMetafile(importsOf = mapOf("tool.ts" to emptyList()))

    assertFalse(newAnalyzer().analyze(tool).requiresHost)
    assertEquals(2, analysesRun())
  }

  @Test
  fun `editing a file the tool imports re-analyzes the tool`() = runBlocking {
    val tool = writeTool("tool.ts", "import { helper } from './helper';")
    val helper = writeTool("helper.ts", "export const helper = 1;")
    stageMetafile(importsOf = mapOf("tool.ts" to listOf("helper.ts"), "helper.ts" to emptyList()))
    assertFalse(newAnalyzer().analyze(tool).requiresHost)

    // The tool's own bytes are untouched; only its dependency now reaches a Node builtin.
    edit(helper, "import 'node:http'; export const helper = 1;")
    stageMetafile(importsOf = mapOf("tool.ts" to listOf("helper.ts"), "helper.ts" to listOf("node:http")))

    val verdict = newAnalyzer().analyze(tool)
    assertTrue(verdict.requiresHost, "a stale verdict would still say the tool is on-device safe")
    assertEquals(2, analysesRun())
  }

  @Test
  fun `adding a resolver config next to the tool re-analyzes it`() = runBlocking {
    val tool = writeTool("tool.ts", "import { helper } from 'lib/helper';")
    stageMetafile(importsOf = mapOf("tool.ts" to emptyList()))
    newAnalyzer().analyze(tool)

    // A path mapping can send the same import to a different file without touching any file the
    // tool bundled.
    writeTool("tsconfig.json", """{"compilerOptions":{"paths":{"lib/*":["./other/*"]}}}""")

    newAnalyzer().analyze(tool)
    assertEquals(2, analysesRun())
  }

  @Test
  fun `a tool edited while it is being analyzed is not remembered`() = runBlocking {
    val tool = writeTool("tool.ts", "export const a = 1;")
    stageMetafile(importsOf = mapOf("tool.ts" to emptyList()))
    // esbuild has read the old bytes; the edit lands before the analysis finishes.
    fakeEsbuild.appendText("\ntouch '${tool.absolutePath}'\n")

    newAnalyzer().analyze(tool)
    newAnalyzer().analyze(tool)

    assertEquals(2, analysesRun(), "a verdict for the pre-edit bytes must not be paired with the edit")
  }

  @Test
  fun `a failed analysis is not remembered`() = runBlocking {
    val tool = writeTool("tool.ts", "export const a = 1;")
    // No staged metafile: the fake esbuild exits 0 but leaves the metafile empty, so the
    // verdict cannot be read. That must be retried, not cached as "on-device safe".
    stagedMetafile.writeText("")
    assertFalse(newAnalyzer().analyze(tool).requiresHost)

    stageMetafile(importsOf = mapOf("tool.ts" to listOf("node:fs")))
    assertTrue(newAnalyzer().analyze(tool).requiresHost)
    assertEquals(2, analysesRun())
  }

  private fun newAnalyzer() = ScriptedToolImportAnalyzer(esbuildBinary = fakeEsbuild)

  private fun analysesRun(): Int = invocations.readLines().count { it.isNotBlank() }

  /**
   * Written with a past mtime: a file changed moments before an analysis may have changed during
   * it, and the analyzer declines to remember that verdict.
   */
  private fun writeTool(name: String, body: String): File = File(toolsDir, name).apply {
    writeText(body)
    setLastModified(System.currentTimeMillis() - 60_000)
  }

  /** Rewrites [file] with a different, still-past mtime, visible even on a coarse-grained clock. */
  private fun edit(file: File, body: String) {
    val before = file.lastModified()
    file.writeText(body)
    file.setLastModified(before - 10_000)
  }

  /**
   * Stages the metafile the fake esbuild hands back: one input per key of [importsOf], each
   * importing the listed paths. `node:*` paths are marked external, as the real analyzer run
   * marks them.
   */
  private fun stageMetafile(importsOf: Map<String, List<String>>) {
    val inputs = importsOf.entries.joinToString(",") { (input, imports) ->
      val importJson = imports.joinToString(",") { path ->
        if (path.startsWith("node:")) """{"path":"$path","external":true}""" else """{"path":"$path"}"""
      }
      """"$input":{"imports":[$importJson]}"""
    }
    stagedMetafile.writeText("""{"inputs":{$inputs}}""")
  }
}
