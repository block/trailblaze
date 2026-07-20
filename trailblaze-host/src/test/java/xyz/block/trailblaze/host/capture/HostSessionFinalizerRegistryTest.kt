package xyz.block.trailblaze.host.capture

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test
import xyz.block.trailblaze.logs.model.SessionId

class HostSessionFinalizerRegistryTest {

  @Test
  fun `all registered finalizers run even when one fails`() {
    val calls = mutableListOf<String>()
    val first =
      HostSessionFinalizerRegistry.register { sessionId -> calls += "first:${sessionId.value}" }
    val failing = HostSessionFinalizerRegistry.register { error("broken finalizer") }
    val last =
      HostSessionFinalizerRegistry.register { sessionId -> calls += "last:${sessionId.value}" }
    try {
      val failure =
        assertFailsWith<IllegalStateException> {
          HostSessionFinalizerRegistry.finalizeSession(SessionId("session-1"))
        }
      assertEquals("broken finalizer", failure.cause?.message)
    } finally {
      first.close()
      failing.close()
      last.close()
    }

    assertEquals(listOf("first:session-1", "last:session-1"), calls)
  }

  @Test
  fun `resource barrier finalizes then stops every session before throwing`() {
    val calls = mutableListOf<String>()
    val finalizer =
      HostSessionFinalizerRegistry.register { sessionId ->
        calls += "finalize:${sessionId.value}"
        if (sessionId.value == "session-1") error("broken finalizer")
      }
    try {
      val failure =
        assertFailsWith<IllegalStateException> {
          finalizeHostSessionResources(
            listOf(SessionId("session-1"), SessionId("session-2")),
          ) { sessionId ->
            calls += "stop:${sessionId.value}"
          }
        }

      assertEquals("broken finalizer", failure.cause?.message)
    } finally {
      finalizer.close()
    }

    assertEquals(
      listOf(
        "finalize:session-1",
        "stop:session-1",
        "finalize:session-2",
        "stop:session-2",
      ),
      calls,
    )
  }
}
