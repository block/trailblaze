package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaywrightVideoRecordDirTest {

  private val device1 = "web-test-device-1"
  private val device2 = "web-test-device-2"
  private val tempDirs = mutableListOf<File>()

  private fun tmp(): File = Files.createTempDirectory("pwrec-").toFile().also { tempDirs.add(it) }

  @AfterTest
  fun tearDown() {
    PlaywrightVideoRecordDir.clearRecordDir(device1)
    PlaywrightVideoRecordDir.clearRecordDir(device2)
    tempDirs.forEach { it.deleteRecursively() }
  }

  @Test
  fun `getRecordDir returns null before registration`() {
    assertNull(PlaywrightVideoRecordDir.getRecordDir(device1))
  }

  @Test
  fun `setRecordDir then getRecordDir returns the registered dir`() {
    val dir = tmp()
    PlaywrightVideoRecordDir.setRecordDir(device1, dir)
    assertEquals(dir, PlaywrightVideoRecordDir.getRecordDir(device1))
  }

  @Test
  fun `clearRecordDir drops the registration`() {
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.clearRecordDir(device1)
    assertNull(PlaywrightVideoRecordDir.getRecordDir(device1))
  }

  @Test
  fun `device ids are isolated`() {
    val dirA = tmp()
    val dirB = tmp()
    PlaywrightVideoRecordDir.setRecordDir(device1, dirA)
    PlaywrightVideoRecordDir.setRecordDir(device2, dirB)
    assertEquals(dirA, PlaywrightVideoRecordDir.getRecordDir(device1))
    assertEquals(dirB, PlaywrightVideoRecordDir.getRecordDir(device2))
  }

  @Test
  fun `setRecordDir overwrites an earlier registration and drops its finalizer`() {
    val firstCalls = AtomicInteger(0)
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.setFinalizer(device1) { firstCalls.incrementAndGet() }
    // New session for the same device id — must clear the prior finalizer so we don't
    // call into a torn-down browser manager.
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.runFinalizer(device1)
    assertEquals(0, firstCalls.get())
  }

  @Test
  fun `runFinalizer invokes the registered callback`() {
    val calls = AtomicInteger(0)
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.setFinalizer(device1) { calls.incrementAndGet() }
    PlaywrightVideoRecordDir.runFinalizer(device1)
    assertEquals(1, calls.get())
  }

  @Test
  fun `runFinalizer is a no-op when no callback is registered`() {
    // No exception, no observable effect.
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.runFinalizer(device1)
  }

  @Test
  fun `runFinalizer swallows exceptions thrown by the callback`() {
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.setFinalizer(device1) { error("boom") }
    // Should not throw — best-effort flush semantics.
    PlaywrightVideoRecordDir.runFinalizer(device1)
  }

  @Test
  fun `clearFinalizer prevents subsequent runFinalizer from firing`() {
    val calls = AtomicInteger(0)
    PlaywrightVideoRecordDir.setRecordDir(device1, tmp())
    PlaywrightVideoRecordDir.setFinalizer(device1) { calls.incrementAndGet() }
    PlaywrightVideoRecordDir.clearFinalizer(device1)
    PlaywrightVideoRecordDir.runFinalizer(device1)
    assertEquals(0, calls.get())
  }
}
