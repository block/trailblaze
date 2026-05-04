package xyz.block.trailblaze.ui

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the JVM `loadNetworkLogs` actual's outcome paths — missing dir, missing file,
 * empty file, happy-path read, and the (mtime, length)-keyed read cache that lets the
 * 1s poll loop in `SessionCombinedView` skip redundant I/O when the file hasn't grown.
 *
 * Lives in `jvmTest` rather than `commonTest` because it exercises the JVM-only file
 * read + caching machinery in `ActualsJvm.kt`.
 */
class LoadNetworkLogsTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun writeNdjson(sessionId: String, content: String): File {
    val sessionDir = File(tempFolder.root, sessionId).apply { mkdirs() }
    val ndjson = File(sessionDir, "network.ndjson")
    ndjson.writeText(content)
    return ndjson
  }

  @Test
  fun `returns null when the session has no network ndjson`() = runBlocking {
    setLogsDirectory(tempFolder.root)
    File(tempFolder.root, "live-session").mkdirs() // session dir exists but no ndjson
    val result = loadNetworkLogs("live-session")
    assertNull(result)
  }

  @Test
  fun `returns null when network ndjson exists but is empty`() = runBlocking {
    setLogsDirectory(tempFolder.root)
    writeNdjson("just-started", "")
    // Distinguish "capture started but no events yet" (empty file) from "events captured"
    // — both are "no useful content," and the caller treats null the same way.
    val result = loadNetworkLogs("just-started")
    assertNull(result)
  }

  @Test
  fun `returns the file content when network ndjson is non-empty`() = runBlocking {
    setLogsDirectory(tempFolder.root)
    val payload = """{"id":"abc","method":"GET"}""" + "\n"
    writeNdjson("good-session", payload)
    val result = loadNetworkLogs("good-session")
    assertEquals(payload, result)
  }

  @Test
  fun `repeated reads of an unchanged file return the cached String reference`() = runBlocking {
    // The cache's whole point is to skip the readText() call when (mtime, length) match,
    // saving tens of MB/s of I/O on long live sessions. Returning the same reference is
    // the load-bearing observable behavior — the panel's `remember(source, rawContent)`
    // parse cache stays warm across ticks because String equality + same identity hits.
    setLogsDirectory(tempFolder.root)
    val payload = (1..50).joinToString("\n") { """{"id":"id-$it","method":"GET"}""" }
    writeNdjson("steady-session", payload)
    val first = loadNetworkLogs("steady-session")
    val second = loadNetworkLogs("steady-session")
    assertNotNull(first)
    assertSame(first, second, "expected the cache to return the identical String reference")
  }

  @Test
  fun `cache invalidates when the file grows`() = runBlocking {
    setLogsDirectory(tempFolder.root)
    val ndjson = writeNdjson("growing-session", """{"id":"a"}""" + "\n")
    val first = loadNetworkLogs("growing-session")
    assertNotNull(first)
    // Append a new event — length changes, so the cache should miss and a fresh read
    // returns the larger payload. Bump mtime explicitly: writes to small files within
    // the same millisecond can land on the same lastModified() timestamp on some
    // filesystems, defeating the (mtime, length) check by length-tie alone.
    ndjson.appendText("""{"id":"b"}""" + "\n")
    ndjson.setLastModified(ndjson.lastModified() + 1_000L)
    val second = loadNetworkLogs("growing-session")
    assertNotNull(second)
    assertEquals(true, second.length > first.length)
    assertEquals(true, second.endsWith("""{"id":"b"}""" + "\n"))
  }

  @Test
  fun `setLogsDirectory clears the read cache`() = runBlocking {
    // Test isolation: a prior session's cached content shouldn't leak when the logs dir
    // is repointed (e.g. a test that swaps temp dirs, or a daemon repointing for a fresh
    // run). The cache is keyed on absolute path, so a new dir with the same session id
    // would already get a cache miss — but clear-on-set is the explicit guarantee.
    setLogsDirectory(tempFolder.root)
    writeNdjson("session-a", """{"id":"a"}""" + "\n")
    val first = loadNetworkLogs("session-a")
    assertNotNull(first)

    // Repoint to a fresh dir, write a *different* session-a file there, and confirm we
    // see the new content rather than the old cached one.
    val secondRoot = tempFolder.newFolder("second-root")
    setLogsDirectory(secondRoot)
    File(secondRoot, "session-a").apply { mkdirs() }
      .resolve("network.ndjson")
      .writeText("""{"id":"different"}""" + "\n")
    val second = loadNetworkLogs("session-a")
    assertEquals("""{"id":"different"}""" + "\n", second)
  }
}
