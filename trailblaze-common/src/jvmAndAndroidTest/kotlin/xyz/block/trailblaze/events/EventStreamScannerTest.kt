package xyz.block.trailblaze.events

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.trailhead.EventMatch

class EventStreamScannerTest {

  private val file: File = Files.createTempFile("stream", ".ndjson").toFile().apply { deleteOnExit() }

  private fun scanAll(scanner: EventStreamScanner, from: Long = 0L, stats: EventStreamScanner.Stats = EventStreamScanner.Stats()): Pair<List<String>, Long> {
    val lines = mutableListOf<String>()
    val end = scanner.forward(file, from, stats) { lines += it; true }
    return lines to end
  }

  private fun scanBackward(scanner: EventStreamScanner, from: Long = 0L): List<String> {
    val lines = mutableListOf<String>()
    scanner.backward(file, from, EventStreamScanner.Stats()) { lines += it; true }
    return lines
  }

  private fun match(json: String): JsonObject = TrailblazeJsonInstance.parseToJsonElement(json) as JsonObject

  @Test
  fun `a line still being written is left for the next scan, which resumes where the last stopped`() {
    file.writeText("{\"a\":1}\n{\"a\":2}\n{\"a\":")
    val (first, cursor) = scanAll(EventStreamScanner())
    assertEquals(listOf("{\"a\":1}", "{\"a\":2}"), first)
    assertEquals("{\"a\":1}\n{\"a\":2}\n".length.toLong(), cursor)

    file.appendText("3}\n{\"a\":4}\n")
    val (second, _) = scanAll(EventStreamScanner(), from = cursor)
    assertEquals(listOf("{\"a\":3}", "{\"a\":4}"), second)
  }

  @Test
  fun `lines without the match's literals are never decoded`() {
    file.writeText(
      (1..1000).joinToString("\n", postfix = "\n") { i ->
        val name = if (i % 400 == 0) "LOGIN" else "CHECKOUT"
        """{"timeMs":$i,"data":{"journeyName":"$name","eventType":{"type":"end"}}}"""
      },
    )
    val stats = EventStreamScanner.Stats()
    val needles = EventStreamScanner.needlesFor(match("""{"journeyName":"LOGIN","eventType":{"type":"end"}}"""))
    val (lines, _) = scanAll(EventStreamScanner(needles), stats = stats)

    assertEquals(2, lines.size)
    assertEquals(1000, stats.lines)
    assertEquals(2, stats.candidates)
  }

  @Test
  fun `a value its producer escaped still reaches the matcher`() {
    // Android's org.json writes `/` as `\/`, Python writes non-ASCII as \u escapes, Gson writes `<` as <.
    file.writeText(
      """{"url":"\/v1\/items\/batch","name":"café","tag":"<b>"}""" + "\n" + """{"url":"/v1/other"}""" + "\n",
    )
    val json = """{"url":"/v1/items/batch","name":"café","tag":"<b>"}"""
    val eventMatch = EventMatch.parse(match(json))
    val (candidates, _) = scanAll(EventStreamScanner(EventStreamScanner.needlesFor(match(json))))
    val matched = candidates.filter { eventMatch.matches(TrailblazeJsonInstance.parseToJsonElement(it)) }

    assertEquals(1, matched.size)
  }

  @Test
  fun `a plain-literal regex prefilters, case-insensitively when flagged`() {
    file.writeText("""{"url":"/v1/ITEMS/9"}""" + "\n" + """{"url":"/v1/orders"}""" + "\n")
    val needles = EventStreamScanner.needlesFor(match("""{"url":{"${'$'}regex":"/v1/items","${'$'}flags":"i"}}"""))
    val (lines, _) = scanAll(EventStreamScanner(needles))
    assertEquals(listOf("""{"url":"/v1/ITEMS/9"}"""), lines)
  }

  @Test
  fun `lines across buffer boundaries read the same both ways`() {
    val records = (1..50).map { """{"timeMs":$it,"data":{"n":"${"x".repeat(it)}"}}""" }
    file.writeText(records.joinToString("\n", postfix = "\n") + "{\"partial\":")
    val tiny = EventStreamScanner(bufferBytes = 16)

    assertEquals(records, scanAll(tiny).first)
    assertEquals(records.reversed(), scanBackward(tiny))
  }

  @Test
  fun `backward reads only the tail when the newest line is enough`() {
    file.writeText((1..5000).joinToString("\n", postfix = "\n") { """{"timeMs":$it,"data":{"n":$it}}""" })
    val stats = EventStreamScanner.Stats()
    var newest: String? = null
    EventStreamScanner(bufferBytes = 4096).backward(file, 0L, stats) { newest = it; false }

    assertEquals("""{"timeMs":5000,"data":{"n":5000}}""", newest)
    assertTrue(stats.bytesRead <= 4096, "read ${stats.bytesRead} of ${file.length()} bytes")
  }

  @Test
  fun `an oversized line is skipped and counted, not returned`() {
    file.writeText("{\"a\":\"${"x".repeat(100)}\"}\n{\"a\":1}\n")
    val stats = EventStreamScanner.Stats()
    val (lines, _) = scanAll(EventStreamScanner(maxLineBytes = 50, bufferBytes = 16), stats = stats)
    assertEquals(listOf("{\"a\":1}"), lines)
    assertEquals(1, stats.oversized)

    val back = mutableListOf<String>()
    val backStats = EventStreamScanner.Stats()
    EventStreamScanner(maxLineBytes = 50, bufferBytes = 16).backward(file, 0L, backStats) { back += it; true }
    assertEquals(listOf("{\"a\":1}"), back)
    assertEquals(1, backStats.oversized)
  }

  @Test
  fun `needles come only from what a match requires`() {
    val needles = EventStreamScanner.needlesFor(
      buildJsonObject {
        put("journeyName", "LOGIN")
        put("status", 200)
        put("ok", true)
        put("ratio", 0.5)
        put("gone", kotlinx.serialization.json.JsonNull)
      },
    ).map { it.toString() }
    assertEquals(listOf("\"LOGIN\"", "true"), needles)
  }

  private fun matched(json: String, line: String): Boolean {
    file.writeText(line + "\n")
    val eventMatch = EventMatch.parse(match(json))
    val (candidates, _) = scanAll(EventStreamScanner(EventStreamScanner.needlesFor(match(json))))
    return candidates.any { eventMatch.matches(TrailblazeJsonInstance.parseToJsonElement(it)) }
  }

  @Test
  fun `the prefilter never drops a line the matcher would accept`() {
    assertTrue(matched("""{"n":200}""", """{"n":2e2}"""), "a number written another way")
    assertTrue(matched("""{"journeyName":"LOGIN"}""", """{"journeyName":"\u004cOGIN"}"""), "a \\u escape")
    assertTrue(matched("""{"path":{"${'$'}regex":"disk","${'$'}flags":"i"}}""", """{"path":"dısk"}"""), "a Unicode case fold")
    assertTrue(matched("""{"flags":{"${'$'}regex":"true,false"}}""", """{"flags":[true, false]}"""), "a regex over a spaced array")
  }

  @Test
  fun `a scan stops at the length it was given, leaving later appends for the next scan`() {
    file.writeText("{\"a\":1}\n")
    val length = file.length()
    file.appendText("{\"a\":2}\n")
    val lines = mutableListOf<String>()
    val end = EventStreamScanner().forward(file, 0L, EventStreamScanner.Stats(), until = length) { lines += it; true }

    assertEquals(listOf("{\"a\":1}"), lines)
    assertEquals(length, end)
  }

  @Test
  fun `a needle that non-ASCII text might hide still lets the other needles reject the line`() {
    file.writeText("""{"path":"dısk","journeyName":"CHECKOUT"}""" + "\n")
    val needles = EventStreamScanner.needlesFor(
      match("""{"path":{"${'$'}regex":"disk","${'$'}flags":"i"},"journeyName":"LOGIN"}"""),
    )
    val stats = EventStreamScanner.Stats()
    val (lines, _) = scanAll(EventStreamScanner(needles), stats = stats)

    assertEquals(emptyList(), lines)
    assertEquals(0, stats.candidates)
  }

  @Test
  fun `a finished stream's last record counts even without a newline, while a live stream's waits`() {
    file.writeText("{\"a\":1}\n{\"a\":2}")

    assertEquals(listOf("{\"a\":1}"), scanAll(EventStreamScanner()).first)
    val (lines, end) = scanAll(EventStreamScanner(bufferBytes = 4, finishedStream = true))
    assertEquals(listOf("{\"a\":1}", "{\"a\":2}"), lines)
    assertEquals(file.length(), end)
  }
}
