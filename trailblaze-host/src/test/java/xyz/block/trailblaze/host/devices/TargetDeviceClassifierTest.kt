package xyz.block.trailblaze.host.devices

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.TargetDeviceClassifierConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.scripting.WorkspaceSurveyBundleSource

class TargetDeviceClassifierTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val config = TargetDeviceClassifierConfig(name = "app_deviceClassifier", script = "/unused.ts")
  private val phone = key("android-phone")
  private val tablet = key("android-tablet")

  /** A classifier that returns [key] and records what it was asked. */
  private class Stub(private val key: String) : TargetDeviceClassifier.Runner {
    var input: JsonObject? = null
    override suspend fun run(config: TargetDeviceClassifierConfig, input: JsonObject): JsonObject {
      this.input = input
      return buildJsonObject {
        put("structuredContent", buildJsonObject { putJsonArray("key") { key.split("-").forEach { add(JsonPrimitive(it)) } } })
      }
    }
  }

  private fun keyFor(
    runner: TargetDeviceClassifier.Runner,
    requested: String = "",
    detected: List<TrailblazeDeviceClassifier> = phone,
    facts: TargetDeviceClassifier.DeviceFacts = TargetDeviceClassifier.DeviceFacts(),
    config: TargetDeviceClassifierConfig? = this.config,
  ) = runBlocking {
    TargetDeviceClassifier.keyFor(
      config = config,
      platform = TrailblazeDevicePlatform.ANDROID,
      detected = detected,
      requested = if (requested.isEmpty()) emptyList() else key(requested),
      facts = { facts },
      runner = runner,
    ).joinToString("-") { it.classifier }
  }

  @Test
  fun `the classifier's key is the device's key`() {
    val stub = Stub("acme-k2")
    assertEquals("acme-k2", keyFor(stub, detected = tablet))
    assertEquals("android", stub.input!!["platform"]!!.jsonPrimitive.content)
    assertEquals(listOf("android", "tablet"), stub.input!!["key"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun `the classifier is told the device's maker and model, and nothing else`() {
    val stub = Stub("android-phone")
    keyFor(stub, facts = TargetDeviceClassifier.DeviceFacts(manufacturer = "Acme", model = "Acme Kiosk 2"))
    assertEquals("Acme", stub.input!!["manufacturer"]!!.jsonPrimitive.content)
    assertEquals("Acme Kiosk 2", stub.input!!["model"]!!.jsonPrimitive.content)
    assertEquals(setOf("platform", "key", "manufacturer", "model"), stub.input!!.keys)
  }

  @Test
  fun `the rest of a requested key goes on the end of the classifier's key`() {
    assertEquals("android-phone-es", keyFor(Stub("android-phone"), requested = "android-phone-es"))
    // Asked in the framework's terms or the family's, a locale lands on the family key.
    assertEquals("acme-k2-es", keyFor(Stub("acme-k2"), requested = "android-tablet-es", detected = tablet))
    assertEquals("acme-k2-es", keyFor(Stub("acme-k2"), requested = "acme-k2-es", detected = tablet))
    // A host whose framework key already is the family's.
    assertEquals("acme-k2-es", keyFor(Stub("acme-k2"), requested = "acme-k2-es", detected = key("acme-k2")))
    // A key that extends the framework's, asked for in its own terms or the framework's.
    assertEquals("android-phone-rugged-es", keyFor(Stub("android-phone-rugged"), requested = "android-phone-rugged-es"))
    assertEquals("android-phone-rugged", keyFor(Stub("android-phone-rugged"), requested = "android-phone"))
  }

  @Test
  fun `a requested key that isn't this device fails`() {
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("acme-k2"), requested = "acme", detected = tablet) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("acme-k2"), requested = "acme-k3", detected = tablet) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("android-tablet"), requested = "acme-k2", detected = tablet) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("acme-k2"), requested = "android-phone", detected = tablet) }
  }

  @Test
  fun `a requested key in the classifier's terms is refused for what a run without one refuses`() {
    // A platform and a form factor that don't go together.
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("acme-k2"), requested = "acme-k2-android-iphone", detected = tablet) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("android-phone"), requested = "android-phone-tablet") }
  }

  @Test
  fun `a classifier can't change the platform or form factor`() {
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("android-phone-tablet")) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("android-tablet")) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("android-testrack")) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("ios-iphone")) }
    // On a key that names no form factor, the classifier may not add one either.
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("android-phone"), detected = key("android")) }
  }

  @Test
  fun `a classifier can name a device family the framework doesn't know`() {
    assertEquals("acme-k2", keyFor(Stub("acme-k2"), detected = tablet))
    // A family key may not borrow a platform or form factor.
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("acme-tablet"), detected = tablet) }
    assertFailsWith<IllegalArgumentException> { keyFor(Stub("acme-android"), detected = tablet) }
  }

  @Test
  fun `a classifier that returns a bad value fails the key`() {
    val bad = TargetDeviceClassifier.Runner { _, _ ->
      buildJsonObject { putJsonArray("key") { add(JsonPrimitive("android")); add(JsonPrimitive("Test-Rack")) } }
    }
    assertFailsWith<IllegalArgumentException> { keyFor(bad) }
  }

  @Test
  fun `a classifier that fails fails the key`() {
    val failing = TargetDeviceClassifier.Runner { _, _ -> error("boom") }
    val e = assertFailsWith<IllegalArgumentException> { keyFor(failing) }
    assertTrue(e.message!!.contains("app_deviceClassifier") && e.message!!.contains("boom"), e.message)
  }

  @Test
  fun `a cancelled run stays cancelled`() {
    val cancelled = TargetDeviceClassifier.Runner { _, _ -> throw CancellationException("stopped") }
    assertFailsWith<CancellationException> { keyFor(cancelled) }
  }

  @Test
  fun `without a classifier the requested key refines the device's key as before, and no facts are read`() {
    val unused = TargetDeviceClassifier.Runner { _, _ -> error("not called") }
    var factsRead = false
    val key = runBlocking {
      TargetDeviceClassifier.keyFor(
        config = null,
        platform = TrailblazeDevicePlatform.ANDROID,
        detected = phone,
        requested = key("android-phone-es"),
        facts = { factsRead = true; TargetDeviceClassifier.DeviceFacts() },
        runner = unused,
      )
    }
    assertEquals(key("android-phone-es"), key)
    assertEquals(false, factsRead)
    assertEquals("android-phone", keyFor(unused, config = null))
    assertFailsWith<IllegalArgumentException> { keyFor(unused, requested = "android-tablet", config = null) }
  }

  private fun forRun(
    runner: TargetDeviceClassifier.Runner,
    definitive: Boolean = true,
    requested: String = "",
    detected: List<TrailblazeDeviceClassifier> = tablet,
  ) = runBlocking {
    TargetDeviceClassifier.forRun(
      config = config,
      definitive = definitive,
      platform = TrailblazeDevicePlatform.ANDROID,
      detected = detected,
      requested = if (requested.isEmpty()) emptyList() else key(requested),
      facts = { TargetDeviceClassifier.DeviceFacts() },
      runner = runner,
    )
  }

  @Test
  fun `a run sends the classifier's key only when it changed the key`() {
    val changed = forRun(Stub("acme-k2"))
    assertEquals(key("acme-k2"), changed.override)
    assertTrue(changed.message!!.contains("acme-k2"), changed.message)

    val unchanged = forRun(Stub("android-tablet"))
    assertEquals(key("android-tablet"), unchanged.key)
    assertEquals(null, unchanged.override)

    // The framework already gave this key.
    assertEquals(null, forRun(Stub("acme-k2"), detected = key("acme-k2")).override)
  }

  @Test
  fun `a run that asks for a device family's key sends the classifier's key`() {
    val run = forRun(Stub("acme-k2"), requested = "acme-k2-es")
    assertEquals(key("acme-k2-es"), run.key)
    assertEquals(key("acme-k2-es"), run.override)
  }

  @Test
  fun `a run on a device whose form factor wasn't measured skips the classifier and says so`() {
    val run = forRun(TargetDeviceClassifier.Runner { _, _ -> error("not called") }, definitive = false, requested = "android-tablet-es")
    assertEquals(key("android-tablet-es"), run.key)
    assertEquals(null, run.override)
    assertTrue(run.message!!.contains("app_deviceClassifier skipped"), run.message)
  }

  /** The whole path: esbuild bundles a target's `trailblaze.deviceClassifier` and QuickJS runs it. */
  @Test
  fun `a TypeScript classifier runs in QuickJS`() {
    val bundler = WorkspaceSurveyBundleSource.resolvedToolchainBundler(tempFolder.newFolder("cache"))
    // CI installs both, so a skip there would report green over the whole path.
    if (System.getenv("CI") == "true") assertTrue(bundler != null, "esbuild and the TypeScript SDK are needed")
    assumeTrue("needs esbuild and the TypeScript SDK source (bun install in sdks/typescript)", bundler != null)
    val script = File(tempFolder.newFolder("app", "tools"), "app_deviceClassifier.ts").apply {
      writeText(
        """
        |import { trailblaze } from "@trailblaze/scripting";
        |export const Acme = { Family: "acme", K2: "k2" } as const;
        |export const app_deviceClassifier = trailblaze.deviceClassifier((device) =>
        |  device.manufacturer === "Acme" && device.model?.startsWith("Acme Kiosk 2") ? [Acme.Family, Acme.K2] : device.key,
        |);
        """.trimMargin(),
      )
    }
    val runner = TargetDeviceClassifier.quickJsRunner(bundler = { bundler }, entryDir = tempFolder.newFolder("entries"))
    val config = TargetDeviceClassifierConfig(name = "app_deviceClassifier", script = script.path)
    val kiosk = TargetDeviceClassifier.DeviceFacts(manufacturer = "Acme", model = "Acme Kiosk 2")

    assertEquals("acme-k2", keyFor(runner, detected = tablet, facts = kiosk, config = config))
    assertEquals("acme-k2-es", keyFor(runner, requested = "android-tablet-es", detected = tablet, facts = kiosk, config = config))
    assertEquals("android-tablet", keyFor(runner, detected = tablet, config = config))
  }

  private fun key(value: String) = value.split("-").map(::TrailblazeDeviceClassifier)
}
