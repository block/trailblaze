package xyz.block.trailblaze.tracing

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.Test
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder

class TracingTest {
  @Test
  fun test() = runBlocking {
    traceRecorder.trace("abc") {
      delay(1)
    }
    TrailblazeTracer.traceSuspend("def") {
      delay(1)
    }

    val json = TrailblazeTracer.exportJson()

    val events = TRACING_JSON_INSTANCE.decodeFromString<JsonArray>(json)
    assertEquals(events.size, 2)
  }
}
