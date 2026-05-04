package xyz.block.trailblaze.scripting.bundle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SdkBundleResourceTest {

  @Test fun `extractToFile returns a real on-disk file containing the bundle bytes`() {
    val file = SdkBundleResource.extractToFile()
    assertThat(file.isFile).isEqualTo(true)
    // The bundle is ~1.2MB; assert non-trivial size to catch a "wrote 0 bytes" regression
    // without pinning an exact length that drifts every time the SDK source is rebundled.
    assertThat(file.length()).isGreaterThan(100_000L)
    val firstLine = file.bufferedReader().use { it.readLine() }
    // esbuild emits `"use strict";` as the first line of the IIFE bundle, then declares the
    // global name. Pinning this line catches "wrote the wrong resource" / "wrote a corrupt
    // file" regressions. If the bundler config changes the prologue, update this assertion
    // alongside the bundle config in `:trailblaze-scripting-bundle`'s `build.gradle.kts`.
    assertThat(firstLine).isEqualTo("\"use strict\";")
  }

  @Test fun `extractToFile returns the same path on repeated calls`() {
    val first = SdkBundleResource.extractToFile()
    val second = SdkBundleResource.extractToFile()
    // We assert path equality rather than `isSameInstanceAs` because the recovery branch
    // (cached file deleted between calls) intentionally extracts a NEW File. Same-path while
    // the cached file still exists is the contract; same-instance was a coincidence of the
    // happy path and would have been a latent flake on long-lived JVMs whose tmp got swept.
    assertThat(second.absolutePath).isEqualTo(first.absolutePath)
    assertThat(second.isFile).isEqualTo(true)
  }

  @Test fun `extractToFile re-extracts when the cached file has been deleted`() {
    // Use a fresh cache so this test doesn't perturb the singleton's cached state for other
    // tests in the suite. The classloader is the real one so we extract real bytes.
    val cache = AtomicReference<File?>(null)
    val classLoader = SdkBundleResource::class.java.classLoader
    val first = SdkBundleResource.extractToFile(classLoader, cache)
    assertThat(first.isFile).isEqualTo(true)
    assertThat(first.delete()).isTrue()
    val second = SdkBundleResource.extractToFile(classLoader, cache)
    // The recovery branch must produce a NEW, readable file. Same-path would mean we
    // returned a stale File reference for a file we just deleted — a silent failure that
    // would surface only when a downstream consumer (node, in production) tries to read it.
    assertThat(second.isFile).isEqualTo(true)
    assertThat(second.length()).isGreaterThan(100_000L)
    assertThat(second.absolutePath).isNotEqualTo(first.absolutePath)
  }

  @Test fun `loadBundleBytes errors with actionable message when classpath resource is missing`() {
    // Build a classloader that resolves nothing — no URLs, parent set to the bootstrap
    // classloader (system classloader's parent) so it can't fall back to the test
    // classpath. Calling getResourceAsStream on this loader returns null for any path.
    val emptyClassLoader = URLClassLoader(emptyArray(), ClassLoader.getSystemClassLoader().parent)
    val ex = assertFailsWith<IllegalStateException> {
      SdkBundleResource.loadBundleBytes(emptyClassLoader)
    }
    val message = ex.message ?: error("expected non-null exception message")
    // Pin the actionable phrasing — every misconfigured downstream consumer hits this
    // surface, and the "Add `:trailblaze-scripting-bundle`..." hint is the only clue
    // they get. A future refactor that drops the hint should fail this assertion.
    assertThat(message).contains(SdkBundleResource.RESOURCE_PATH)
    assertThat(message).contains("Add `:trailblaze-scripting-bundle`")
    assertThat(message).contains("runtime classpath")
  }

  @Test fun `loadBundleBytes errors when given a null classloader`() {
    val ex = assertFailsWith<IllegalStateException> {
      SdkBundleResource.loadBundleBytes(null)
    }
    assertThat(ex.message ?: "").contains("no classloader available")
  }

  @Test fun `extractToFile is concurrency-safe under contention from multiple threads`() {
    // Fresh cache so all threads race against an unset state and exercise the
    // double-checked-locking branch in extractToFile, not the un-synchronized fast path.
    val cache = AtomicReference<File?>(null)
    val classLoader = SdkBundleResource::class.java.classLoader
    val threadCount = 16
    val barrier = CountDownLatch(1)
    val started = CountDownLatch(threadCount)
    val results = ConcurrentHashMap.newKeySet<String>()
    val errors = ConcurrentHashMap.newKeySet<Throwable>()

    val threads = (1..threadCount).map {
      Thread {
        try {
          started.countDown()
          // All threads block here until the main test thread releases them, maximizing
          // the chance that they collide inside extractToFile's synchronized block.
          barrier.await()
          val file = SdkBundleResource.extractToFile(classLoader, cache)
          results.add(file.absolutePath)
        } catch (t: Throwable) {
          errors.add(t)
        }
      }.also { it.start() }
    }
    started.await()
    barrier.countDown()
    threads.forEach { thread ->
      thread.join(10_000)
      // Detect a hung thread: 10s is generously above the actual extraction time (~50ms
      // for the 1.2MB write), so a thread still alive at this point points at deadlock
      // or runaway loop in extractToFile, not just slow IO.
      assertThat(thread.isAlive).isEqualTo(false)
    }

    assertThat(errors).isEmpty()
    // If the double-checked-locking regresses (e.g. cache.set moves outside the
    // synchronized block, or `synchronized` is dropped), some threads will run doExtract
    // independently and produce different tempDirs, so `results` will have >1 entry.
    assertThat(results).hasSize(1)
  }
}
