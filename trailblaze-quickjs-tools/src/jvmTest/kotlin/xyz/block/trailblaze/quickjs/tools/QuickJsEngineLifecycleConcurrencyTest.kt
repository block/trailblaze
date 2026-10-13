package xyz.block.trailblaze.quickjs.tools

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Engines start and stop concurrently in production: a session launches its tool bundles in
 * parallel, and a finished session shuts its engines down while the next one starts. quickjs-kt
 * shares JNI state across every engine, and racing on it aborts the process on Android with
 * "invalid global reference".
 *
 * The desktop JVM usually runs on past a bad JNI reference, so the engines run in a child JVM
 * with `-Xcheck:jni`, which aborts on one the way Android does. Its output goes to a file:
 * `-Xcheck:jni` also prints a warning for every engine about the library's own exception checks.
 */
class QuickJsEngineLifecycleConcurrencyTest {

  @Test
  fun `engines started and closed concurrently each answer their own tool call`() {
    val output = File.createTempFile("quickjs-lifecycle", ".log").apply { deleteOnExit() }
    val java = File(System.getProperty("java.home"), "bin/java").path
    val child = ProcessBuilder(
      java, "-Xcheck:jni", "-cp", System.getProperty("java.class.path"),
      ConcurrentEngineLifecycles::class.java.name,
    ).redirectErrorStream(true).redirectOutput(output).start()
    if (!child.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      child.destroyForcibly()
      fail("Engines did not finish within ${CHILD_TIMEOUT_SECONDS}s")
    }
    val log = output.readLines()
    assertTrue(
      child.exitValue() == 0 && ConcurrentEngineLifecycles.PASSED in log,
      "Child JVM exited ${child.exitValue()}:\n" +
        log.filterNot { it.startsWith("WARNING in native method") || it.startsWith("\tat ") }.takeLast(40).joinToString("\n"),
    )
  }

  private companion object {
    const val CHILD_TIMEOUT_SECONDS = 180L
  }
}

/** Runs in the child JVM; prints [PASSED] only when every engine returned its own answer. */
object ConcurrentEngineLifecycles {
  const val PASSED = "ALL ENGINES ANSWERED"

  private const val ROUNDS = 300
  private const val ENGINES_PER_ROUND = 16

  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    repeat(ROUNDS) { round ->
      val expected = (0 until ENGINES_PER_ROUND).map { "r$round-e$it" }
      val answers = coroutineScope {
        expected.map { name -> async(Dispatchers.Default) { startAskAndClose(name) } }.awaitAll()
      }
      check(answers == expected) { "Round $round answered $answers" }
    }
    println(PASSED)
  }

  private suspend fun startAskAndClose(name: String): String {
    val host = QuickJsToolHost.connect(bundleAnswering(name), logSink = {})
    try {
      val result = host.callTool("whoAmI", JsonObject(emptyMap()), JsonObject(emptyMap()))
      return ((result["content"] as JsonArray).single() as JsonObject)["text"]!!.jsonPrimitive.content
    } finally {
      host.shutdown()
    }
  }

  private fun bundleAnswering(name: String): String = """
    globalThis.__trailblazeTools = {
      whoAmI: {
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: ${JsonPrimitive(name)} }] }),
      },
    };
  """.trimIndent()
}
