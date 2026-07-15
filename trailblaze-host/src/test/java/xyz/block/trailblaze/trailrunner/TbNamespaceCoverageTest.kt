package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Guards the `window.TB` namespace contract: every `TB.<name>` a web screen calls must be a key
 * published in the `window.TB = { … }` literal in `app/data-extract.tsx`. The screens are plain
 * babel scripts and `TB` is typed `any`, so a missing member compiles fine and only fails at
 * click time with "TB.x is not a function" — which is exactly how every Run click broke once
 * before (`withTimeout` was added to `Object.assign(window, …)` in data-core.tsx but never to
 * the TB literal).
 */
class TbNamespaceCoverageTest {

  private val tbMember = Regex("""\bTB\.([A-Za-z0-9_]+)""")

  private fun webAppDir(): File {
    val marker = javaClass.getResource("/xyz/block/trailblaze/trailrunner/web/app/data-extract.tsx")
      ?: error("data-extract.tsx not found on the test classpath — did the web resources move?")
    return File(marker.toURI()).parentFile
  }

  private fun publishedTbKeys(dataExtract: File): Set<String> {
    val text = dataExtract.readText()
    val start = text.indexOf("window.TB = {")
    assertTrue(start >= 0, "window.TB literal not found in data-extract.tsx")
    val end = text.indexOf("\n};", start)
    assertTrue(end > start, "window.TB literal not terminated with a top-level '};'")
    return text.substring(start, end)
      .lineSequence()
      .map { it.substringBefore("//") }
      .flatMap { it.split(',') }
      .map { it.trim().substringBefore(':').trim() }
      .filter { it.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*""")) }
      .toSet()
  }

  @Test
  fun `every TB member referenced by the web app is published on the TB namespace`() {
    val appDir = webAppDir()
    val dataExtract = File(appDir, "data-extract.tsx")
    val published = publishedTbKeys(dataExtract)
    assertTrue(published.size > 50, "suspiciously few TB keys parsed (${published.size}) — parser broken?")

    val missing = appDir.walkTopDown()
      .filter { it.isFile && (it.extension == "tsx" || it.extension == "jsx" || it.extension == "js") }
      .flatMap { file ->
        tbMember.findAll(file.readText()).map { file.name to it.groupValues[1] }
      }
      .filter { (_, name) -> name !in published }
      .groupBy({ it.second }, { it.first })

    assertTrue(
      missing.isEmpty(),
      "TB members used by screens but missing from the window.TB literal in data-extract.tsx " +
        "(add them there or the call throws 'TB.x is not a function' at runtime): " +
        missing.entries.joinToString { (name, files) -> "$name (${files.distinct().joinToString()})" },
    )
  }
}
