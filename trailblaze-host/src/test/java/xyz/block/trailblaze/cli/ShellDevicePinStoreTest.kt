package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Tests for [ShellDevicePinStore].
 *
 * Coverage focuses on the contract that the CLI resolver depends on:
 *
 *  - Cold-start (file missing) returns NotFound without creating the file.
 *  - Round-trip set → resolve returns the pinned device.
 *  - Dead PID (probe returns false) → NotFound + eviction.
 *  - Concurrent terminals (different PIDs) coexist; clearing one preserves the other.
 *  - Garbage collection runs opportunistically on every mutation so a long-lived
 *    file doesn't accumulate dead entries forever.
 *  - Concurrent writers under file locking don't lose updates.
 *  - Port scoping: two daemons get independent files.
 *  - Corrupted file content is treated as empty rather than throwing.
 *  - Non-numeric keys (corrupted file) get dropped on the next mutation.
 *
 * The liveness probe is injected as a deterministic lambda so tests don't depend
 * on real PIDs. Production wires it to `ProcessHandle.of(pid).map { it.isAlive }`.
 */
class ShellDevicePinStoreTest {

  @Rule
  @JvmField
  val tmp = TemporaryFolder()

  private fun newFile(): File = File(tmp.root, "shell-device-pins-52525.json")

  /** Probe that reports every PID as alive. */
  private val allAlive = ShellDevicePinStore.LivenessProbe { _ -> true }

  /** Probe that reports every PID as dead. */
  private val allDead = ShellDevicePinStore.LivenessProbe { _ -> false }

  @Test
  fun `resolvePin returns NotFound when file does not exist`() {
    val file = newFile()
    assertFalse(file.exists(), "precondition: file should not exist")
    val result = ShellDevicePinStore.resolvePin(file, shellPid = 12345L, probe = allAlive)
    assertEquals(ShellDevicePinStore.PinLookup.NotFound, result)
    assertFalse(file.exists(), "resolvePin must not create the file on a cold read")
  }

  @Test
  fun `setPin then resolvePin round-trips the device`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    assertEquals(ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("android/emulator-5554")), result)
  }

  @Test
  fun `setPin with target round-trips both fields`() {
    // Persisting the target is what makes `device connect --target X` survive
    // a daemon restart — see PR #3611 review feedback. Round-trip via resolve
    // to confirm the target field is JSON-encoded and decoded correctly, and
    // accessible via the convenience getter on PinLookup.Found.
    val file = newFile()
    ShellDevicePinStore.setPin(
      file,
      12345L,
      device = "android/emulator-5554",
      target = "sampleapp",
      probe = allAlive,
    )
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    val found = result as? ShellDevicePinStore.PinLookup.Found
    assertEquals("android/emulator-5554", found?.device)
    assertEquals("sampleapp", found?.target)
  }

  @Test
  fun `setPin without target preserves null target on read`() {
    // Users who pin a bare `device connect` (no --target) should land on a
    // null target so the resolver falls through to env / workspace config /
    // built-in default rather than re-applying a stale value.
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    val found = result as? ShellDevicePinStore.PinLookup.Found
    assertEquals("android/emulator-5554", found?.device)
    assertEquals(null, found?.target)
  }

  @Test
  fun `mutate throws IllegalStateException when lock path exists as a directory`() {
    // A corrupted ~/.trailblaze/ state (user mkdir'd the lock path, filesystem
    // weirdness, etc.) should surface a clear error naming the path — not the
    // misleading "Is a directory" FileNotFoundException that RandomAccessFile
    // would otherwise throw mid-lock. See lead-dev-review finding from
    // PR #3611 — the defensive check fires early to keep the failure
    // self-diagnostic.
    val file = newFile()
    file.parentFile.mkdirs()
    val lockPath = java.io.File(file.parentFile, "${file.name}.lock")
    lockPath.mkdirs()
    assertTrue(lockPath.isDirectory, "precondition: lock path must be a directory")

    val ex = kotlin.runCatching {
      ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    }.exceptionOrNull()
    assertTrue(ex is IllegalStateException, "expected IllegalStateException, got: $ex")
    assertTrue(
      ex.message?.contains(lockPath.name) == true,
      "exception message should name the bad lock path: got: ${ex.message}",
    )
  }

  @Test
  fun `clearPinTarget atomically nulls the target while preserving the device`() {
    // The atomic variant (single mutate() lock window) used by --target=clear.
    // Replaces the resolvePin-then-setPin sequence that had a same-PID race
    // window in the rare case of concurrent ./trailblaze invocations from
    // one backgrounded shell.
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", target = "sampleapp", probe = allAlive)

    ShellDevicePinStore.clearPinTarget(file, 12345L, probe = allAlive)

    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    val found = result as? ShellDevicePinStore.PinLookup.Found
    assertEquals("android/emulator-5554", found?.device, "device must survive a target-only clear")
    assertEquals(null, found?.target, "target must be null after clearPinTarget")
  }

  @Test
  fun `clearPinTarget is a no-op when entry is absent`() {
    // Cold-file case: no entry for this PID at all → nothing to clear → the
    // function must not create a phantom entry. (Some non-target-clear
    // workflow could rely on this — defensive coverage.)
    val file = newFile()
    ShellDevicePinStore.clearPinTarget(file, 12345L, probe = allAlive)
    assertEquals(
      ShellDevicePinStore.PinLookup.NotFound,
      ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive),
    )
  }

  @Test
  fun `clearPinTarget on an already-null target leaves the entry intact`() {
    // After the first clear, subsequent clears should leave the entry's
    // device intact and the target null. (mutate() does still rewrite the
    // file even when the transform is a no-op — that's an accepted cost
    // for the simpler implementation; the property we care about is that
    // the entry's value is unchanged.)
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", target = null, probe = allAlive)
    ShellDevicePinStore.clearPinTarget(file, 12345L, probe = allAlive)

    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    val found = result as? ShellDevicePinStore.PinLookup.Found
    assertEquals("android/emulator-5554", found?.device, "device must remain intact")
    assertEquals(null, found?.target, "target stays null")
  }

  @Test
  fun `setPin with target=null clears only the target, preserving the device`() {
    // Models the `--target=clear` flow: clear the target field on the pin
    // without disconnecting the device. The user's terminal stays bound to
    // its device; the next action call falls through to env / workspace
    // config for the target instead of re-applying the cleared one.
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", target = "sampleapp", probe = allAlive)
    // Surgery: same PID, same device, null target.
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", target = null, probe = allAlive)
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    val found = result as? ShellDevicePinStore.PinLookup.Found
    assertEquals("android/emulator-5554", found?.device, "device pin must survive a target clear")
    assertEquals(null, found?.target, "target must be null after the clear")
  }

  @Test
  fun `setPin overwrites prior target when called again`() {
    // `device rebind --target Y` re-writes the pin with the new target.
    // Confirm the second write replaces, not merges, the prior target.
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", target = "sampleapp", probe = allAlive)
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", target = "square", probe = allAlive)
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    assertEquals("square", (result as? ShellDevicePinStore.PinLookup.Found)?.target)
  }

  @Test
  fun `pin file written by older code without target field still deserializes`() {
    // Backwards compat: existing pin files from before this PR's target field
    // have entries shaped like {"device": "android/..."}. The new Entry's
    // `target: String? = null` default needs to handle that gracefully on
    // read so a user who upgrades doesn't lose their device pin.
    val file = newFile()
    file.parentFile.mkdirs()
    file.writeText(
      """
      {
        "shells": {
          "12345": { "device": "android/emulator-5554" }
        }
      }
      """.trimIndent(),
    )
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    val found = result as? ShellDevicePinStore.PinLookup.Found
    assertEquals("android/emulator-5554", found?.device)
    assertEquals(null, found?.target)
  }

  @Test
  fun `resolvePin returns NotFound and evicts when the PID is no longer alive`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)

    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allDead)
    assertEquals(ShellDevicePinStore.PinLookup.NotFound, result)

    val after = ShellDevicePinStore.readAll(file)
    assertFalse(after.containsKey(12345L), "dead-PID entry must be evicted")
  }

  @Test
  fun `pins for different shells coexist`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    ShellDevicePinStore.setPin(file, 67890L, "ios/iPhone-15", probe = allAlive)

    assertEquals(
      ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("android/emulator-5554")),
      ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive),
    )
    assertEquals(
      ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("ios/iPhone-15")),
      ShellDevicePinStore.resolvePin(file, 67890L, probe = allAlive),
    )
  }

  @Test
  fun `setting the same PID twice replaces the prior device`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5556", probe = allAlive)

    assertEquals(
      ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("android/emulator-5556")),
      ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive),
    )
  }

  @Test
  fun `clearPin removes the entry but preserves other terminals' pins`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    ShellDevicePinStore.setPin(file, 67890L, "ios/iPhone-15", probe = allAlive)

    ShellDevicePinStore.clearPin(file, 12345L, probe = allAlive)

    assertEquals(
      ShellDevicePinStore.PinLookup.NotFound,
      ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive),
    )
    assertEquals(
      ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("ios/iPhone-15")),
      ShellDevicePinStore.resolvePin(file, 67890L, probe = allAlive),
    )
  }

  @Test
  fun `clearPin is a no-op when no entry exists`() {
    val file = newFile()
    // Cold file. Should not throw, should not create the file with an empty map
    // either (the file gets created by `mutate` to acquire its lock, then ends
    // up holding an empty `shells` map — both states are fine, just want no
    // exception).
    ShellDevicePinStore.clearPin(file, 12345L, probe = allAlive)
    assertEquals(
      ShellDevicePinStore.PinLookup.NotFound,
      ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive),
    )
  }

  @Test
  fun `mutate opportunistically garbage-collects dead entries`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 11111L, "android/emulator-5554", probe = allAlive)
    ShellDevicePinStore.setPin(file, 22222L, "android/emulator-5556", probe = allAlive)
    ShellDevicePinStore.setPin(file, 33333L, "ios/iPhone-15", probe = allAlive)

    // Probe now reports only PID 22222 as alive.
    val partialProbe = ShellDevicePinStore.LivenessProbe { pid -> pid == 22222L }

    // Any mutation should sweep dead entries — use a no-op set on the live PID.
    ShellDevicePinStore.setPin(file, 22222L, "android/emulator-5556", probe = partialProbe)

    val after = ShellDevicePinStore.readAll(file)
    assertEquals(setOf(22222L), after.keys, "GC must drop dead-PID entries on mutation")
  }

  @Test
  fun `readAll without eviction returns raw file contents`() {
    val file = newFile()
    ShellDevicePinStore.setPin(file, 11111L, "android/emulator-5554", probe = allAlive)
    ShellDevicePinStore.setPin(file, 22222L, "android/emulator-5556", probe = allAlive)

    val all = ShellDevicePinStore.readAll(file)
    assertEquals(2, all.size)
    assertEquals("android/emulator-5554", all[11111L]?.device)
    assertEquals("android/emulator-5556", all[22222L]?.device)
  }

  @Test
  fun `pinFileFor scopes file path by port`() {
    val a = ShellDevicePinStore.pinFileFor(52525)
    val b = ShellDevicePinStore.pinFileFor(52527)
    assertTrue(a.path != b.path, "different ports must yield different file paths")
    assertTrue(a.name.contains("52525"), "filename should carry the port: ${a.name}")
    assertTrue(b.name.contains("52527"), "filename should carry the port: ${b.name}")
  }

  @Test
  fun `concurrent writers do not lose updates under file lock`() {
    // Spawn N threads each writing a distinct PID's pin. After all join, every
    // pin must be present — the file lock + in-JVM monitor serialize the RMW,
    // so no thread's write is overwritten by another's stale read.
    val file = newFile()
    val threadCount = 16
    val threads = (1..threadCount).map { i ->
      Thread {
        val pid = i.toLong() * 1000
        ShellDevicePinStore.setPin(file, pid, "device-$i", probe = allAlive)
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    val all = ShellDevicePinStore.readAll(file)
    assertEquals(threadCount, all.size, "every concurrent write must be present")
    for (i in 1..threadCount) {
      val pid = i.toLong() * 1000
      assertEquals("device-$i", all[pid]?.device, "lost write for PID $pid")
    }
  }

  @Test
  fun `corrupted file is treated as empty, not as an error`() {
    val file = newFile()
    file.parentFile.mkdirs()
    file.writeText("{not valid json at all")

    // Read should treat this as empty, not throw.
    val result = ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive)
    assertEquals(ShellDevicePinStore.PinLookup.NotFound, result)

    // Write should overwrite the corruption cleanly.
    ShellDevicePinStore.setPin(file, 12345L, "android/emulator-5554", probe = allAlive)
    assertEquals(
      ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("android/emulator-5554")),
      ShellDevicePinStore.resolvePin(file, 12345L, probe = allAlive),
    )
  }

  @Test
  fun `entries with non-numeric keys are dropped on mutation`() {
    val file = newFile()
    file.parentFile.mkdirs()
    // Hand-craft a file with a non-numeric key (someone edited it; or it came
    // from a future version with a different key format).
    file.writeText(
      """
      {
        "shells": {
          "abc": { "device": "android/foo" },
          "12345": { "device": "android/emulator-5554" }
        }
      }
      """.trimIndent(),
    )

    // Triggering any mutation should drop the non-numeric key while preserving
    // the valid one.
    ShellDevicePinStore.setPin(file, 67890L, "ios/iPhone-15", probe = allAlive)

    val all = ShellDevicePinStore.readAll(file)
    assertEquals(setOf(12345L, 67890L), all.keys, "non-numeric keys must not survive a mutation")
  }

  @Test
  fun `setPin creates the parent directory if missing`() {
    // Use a path under a not-yet-existing subdirectory to assert mkdirs() runs.
    val nested = File(tmp.root, "deeply/nested/dir/shell-device-pins-52525.json")
    assertFalse(nested.parentFile.exists(), "precondition: parent dir should not exist")

    ShellDevicePinStore.setPin(nested, 12345L, "android/emulator-5554", probe = allAlive)

    assertTrue(nested.exists(), "file should be created")
    assertEquals(
      ShellDevicePinStore.PinLookup.Found(ShellDevicePinStore.Entry("android/emulator-5554")),
      ShellDevicePinStore.resolvePin(nested, 12345L, probe = allAlive),
    )
  }
}
