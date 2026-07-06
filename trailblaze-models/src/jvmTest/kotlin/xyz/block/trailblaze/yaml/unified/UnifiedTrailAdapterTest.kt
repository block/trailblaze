package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Pins the the unified format → v1 lowering used by the runtime: closest-wins classifier
 * resolution, the lossy-but-correct mapping of step features into
 * `DirectionStep`s, and the dropped-fields contract for the config.
 *
 * This is the bridge that lets the existing v1 executor consume a the unified format trail
 * without a rewrite. The semantics tested here ARE the runtime semantics
 * a the unified format file actually sees.
 */
class UnifiedTrailAdapterTest {

  @Test
  fun `closest-wins picks the most specific classifier match`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Tap something",
          recordings = linkedMapOf(
            "ios-iphone" to listOf(toolNamed("specific-iphone")),
            "ios" to listOf(toolNamed("family-ios")),
          ),
        ),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      // iPhone device: provider emits broad-first segments `[ios, iphone]`,
      // which the lineage joins+expands to `[ios-iphone, ios]`.
      classifiers = listOf(classifier("ios"), classifier("iphone")),
    )
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    val tools = step.recording?.tools.orEmpty()
    assertEquals(1, tools.size)
    assertEquals("specific-iphone", tools[0].name)
  }

  @Test
  fun `closest-wins falls through to family classifier when specific not present`() {
    // An iPad device: provider emits `[ios, ipad]` → lineage `[ios-ipad, ios]`.
    // The step has only `ios:` recordings → we walk ios-ipad → ios → family.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Tap something",
          recordings = mapOf("ios" to listOf(toolNamed("family-ios"))),
        ),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("ios"), classifier("ipad")),
    )
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    val tools = step.recording?.tools.orEmpty()
    assertEquals(1, tools.size)
    assertEquals("family-ios", tools[0].name)
  }

  @Test
  fun `no classifier match leaves recording null — executor falls back to LLM mode`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Tap something",
          recordings = mapOf("android-phone" to listOf(toolNamed("android-only"))),
        ),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("ios"), classifier("iphone")),
    )
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    assertNull(step.recording, "no classifier match → no recording → LLM mode at runtime")
    assertTrue(step.recordable, "recordable defaults to true even when this device has no recording")
  }

  @Test
  fun `explicit empty list is treated as no recording — explicit no-op for this device`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Skip on tablet",
          recordings = linkedMapOf(
            "android-tablet" to emptyList(),
            "android-phone" to listOf(toolNamed("phone-tool")),
          ),
        ),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("android"), classifier("tablet")),
    )
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    assertNull(
      step.recording,
      "explicit empty classifier list lowers to a null recording — closest-wins matched, " +
        "but the matched list is empty, so the executor runs no tools",
    )
  }

  @Test
  fun `recordable false propagates through to the lowered DirectionStep`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "LLM step", recordings = emptyMap(), recordable = false),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("android"), classifier("phone")),
    )
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    assertFalse(step.recordable, "recordable: false must survive lowering")
    assertNull(step.recording)
  }

  @Test
  fun `config lowering drops devices and keeps id target description context memory metadata`() {
    val unifiedConfig = UnifiedTrailConfig(
      id = "myapp/checkout",
      target = "myapp",
      description = "Open the checkout flow and pay.",
      devices = mapOf("android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION"),
      context = "Test context",
      memory = mapOf("email" to "tb+test@example.com"),
      metadata = mapOf("jira" to "PROJ-123"),
    )
    val v1 = UnifiedTrailAdapter.lowerConfig(unifiedConfig)
    assertEquals("myapp/checkout", v1.id)
    assertEquals("myapp", v1.target)
    // description is runtime-surfaced; it must round-trip back to v1, not be dropped.
    assertEquals("Open the checkout flow and pay.", v1.description)
    assertEquals("Test context", v1.context)
    assertEquals(mapOf("jira" to "PROJ-123"), v1.metadata)
    // memory flows through so the v3 runner can pre-seed AgentMemory before the first step.
    // Without this, `config.memory:` in v3 YAMLs would parse but never reach the runner.
    assertEquals(mapOf("email" to "tb+test@example.com"), v1.memory)
    // driver is resolved per-device at lowerToTrailItems time, not by lowerConfig alone.
    assertNull(v1.driver, "lowerConfig without a resolved driver leaves v1.driver null")
    // unified-only fields that the v1 executor doesn't model are dropped.
    assertNull(v1.platform, "the unified format has no platform field — must lower as null")
    assertNull(v1.title, "the unified format has no title field — must lower as null")
  }

  @Test
  fun `per-classifier driver resolves closest-wins for the device under test`() {
    // A multi-platform trail pins a driver per platform; each device gets its own.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(
        id = "x",
        target = "y",
        devices = linkedMapOf(
          "android" to "ANDROID_ONDEVICE_ACCESSIBILITY",
          "ios" to "IOS_HOST",
        ),
      ),
      trail = listOf(UnifiedTrailStep(step = "s", recordable = false)),
    )

    fun driverFor(vararg segs: String): String? =
      UnifiedTrailAdapter.lowerToTrailItems(unified, segs.map { classifier(it) })
        .filterIsInstance<TrailYamlItem.ConfigTrailItem>().single().config.driver

    // android-phone → chain [android-phone, android] → matches the `android` pin.
    assertEquals("ANDROID_ONDEVICE_ACCESSIBILITY", driverFor("android", "phone"))
    // iPhone → chain [ios-iphone, ios] → matches the `ios` pin (different driver).
    assertEquals("IOS_HOST", driverFor("ios", "iphone"))
  }

  @Test
  fun `driver is null when the config pins none for the device's classifier chain`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y", devices = mapOf("ios" to "IOS_HOST")),
      trail = listOf(UnifiedTrailStep(step = "s", recordable = false)),
    )
    val v1 = UnifiedTrailAdapter.lowerToTrailItems(unified, listOf(classifier("android"), classifier("phone")))
      .filterIsInstance<TrailYamlItem.ConfigTrailItem>().single().config
    assertNull(v1.driver, "no android pin → driver resolves at run time (null here)")
  }

  @Test
  fun `per-classifier skip resolves closest-wins for the device under test`() {
    // A trail skipped on android but not ios: each device sees only its own verdict.
    val config = UnifiedTrailConfig(
      id = "x",
      target = "y",
      skip = linkedMapOf("android" to "flaky on android — see #123"),
    )
    // Android phone → chain [android-phone, android] → matches the `android` skip.
    assertEquals(
      "flaky on android — see #123",
      UnifiedTrailAdapter.resolveSkip(config, listOf(classifier("android"), classifier("phone"))),
    )
    // iPhone → chain [ios-iphone, ios] → no match → runs (per-platform skip).
    assertNull(
      UnifiedTrailAdapter.resolveSkip(config, listOf(classifier("ios"), classifier("iphone"))),
      "a skip pinned only to android must not skip an ios run",
    )
  }

  @Test
  fun `resolveSkip device-agnostic — any declared skip counts as skipped`() {
    val config = UnifiedTrailConfig(id = "x", target = "y", skip = mapOf("ios" to "ios only"))
    // No classifiers (pre-flight with no device yet): skipped if ANY classifier declares a reason,
    // so the CLI's skip gate still fires. Blank reasons are ignored.
    assertEquals("ios only", UnifiedTrailAdapter.resolveSkip(config, emptyList()))
    assertNull(
      UnifiedTrailAdapter.resolveSkip(
        UnifiedTrailConfig(id = "x", target = "y", skip = mapOf("android" to "  ")),
        emptyList(),
      ),
      "a blank skip reason is not a skip",
    )
    assertNull(
      UnifiedTrailAdapter.resolveSkip(UnifiedTrailConfig(id = "x", target = "y"), emptyList()),
      "no skip map → null",
    )
    // Multi-entry map, device-agnostic → deterministic (lowest classifier key), never decode-order.
    assertEquals(
      "aaa",
      UnifiedTrailAdapter.resolveSkip(
        UnifiedTrailConfig(id = "x", target = "y", skip = linkedMapOf("z" to "zzz", "a" to "aaa")),
        emptyList(),
      ),
      "device-agnostic skip picks the lowest classifier key deterministically",
    )
  }

  @Test
  fun `lowerConfig carries resolved skip and verbatim tags to the v1 TrailConfig`() {
    val config = UnifiedTrailConfig(
      id = "x",
      target = "y",
      tags = listOf("smoke", "flaky"),
      skip = mapOf("android" to "blocked"),
    )
    // Full lowering (device-aware) resolves the android skip and passes tags through.
    val v1 = UnifiedTrailAdapter.lowerToTrailItems(config.let {
      UnifiedTrail(config = it, trail = listOf(UnifiedTrailStep(step = "s", recordable = false)))
    }, listOf(classifier("android"), classifier("phone")))
      .filterIsInstance<TrailYamlItem.ConfigTrailItem>().single().config
    assertEquals("blocked", v1.skip, "resolved per-classifier skip must lower to v1 skip")
    assertEquals(listOf("smoke", "flaky"), v1.tags, "tags lower verbatim (trail-level, not per-device)")
  }

  @Test
  fun `config lowering preserves null memory when v3 config omits the field`() {
    val unifiedConfig = UnifiedTrailConfig(id = "x", target = "y")
    val v1 = UnifiedTrailAdapter.lowerConfig(unifiedConfig)
    assertNull(v1.memory, "absent config.memory must round-trip to v1 as null, not empty map")
  }

  @Test
  fun `resolveDriver resolves the per-classifier pin closest-wins for the device`() {
    // The driver a unified trail pins for a device must be resolvable BEFORE device selection
    // (the CLI needs it to pick the ANDROID_ONDEVICE_ACCESSIBILITY device variant rather than
    // the INSTRUMENTATION default). This is that resolution.
    val config = UnifiedTrailConfig(
      id = "x",
      target = "y",
      devices = linkedMapOf(
        "android" to "ANDROID_ONDEVICE_ACCESSIBILITY",
        "android-tablet" to "ANDROID_ONDEVICE_INSTRUMENTATION",
        "ios" to "IOS_HOST",
      ),
    )
    // Phone → chain [android-phone, android, phone] → family `android` pin.
    assertEquals(
      "ANDROID_ONDEVICE_ACCESSIBILITY",
      UnifiedTrailAdapter.resolveDriver(config, listOf(classifier("android"), classifier("phone"))),
    )
    // Tablet → chain [android-tablet, android, tablet] → the more-specific `android-tablet` pin.
    assertEquals(
      "ANDROID_ONDEVICE_INSTRUMENTATION",
      UnifiedTrailAdapter.resolveDriver(config, listOf(classifier("android"), classifier("tablet"))),
    )
    // iPhone → the `ios` pin.
    assertEquals(
      "IOS_HOST",
      UnifiedTrailAdapter.resolveDriver(config, listOf(classifier("ios"), classifier("iphone"))),
    )
  }

  @Test
  fun `resolveDriver returns null when nothing matches or no pins exist`() {
    val pinned = UnifiedTrailConfig(id = "x", target = "y", devices = mapOf("ios" to "IOS_HOST"))
    // Android device, only an ios pin → no match → null (driver resolves at run time).
    assertNull(UnifiedTrailAdapter.resolveDriver(pinned, listOf(classifier("android"), classifier("phone"))))
    // Empty classifier list (e.g. no --device spec) → null, never throws.
    assertNull(UnifiedTrailAdapter.resolveDriver(pinned, emptyList()))
    // No devices map at all → null.
    assertNull(
      UnifiedTrailAdapter.resolveDriver(
        UnifiedTrailConfig(id = "x", target = "y"),
        listOf(classifier("android")),
      ),
    )
  }

  @Test
  fun `lowered output has exactly one config item followed by one prompts item`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Step 1", recordings = mapOf("android" to listOf(toolNamed("t1")))),
        UnifiedTrailStep(step = "Step 2", recordings = mapOf("android" to listOf(toolNamed("t2")))),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("android"), classifier("phone")),
    )
    assertEquals(2, items.size)
    assertTrue(items[0] is TrailYamlItem.ConfigTrailItem)
    val prompts = items[1] as TrailYamlItem.PromptsTrailItem
    assertEquals(2, prompts.promptSteps.size)
  }

  @Test
  fun `empty classifier list lowers to no recordings — config-only safe mode`() {
    // This is the safe default for callers that only need static config
    // (e.g. trail-dir resolution / device picking). Recordings get dropped;
    // the caller is expected to call extractTrailConfig and ignore the
    // empty prompt list, NOT execute.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Step 1", recordings = mapOf("android" to listOf(toolNamed("t1")))),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(unified, classifiers = emptyList())
    val prompts = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
    val step = prompts.promptSteps.single() as DirectionStep
    assertNotNull(step.step)
    assertNull(step.recording, "empty classifiers → no closest-wins match → no recording")
  }

  @Test
  fun `maxRetries on a unified step lowers to DirectionStep maxRetries`() {
    // Regression guard for codex P1 on PR #3476: the per-step retry override must
    // round-trip from the unified format all the way to the v1 DirectionStep the
    // executor consumes — otherwise the feature is unreachable from any trail
    // authored in the unified YAML shape.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Enter 6-digit passcode", maxRetries = 10),
        UnifiedTrailStep(step = "Default-retry step"),
      ),
    )
    val items = UnifiedTrailAdapter.lowerToTrailItems(unified, classifiers = emptyList())
    val prompts = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
    val steps = prompts.promptSteps.map { it as DirectionStep }
    assertEquals(10, steps[0].maxRetries, "explicit maxRetries must propagate to lowered step")
    assertNull(steps[1].maxRetries, "absent maxRetries must lower to null (use trail-wide default)")
  }

  private fun classifier(value: String) = TrailblazeDeviceClassifier(value)

  private fun toolNamed(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(
      toolName = name,
      raw = JsonObject(mapOf("marker" to JsonPrimitive(name))),
    ),
  )
}
