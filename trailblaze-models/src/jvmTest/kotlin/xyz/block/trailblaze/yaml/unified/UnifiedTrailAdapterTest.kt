package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.DefaultBehavior
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ElectronAppConfig
import xyz.block.trailblaze.yaml.TrailArgConfig
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.TrailSourceType
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.VerificationStep

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
  fun `explicit empty list lowers to a declared zero-tool recording — deterministic no-op`() {
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
    // Closest-wins matched `android-tablet`, and the matched list is empty — that's a DECLARED
    // no-op (non-null recording, zero tools), not the same as no classifier matching at all. The
    // executor runs zero tools and succeeds without falling through to AI.
    assertNotNull(step.recording)
    assertTrue(step.recording!!.tools.isEmpty())
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
  fun `config lowering drops devices and keeps every device-agnostic scalar field`() {
    val unifiedConfig = UnifiedTrailConfig(
      id = "myapp/checkout",
      target = "myapp",
      title = "Checkout with a saved card",
      description = "Open the checkout flow and pay.",
      priority = "P1",
      devices = mapOf("android-phone" to "ANDROID_ONDEVICE_INSTRUMENTATION"),
      context = "Test context",
      memory = mapOf("email" to "tb+test@example.com"),
      metadata = mapOf(
        "jira" to "PROJ-123",
        UnifiedTrailConfig.METADATA_KEY_SOURCE to "HANDWRITTEN",
        UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON to "authored by hand",
      ),
    )
    val v1 = UnifiedTrailAdapter.lowerConfig(unifiedConfig)
    assertEquals("myapp/checkout", v1.id)
    assertEquals("myapp", v1.target)
    // description is runtime-surfaced; it must round-trip back to v1, not be dropped.
    assertEquals("Open the checkout flow and pay.", v1.description)
    assertEquals("Test context", v1.context)
    // memory flows through so the v3 runner can pre-seed AgentMemory before the first step.
    // Without this, `config.memory:` in v3 YAMLs would parse but never reach the runner.
    assertEquals(mapOf("email" to "tb+test@example.com"), v1.memory)
    assertEquals("Checkout with a saved card", v1.title)
    // priority is a first-class unified field and lowers verbatim; source is metadata by nature
    // in the unified format (reserved bridge keys), but internal tooling reads it as a
    // first-class v1 field — lowering lifts the bridge keys back onto TrailConfig.source and
    // strips them from the plain metadata map.
    assertEquals("P1", v1.priority)
    assertEquals(TrailSource(type = TrailSourceType.HANDWRITTEN, reason = "authored by hand"), v1.source)
    assertEquals(mapOf("jira" to "PROJ-123"), v1.metadata)
    // driver is resolved per-device at lowerToTrailItems time, not by lowerConfig alone.
    assertNull(v1.driver, "lowerConfig without a resolved driver leaves v1.driver null")
    // platform is the one retired field — derived from the classifier slots, never lowered.
    assertNull(v1.platform, "the unified format has no platform field — must lower as null")
  }

  @Test
  fun `an unrecognized metadata source value is not destroyed by the bridge`() {
    // `metadata.source` only bridges to TrailConfig.source when it is empty (bare `source: {}`
    // marker) or a known TrailSourceType name — anything else is the author's own metadata and
    // must stay in the map untouched instead of being consumed by a failed parse.
    val v1 = UnifiedTrailAdapter.lowerConfig(
      UnifiedTrailConfig(metadata = mapOf(UnifiedTrailConfig.METADATA_KEY_SOURCE to "some-other-system")),
    )
    assertNull(v1.source)
    assertEquals(mapOf(UnifiedTrailConfig.METADATA_KEY_SOURCE to "some-other-system"), v1.metadata)
  }

  /**
   * A v1 config with every convertible field set — the round-trip test's completeness guard
   * walks the serial descriptor to enforce that, so a future `TrailConfig` field must be added
   * here (and thereby to every conversion covered below) before it can ship. Two fields are
   * deliberately absent: `platform` (retired — derived from classifier slots) and `electron`
   * (refused by [UnifiedTrailAdapter.v1ConfigToUnifiedConfig] — fail loud, never silently drop).
   */
  private fun fullV1Config() = TrailConfig(
    context = "Extra LLM context",
    id = "app/checkout",
    title = "Checkout with a saved card",
    description = "Open the checkout flow and pay.",
    priority = "P1",
    source = TrailSource(type = TrailSourceType.HANDWRITTEN, reason = "authored by hand"),
    metadata = mapOf("case" to "C123"),
    target = "app",
    driver = "ANDROID_ONDEVICE_ACCESSIBILITY",
    tags = listOf("smoke"),
    skip = "blocked on #123",
    memory = mapOf("email" to "tb+test@example.com"),
    args = mapOf(
      "recipient" to TrailArgConfig(type = TrailArgConfig.STRING),
      "retries" to TrailArgConfig(type = TrailArgConfig.INTEGER, default = DefaultBehavior.Use(JsonPrimitive("3"))),
    ),
  )

  @Test
  fun `every v1 config field round-trips v1 to unified to v1 losslessly`() {
    // One fixture with EVERY v1 TrailConfig field set (the guard below enforces completeness),
    // pushed through the same conversion the migrator and recorder first-write use, then lowered
    // back for the recording device. Field-by-field equality plus byte-equal re-encoding: no v1
    // config field may be silently dropped by the unified format.
    val v1 = fullV1Config()

    // Fixture-completeness guard: every TrailConfig field must be set above, except the two
    // with an explicit no-home decision — `platform` (retired: the device set derives from the
    // classifier slots) and `electron` (refused: conversion fails loud on it, tested below).
    // encodeDefaults=false omits unset fields, so a newly added v1 field that this fixture
    // doesn't cover fails here — forcing it into the round-trip (or an explicit decision)
    // instead of being silently dropped by conversion.
    val encodedV1 = TrailblazeYaml.defaultYamlInstance.encodeToString(TrailConfig.serializer(), v1)
    val descriptor = TrailConfig.serializer().descriptor
    val fieldNames = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
    for (field in fieldNames) {
      if (field == "platform" || field == "electron") continue
      assertTrue(
        encodedV1.lineSequence().any { it.startsWith("$field:") },
        "fixture must set v1 `$field:` so the round-trip covers it — a new TrailConfig field " +
          "needs a unified home (or an explicit decision like `platform`/`electron`)",
      )
    }

    // v1 → unified, seeded the way the migrator and recorder first-write do: device-agnostic
    // scalars via the shared helper, the two per-platform v1 scalars keyed under the recording
    // device's classifier, tags verbatim.
    val unified = UnifiedTrailAdapter.v1ConfigToUnifiedConfig(v1).copy(
      devices = mapOf("android" to v1.driver!!),
      skip = mapOf("android" to v1.skip!!),
      tags = v1.tags,
    )
    // Pin the unified representation: priority is a top-level unified field; source is NOT — it
    // rides in metadata under the reserved bridge keys.
    assertEquals("P1", unified.priority)
    assertEquals("HANDWRITTEN", unified.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE))
    assertEquals("authored by hand", unified.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON))

    // unified → v1 for an android device (chain [android-phone, android]).
    val device = listOf(classifier("android"), classifier("phone"))
    val lowered = UnifiedTrailAdapter.lowerConfig(
      unified,
      resolvedDriver = UnifiedTrailAdapter.resolveDriver(unified, device),
      resolvedSkip = UnifiedTrailAdapter.resolveSkip(unified, device),
    )
    assertEquals(v1, lowered, "every v1 config field must survive v1 → unified → v1")
    assertEquals(
      encodedV1,
      TrailblazeYaml.defaultYamlInstance.encodeToString(TrailConfig.serializer(), lowered),
      "the round-tripped config must re-encode byte-equal to the original",
    )
  }

  @Test
  fun `fillMissingConfigScalars carries every scalar the base lacks`() {
    // Chained to the same descriptor-guarded fixture as the round-trip test: the guard forces
    // every v1 field into fullV1Config, v1ConfigToUnifiedConfig must carry it (or the round-trip
    // fails), and this assertion then fails if the fill helper misses it — so the migrator's
    // fold can't silently drop a future config field that only a later file declares.
    val scalarSeed = UnifiedTrailAdapter.v1ConfigToUnifiedConfig(fullV1Config())
    assertEquals(
      scalarSeed,
      UnifiedTrailAdapter.fillMissingConfigScalars(UnifiedTrailConfig(), scalarSeed),
      "an empty base filled from a fully-populated fallback must equal the fallback's scalars",
    )
  }

  @Test
  fun `fillMissingConfigScalars treats placeholders as absent but keeps them when nothing better exists`() {
    // A first file's blank title must not shadow a later file's populated value…
    val placeholder = UnifiedTrailConfig(title = "  ")
    val filled = UnifiedTrailAdapter.fillMissingConfigScalars(placeholder, UnifiedTrailConfig(title = "Real title"))
    assertEquals("Real title", filled.title)
    // …but a placeholder survives when no file declares better.
    val kept = UnifiedTrailAdapter.fillMissingConfigScalars(placeholder, UnifiedTrailConfig())
    assertEquals("  ", kept.title)
  }

  @Test
  fun `a v1 config with an electron block is refused, never silently dropped`() {
    // The unified config deliberately has no electron field (driver-specific launch config,
    // zero corpus usage) — conversion must fail loud rather than change how the trail launches.
    val v1 = TrailConfig(id = "app/x", electron = ElectronAppConfig(command = "/opt/app/electron-app"))
    val error = assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.v1ConfigToUnifiedConfig(v1)
    }
    assertTrue("electron" in error.message.orEmpty())
  }

  @Test
  fun `fillMissingConfigScalars merges metadata per key — first file wins a shared key, later files fill new keys`() {
    // Per-key (not whole-map) merge is what keeps the bridged source lossless: one file's
    // `source:` and another file's plain metadata both land in the same map, and an atomic
    // first-map-wins would re-drop whichever the first file lacked.
    val base = UnifiedTrailConfig(
      metadata = mapOf("case" to "C123", "jira" to "PROJ-1"),
    )
    val fallback = UnifiedTrailConfig(
      metadata = mapOf(UnifiedTrailConfig.METADATA_KEY_SOURCE to "HANDWRITTEN", "jira" to "PROJ-2"),
    )
    val merged = UnifiedTrailAdapter.fillMissingConfigScalars(base, fallback)
    assertEquals("C123", merged.metadata?.get("case"))
    assertEquals("HANDWRITTEN", merged.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE))
    assertEquals("PROJ-1", merged.metadata?.get("jira"), "base wins a shared key")
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
  fun `a verify step lowers to a v1 VerificationStep with its recording and overrides`() {
    // Verify semantics are load-bearing at run time (assertion-scoped tool surface,
    // auto-terminate, never self-healed) — the lowering must produce a VerificationStep,
    // not silently downgrade to a DirectionStep.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart"),
        UnifiedTrailStep(
          step = "The cart shows 2 items",
          verify = true,
          recordings = mapOf("android" to listOf(toolNamed("assert-items"))),
          maxRetries = 5,
        ),
      ),
    )
    val steps = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("android"), classifier("phone")),
    ).filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps

    assertTrue(steps[0] is DirectionStep, "a plain step lowers to DirectionStep")
    val verify = steps[1] as VerificationStep
    assertEquals("The cart shows 2 items", verify.prompt)
    assertEquals(listOf("assert-items"), verify.recording?.tools?.map { it.name })
    assertEquals(5, verify.maxRetries)
    assertTrue(verify.recordable)
  }

  @Test
  fun `an always-LLM verify step lowers to a recordable-false VerificationStep`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "The receipt banner is correct", verify = true, recordable = false),
      ),
    )
    val step = UnifiedTrailAdapter.lowerToTrailItems(
      unified,
      classifiers = listOf(classifier("android"), classifier("phone")),
    ).filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps.single() as VerificationStep
    assertFalse(step.recordable, "recordable: false must survive lowering on a verify step")
    assertNull(step.recording)
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

  @Test
  fun `hasRecordingForDevice — android-only recording counts for android but not ios`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Tap", recordings = mapOf("android" to listOf(toolNamed("t")))),
      ),
    )
    assertTrue(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("android"), classifier("phone")),
      ),
    )
    assertFalse(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("ios"), classifier("iphone")),
      ),
    )
  }

  @Test
  fun `hasRecordingForDevice — family android recording covers phone and tablet`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Tap", recordings = mapOf("android" to listOf(toolNamed("t")))),
      ),
    )
    assertTrue(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("android"), classifier("phone")),
      ),
    )
    assertTrue(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("android"), classifier("tablet")),
      ),
    )
  }

  @Test
  fun `hasRecordingForDevice — matched empty list counts as a deterministic recording`() {
    // A matched `android: []` lowers to a NON-NULL zero-tool ToolRecording (a deterministic no-op,
    // not LLM mode) in lowerToTrailItems, and hasRecordedSteps counts it — so the gate counts it too.
    // Only an UNMATCHED chain (null resolution) is "no recording".
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "Tap", recordings = mapOf("android" to emptyList())),
      ),
    )
    assertTrue(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("android"), classifier("phone")),
      ),
      "a matched android:[] is a deterministic recording for android",
    )
    // …but the same `android: []` does NOT match an ios device's chain, so it is not a recording
    // there (the entry is declared for android only).
    assertFalse(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("ios"), classifier("iphone")),
      ),
      "android:[] must not count for ios (no android in the ios chain)",
    )
  }

  @Test
  fun `hasRecordingForDevice — a recorded trailhead counts even when no step is recorded`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(
        step = "Launch",
        recordings = mapOf("android" to listOf(toolNamed("launch"))),
      ),
      trail = listOf(UnifiedTrailStep(step = "LLM only")),
    )
    assertTrue(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("android"), classifier("phone")),
      ),
    )
  }

  @Test
  fun `hasRecordingForDevice — no recordings at all is false`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(UnifiedTrailStep(step = "LLM only")),
    )
    assertFalse(
      UnifiedTrailAdapter.hasRecordingForDevice(
        unified,
        listOf(classifier("android"), classifier("phone")),
      ),
    )
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
