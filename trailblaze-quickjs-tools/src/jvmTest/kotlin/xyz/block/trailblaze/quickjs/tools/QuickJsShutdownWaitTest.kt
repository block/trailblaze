package xyz.block.trailblaze.quickjs.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class QuickJsShutdownWaitTest {

  @Test
  fun `shutdown stops waiting on a stuck call and frees the engine once it ends`() = runBlocking {
    val host = QuickJsToolHost.connect("globalThis.__trailblazeTools = {};", logSink = {})
    // Stands in for an evaluation stuck behind a blocked host call its caller abandoned.
    host.evalMutex.lock()
    val started = TimeSource.Monotonic.markNow()
    host.shutdown(wait = 100.milliseconds)
    assertTrue(started.elapsedNow() < 5.seconds, "shutdown waited ${started.elapsedNow()}")
    assertFalse(host.quickJs.isClosed, "freed the engine while a call still held it")

    host.evalMutex.unlock()
    withTimeout(5.seconds) { while (!host.quickJs.isClosed) delay(10) }
  }
}
