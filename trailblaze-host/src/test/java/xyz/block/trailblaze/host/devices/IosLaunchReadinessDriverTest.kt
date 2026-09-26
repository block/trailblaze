package xyz.block.trailblaze.host.devices

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import maestro.Driver
import maestro.MaestroException
import maestro.TreeNode
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class IosLaunchReadinessDriverTest {

  @Test
  fun `launch readiness waits through Maestro's generic AppCrash and accepts the next snapshot`() {
    val clock = TestClock()
    var captures = 0
    val launchTransition = appCrash()

    awaitIosAppSnapshotReady(
      timeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = clock::now,
      sleepMs = clock::advance,
      contentDescriptor = {
        captures++
        if (captures < 3) throw launchTransition
        foregroundTree(APP_ID)
      },
    )

    assertThat(captures).isEqualTo(3)
  }

  @Test
  fun `a successful hierarchy is ready even when iOS is showing a system overlay`() {
    val clock = TestClock()

    awaitIosAppSnapshotReady(
      timeoutMs = 1_000,
      pollIntervalMs = 100,
      nowMs = clock::now,
      sleepMs = clock::advance,
      contentDescriptor = { foregroundTree("com.apple.springboard") },
    )

    assertThat(clock.now()).isEqualTo(0)
  }

  @Test
  fun `persistent AppCrash is rethrown unchanged after the bounded wait`() {
    val clock = TestClock()
    val persistentFailure = appCrash()

    assertFailure {
      awaitIosAppSnapshotReady(
        timeoutMs = 200,
        pollIntervalMs = 100,
        nowMs = clock::now,
        sleepMs = clock::advance,
        contentDescriptor = { throw persistentFailure },
      )
    }.isInstanceOf(MaestroException.AppCrash::class)
      .isSameInstanceAs(persistentFailure)
    assertThat(clock.now()).isEqualTo(200)
  }

  @Test
  fun `an unrelated hierarchy failure propagates without polling`() {
    val clock = TestClock()
    val hierarchyFailure = IllegalStateException("XCTest transport disconnected")

    assertFailure {
      awaitIosAppSnapshotReady(
        timeoutMs = 1_000,
        pollIntervalMs = 100,
        nowMs = clock::now,
        sleepMs = clock::advance,
        contentDescriptor = { throw hierarchyFailure },
      )
    }.isInstanceOf(IllegalStateException::class)
      .isSameInstanceAs(hierarchyFailure)
    assertThat(clock.now()).isEqualTo(0)
  }

  @Test
  fun `launchApp returns only once the launched app's hierarchy can be captured`() {
    val events = mutableListOf<String>()
    var captures = 0
    val xcTest = driverAnswering { method ->
      when (method.name) {
        "launchApp" -> events += "launch"
        "contentDescriptor" -> {
          captures++
          if (captures < 2) throw appCrash()
          events += "snapshot"
          return@driverAnswering foregroundTree(APP_ID)
        }
        else -> error("unexpected XCTest call ${method.name}")
      }
      null
    }

    IosLaunchReadinessDriver(xcTest).launchApp(APP_ID, emptyMap())

    assertThat(events).isEqualTo(listOf("launch", "snapshot"))
  }

  private fun driverAnswering(answer: (Method) -> Any?): Driver =
    Proxy.newProxyInstance(Driver::class.java.classLoader, arrayOf(Driver::class.java)) { _, method, _ ->
      answer(method)
    } as Driver

  private fun foregroundTree(appId: String) = TreeNode(
    children = listOf(TreeNode(attributes = mutableMapOf("resource-id" to appId))),
  )

  private fun appCrash() = MaestroException.AppCrash(
    "App crashed or stopped while executing flow",
    null,
  )

  private class TestClock {
    private var timeMs = 0L

    fun now(): Long = timeMs

    fun advance(durationMs: Long) {
      timeMs += durationMs
    }
  }

  private companion object {
    const val APP_ID = "com.apple.Preferences"
  }
}
