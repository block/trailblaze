package xyz.block.trailblaze.host

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail
import org.junit.Test
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrail

/**
 * Behavioral contract for [TrailTargetLint]. Trails are written as YAML and decoded through the real
 * parser rather than assembled as objects, so a change to how `config.target:` or a per-device
 * `target:` deserializes shows up here instead of passing against a hand-built model the runtime
 * would never produce.
 */
class TrailTargetLintTest {

  private val registered = setOf("wikipedia", "contacts", "default")

  private fun lint(
    yaml: String,
    registeredTargetIds: Set<String> = registered,
    trailmapsDir: String? = null,
  ) = TrailTargetLint.lint(
    trailRelPath = "flows/example.trail.yaml",
    workspaceLabel = "ws",
    trail = decode(yaml),
    registeredTargetIds = registeredTargetIds,
    trailmapsDir = trailmapsDir,
  )

  private fun decode(yaml: String): UnifiedTrail =
    when (val doc = createTrailblazeYaml().decodeTrailDocument(yaml)) {
      is TrailDocument.Unified -> doc.trail
    }

  @Test
  fun `a registered target is not a finding`() {
    assertNull(
      lint(
        """
        config:
          target: wikipedia
        trail:
          - step: Open the app.
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `an unregistered target is a finding naming the id as written`() {
    val finding = lint(
      """
      config:
        target: xyz.block.trailblaze.examples.wikipedia
      trail:
        - step: Open the app.
      """.trimIndent(),
    )
    assertEquals(
      listOf(TrailTargetLint.UnresolvedTarget("xyz.block.trailblaze.examples.wikipedia")),
      finding?.unresolved,
    )
  }

  @Test
  fun `no target at all is not a finding`() {
    // Omitting `config.target:` asks for the workspace default on purpose. Getting the default
    // because the id you named resolves to nothing is the thing this gate catches; asking for it is
    // not.
    assertNull(
      lint(
        """
        config:
          title: No target declared
        trail:
          - step: Open the app.
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `target matching is case-insensitive like findById`() {
    assertNull(
      lint(
        """
        config:
          target: WikiPedia
        trail:
          - step: Open the app.
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `a per-device target override is checked and reported by its device path`() {
    val finding = lint(
      """
      config:
        target: wikipedia
        devices:
          pair:
            devices:
              seller:
                classifier: android-phone
                target: nosuchtarget
              buyer:
                classifier: android-phone
      trail:
        - step: Open the app.
      """.trimIndent(),
    )
    assertEquals(
      listOf(TrailTargetLint.UnresolvedTarget("nosuchtarget", devicePath = "pair.seller")),
      finding?.unresolved,
    )
  }

  @Test
  fun `an unregistered session target and an unregistered device target are both reported`() {
    val finding = lint(
      """
      config:
        target: ghost
        devices:
          pair:
            devices:
              seller:
                classifier: android-phone
                target: phantom
      trail:
        - step: Open the app.
      """.trimIndent(),
    )
    assertEquals(
      listOf(
        TrailTargetLint.UnresolvedTarget("ghost"),
        TrailTargetLint.UnresolvedTarget("phantom", devicePath = "pair.seller"),
      ),
      finding?.unresolved,
    )
  }

  @Test
  fun `a target on a plain classifier entry is not reported, because nothing reads it`() {
    // `MultiDeviceConfigurationResolver` filters `config.devices:` to entries that ARE multi-device
    // configurations before it collects any override, so a `target:` on a top-level classifier entry
    // has no run-time effect at all. Flagging it would be a finding about a dead key wearing this
    // gate's "your trail runs the wrong app" clothing.
    assertNull(
      lint(
        """
        config:
          target: wikipedia
          devices:
            android-phone:
              driver: ANDROID_ONDEVICE_ACCESSIBILITY
              target: phantom
        trail:
          - step: Open the app.
        """.trimIndent(),
      ),
    )
  }

  @Test
  fun `a blank per-device target is a finding but a blank session target is not`() {
    // Not symmetry for its own sake — the runtime is asymmetric. `MultiDeviceConfigurationResolver`
    // inherits the session target only on a NULL and throws on a blank that misses lookup, while a
    // blank `config.target:` misses `findById` and lands on the workspace default exactly like an
    // omitted key. Skipping blanks everywhere would hide the one that hard-fails.
    val finding = lint(
      """
      config:
        target: ""
        devices:
          pair:
            devices:
              seller:
                classifier: android-phone
                target: ""
      trail:
        - step: Open the app.
      """.trimIndent(),
    )
    assertEquals(
      listOf(TrailTargetLint.UnresolvedTarget("", devicePath = "pair.seller")),
      finding?.unresolved,
    )
    // …and it prints as something, rather than a line that trails off after the colon.
    assertContains(
      TrailTargetLint.renderWarnings(listOf(finding!!)),
      "config.devices.pair.seller.target: (blank)",
    )
  }

  @Test
  fun `the consequence stated matches the kind of reference, because they differ at run time`() {
    val sessionOnly = lint(
      """
      config:
        target: ghost
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!
    val deviceOnly = lint(
      """
      config:
        target: wikipedia
        devices:
          pair:
            devices:
              seller:
                classifier: android-phone
                target: phantom
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!

    // A session target degrades silently and the run still reports PASSED.
    val session = TrailTargetLint.renderWarnings(listOf(sessionOnly))
    assertContains(session, "still reports PASSED")
    assertFalse(session.contains("hard error"), "no device override here, so nothing hard-fails")

    // A per-device override does the opposite: the run refuses to start. Reusing the fallback
    // wording there would tell the reader the trail still runs, which is false.
    val device = TrailTargetLint.renderWarnings(listOf(deviceOnly))
    assertContains(device, "is a hard error")
    assertFalse(device.contains("still reports PASSED"), "no session-target finding to fall back")
  }

  @Test
  fun `the cheapest remedy names the key that is actually wrong`() {
    val sessionOnly = lint(
      """
      config:
        target: ghost
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!
    val deviceOnly = lint(
      """
      config:
        target: wikipedia
        devices:
          pair:
            devices:
              seller:
                classifier: android-phone
                target: phantom
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!

    assertContains(TrailTargetLint.renderWarnings(listOf(sessionOnly)), "Drop `config.target:`")

    // Telling someone with an unresolved cast-member override to drop `config.target:` leaves the
    // override in place and the run still throws on it — a remedy that doesn't remedy. A member with
    // no `target:` inherits the session target, so that is the equivalent move one level down.
    val device = TrailTargetLint.renderWarnings(listOf(deviceOnly))
    assertContains(device, "Drop the `target:` from the device listed above")
    assertFalse(
      device.contains("Drop `config.target:`"),
      "dropping the trail's target would leave the member override throwing",
    )

    // A report carrying both offers both, because one remedy block covers the whole report.
    val both = TrailTargetLint.renderWarnings(listOf(sessionOnly, deviceOnly))
    assertContains(both, "Drop `config.target:`")
    assertContains(both, "Drop the `target:` from the device listed above")
  }

  @Test
  fun `the remedy snippet uses an id the runtime will accept`() {
    // The flagship mistake this gate exists for is naming an applicationId. `TrailblazeHostAppTarget`
    // throws on any id outside `^[a-zA-Z0-9_-]+$`, so echoing it back would hand the reader a
    // "paste this" trailmap that cannot load.
    val finding = lint(
      """
      config:
        target: xyz.block.trailblaze.examples.sampleapp
      trail:
        - step: Open the app.
      """.trimIndent(),
      trailmapsDir = "trails/config/trailmaps",
    )!!
    val rendered = TrailTargetLint.renderWarnings(listOf(finding))
    assertContains(rendered, "trails/config/trailmaps/sampleapp/trailmap.yaml")
    assertContains(rendered, "id: sampleapp")
    // The applicationId isn't discarded — it belongs under `app_ids:`, which is where it was true.
    assertContains(rendered, "app_ids: [xyz.block.trailblaze.examples.sampleapp]")
    // And the rename is called out, or the reader pastes a trailmap the trail still can't find.
    assertContains(rendered, "update the trail's `target:` to match")
  }

  @Test
  fun `the trailmap the remedy tells you to paste actually loads`() {
    // The remedy embeds YAML, which means it can rot: rename `display_name` or `app_ids` on the
    // manifest schema and the message keeps confidently teaching a file that cannot load. Nothing
    // else would catch that — the other assertions here only check the message CONTAINS a string.
    // So decode the exact snippet through the real loader and read the target back off it.
    val skeleton = TrailTargetLint.trailmapSkeleton("calculator", "com.example.calculator")
    val trailmap = File.createTempFile("trailmap", ".yaml").apply {
      writeText(skeleton)
      deleteOnExit()
    }

    val manifest = TrailblazeTrailmapManifestLoader.load(trailmap).manifest
    assertEquals("calculator", manifest.id)
    val target = manifest.target ?: fail("the skeleton must declare a runnable target, not a library")
    assertEquals("calculator", target.displayName)
    assertEquals(
      listOf("com.example.calculator"),
      target.platforms?.get("android")?.appIds,
      "the app id must land where the runtime launches from",
    )
  }

  private fun manifest(yaml: String): TrailblazeTrailmapManifest =
    TrailblazeTrailmapManifestLoader.load(
      File.createTempFile("trailmap", ".yaml").apply {
        writeText(yaml)
        deleteOnExit()
      },
    ).manifest

  @Test
  fun `an explicit target id REPLACES the manifest id rather than adding to it`() {
    // `toAppTargetYamlConfig` resolves `id ?: defaultId`, so `alpha` registers nothing. Reporting both
    // names would go quiet on the trail that really does degrade at run time.
    assertEquals(
      listOf("beta"),
      TrailTargetLint.manifestTargetNames(
        manifest(
          """
          id: alpha
          target:
            id: beta
            display_name: Beta
          """.trimIndent(),
        ),
      ),
    )
  }

  @Test
  fun `a library manifest registers no target at all`() {
    // No `target:` block means `TrailblazeProjectConfigLoader` emits no target for it — the trailmap
    // exists, but `config.target:` naming it falls back to the workspace default and passes anyway.
    assertEquals(
      emptyList(),
      TrailTargetLint.manifestTargetNames(manifest("id: helpers\n")),
    )
  }

  @Test
  fun `resolvableTargetIds unions the workspace, the classpath and the Kotlin-surfaced targets`() {
    val ids = TrailTargetLint.resolvableTargetIds(
      workspaceTargetIds = setOf("square"),
      classpathTrailmapIds = setOf("web"),
    )
    assertEquals(setOf("square", "web") + TrailTargetLint.KOTLIN_SURFACED_TARGET_IDS, ids)
  }

  @Test
  fun `an id the runtime would reject does not count as registered`() {
    // Declaring a target is not registering one: `TrailblazeHostAppTarget` throws on an id outside
    // `^[a-zA-Z0-9_-]+$` and the loader drops that config, so a `targets/` YAML saying
    // `id: com.example.app` registers nothing. Trusting the declared id would make the gate go quiet
    // on precisely the trail whose target falls back at run time.
    val ids = TrailTargetLint.resolvableTargetIds(
      workspaceTargetIds = setOf("com.example.app", "goodid"),
      classpathTrailmapIds = setOf("also.bad"),
    )
    assertEquals(setOf("goodid") + TrailTargetLint.KOTLIN_SURFACED_TARGET_IDS, ids)
  }

  @Test
  fun `the default target resolves with no trailmap anywhere`() {
    // `default` is surfaced as a Kotlin object, so no manifest discovery can find it — a workspace
    // with zero trailmaps must still accept it.
    assertNull(
      lint(
        yaml = """
        config:
          target: default
        trail:
          - step: Open the app.
        """.trimIndent(),
        registeredTargetIds = TrailTargetLint.resolvableTargetIds(emptySet(), emptySet()),
      ),
    )
  }

  @Test
  fun `the failure message names the trail, the workspace menu and a containment suggestion`() {
    val finding = lint(
      """
      config:
        target: org.wikipedia.alpha
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!
    val rendered = TrailTargetLint.renderFailures(listOf(finding))
    assertContains(rendered, "flows/example.trail.yaml: config.target: org.wikipedia.alpha")
    assertContains(rendered, "workspace ws resolves: contacts, default, wikipedia")
    assertContains(rendered, "did you mean wikipedia?")
  }

  @Test
  fun `the failure message offers no suggestion when nothing resembles the id`() {
    val finding = lint(
      """
      config:
        target: trailrunner
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!
    // A near-miss guess on an id borrowed from another workspace would be worse than none: the
    // author's target exists, just not here, and pointing them at an unrelated app hides that.
    assertContains(TrailTargetLint.renderFailures(listOf(finding)), "config.target: trailrunner\n")
  }

  @Test
  fun `the two severities differ in verdict and framing but carry the same remedy`() {
    val finding = lint(
      """
      config:
        target: calculator
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!
    val fatal = TrailTargetLint.renderFailures(listOf(finding))
    val warning = TrailTargetLint.renderWarnings(listOf(finding))

    assertContains(fatal, "trail-target gate (FATAL)")
    assertContains(fatal, "FAIL flows/example.trail.yaml")
    assertContains(warning, "trail-target check (warning)")
    assertContains(warning, "WARN flows/example.trail.yaml")
    // Only the CLI half promises the build survives; saying so in the corpus gate would be a lie.
    assertContains(warning, "This does not fail `trailblaze check`.")
    assertFalse(fatal.contains("does not fail"))

    // The remedy is the same three options either way — whoever reads this needs to know that
    // registering a target is one option, and dropping `config.target:` is another.
    listOf(fatal, warning).forEach { rendered ->
      assertContains(rendered, "Drop `config.target:` from the trail")
      assertContains(rendered, "id: calculator")
    }
  }

  @Test
  fun `the remedy names the workspace's own trailmaps path when the caller knows it`() {
    val known = lint(
      """
      config:
        target: calculator
      trail:
        - step: Open the app.
      """.trimIndent(),
      trailmapsDir = "myrepo/trailblaze-config/trailmaps",
    )!!
    assertContains(
      TrailTargetLint.renderWarnings(listOf(known)),
      "myrepo/trailblaze-config/trailmaps/calculator/trailmap.yaml",
    )
    // Without one, the path is a placeholder rather than a wrong absolute path presented as fact.
    val unknown = lint(
      """
      config:
        target: calculator
      trail:
        - step: Open the app.
      """.trimIndent(),
    )!!
    assertContains(TrailTargetLint.renderWarnings(listOf(unknown)), "<workspace>/")
  }

  @Test
  fun `each workspace gets a remedy naming its own directory and its own ids`() {
    // The corpus gate spans every workspace in the checkout at once. One remedy for the whole run
    // would tell half the readers to create a trailmap in a directory their trail can't see, and
    // offer them a menu of ids another workspace registers.
    fun finding(workspaceLabel: String, trailmapsDir: String, registeredTargetIds: Set<String>) =
      TrailTargetLint.lint(
        trailRelPath = "$workspaceLabel/example.trail.yaml",
        workspaceLabel = workspaceLabel,
        trail = decode(
          """
          config:
            target: calculator
          trail:
            - step: Open the app.
          """.trimIndent(),
        ),
        registeredTargetIds = registeredTargetIds,
        trailmapsDir = trailmapsDir,
      )!!

    val rendered = TrailTargetLint.renderFailures(
      listOf(
        finding("alpha", "alpha/trails/config/trailmaps", setOf("wikipedia")),
        finding("beta", "beta/trailblaze-config/trailmaps", setOf("contacts")),
      ),
    )
    assertContains(rendered, "alpha/trails/config/trailmaps/calculator/trailmap.yaml")
    assertContains(rendered, "beta/trailblaze-config/trailmaps/calculator/trailmap.yaml")
    // And each remedy sits under the workspace whose ids it belongs to.
    val alphaSection = rendered.substringAfter("workspace alpha").substringBefore("workspace beta")
    assertContains(alphaSection, "alpha/trails/config/trailmaps/calculator/trailmap.yaml")
    assertFalse(
      alphaSection.contains("beta/trailblaze-config"),
      "alpha's remedy must not point at beta's trailmaps directory",
    )
  }

  @Test
  fun `the resolver does no work until a workspace is asked for, then reuses the answer`() {
    val scanned = mutableListOf<File>()
    var classpathDiscoveries = 0
    val resolver = TrailTargetLint.TargetIdResolver(
      workspaceTargetIds = { root -> scanned.add(root); setOf(root.name) },
      classpathTrailmapIds = { classpathDiscoveries++; setOf("trailblaze") },
    )

    // Constructing the resolver must not scan a workspace or walk the classpath — that is what makes
    // the kill-switch skip the gate's work rather than just discarding its findings.
    assertEquals(emptyList(), scanned)
    assertEquals(0, classpathDiscoveries)

    val alpha = File("/tmp/alpha")
    assertEquals(setOf("alpha", "trailblaze", "default"), resolver.forWorkspace(alpha))
    // A workspace owns hundreds of trails, so the second ask must not re-scan its trailmaps dir.
    resolver.forWorkspace(alpha)
    assertEquals(listOf(alpha), scanned)

    resolver.forWorkspace(File("/tmp/beta"))
    assertEquals(listOf(alpha, File("/tmp/beta")), scanned)
    // Classpath manifests are a property of the JVM, not of a workspace: discovered once for all.
    assertEquals(1, classpathDiscoveries)
  }
}
