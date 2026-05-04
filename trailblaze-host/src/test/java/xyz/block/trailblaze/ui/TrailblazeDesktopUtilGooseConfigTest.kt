package xyz.block.trailblaze.ui

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.bundle.yaml.YamlEmitter
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil.GooseExtensionResult
import xyz.block.trailblaze.ui.goose.TrailblazeGooseExtension

/**
 * Tests for [TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn] — the YAML
 * round-trip path that splices the Trailblaze extension entry into a user-owned Goose
 * config file. Previously untested; the kaml migration replaced a one-line SnakeYAML
 * round-trip with a tree-API-plus-shared-emitter approach, so a baseline regression
 * surface is needed.
 *
 * The reusable scenarios live here (rather than in `:trailblaze-pack-bundler`'s emitter
 * test) because they exercise the *integration* — read existing config, splice typed
 * entry, write back — not just the emitter primitives.
 */
class TrailblazeDesktopUtilGooseConfigTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

  @Test
  fun `returns ConfigNotFound when the file does not exist`() {
    val missing = File(tempFolder.root, "missing.yaml")
    val result = TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(missing)
    assertEquals(GooseExtensionResult.ConfigNotFound, result)
  }

  @Test
  fun `adds the trailblaze extension to a config that already has other extensions`() {
    val configFile = tempFolder.newFile("config.yaml").apply {
      writeText(
        """
        |GOOSE_PROVIDER: anthropic
        |GOOSE_MODEL: claude-3-5-sonnet
        |extensions:
        |  developer:
        |    enabled: true
        |    type: builtin
        |    name: developer
        |
        """.trimMargin(),
      )
    }

    val result = TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(configFile)
    assertEquals(GooseExtensionResult.Added, result)

    val rewritten = parseConfig(configFile)
    @Suppress("UNCHECKED_CAST")
    val extensions = rewritten["extensions"] as Map<String, Any?>
    // Existing extension preserved.
    assertNotNull(extensions["developer"], "existing 'developer' extension must be preserved")
    // Trailblaze extension added with the expected fields.
    @Suppress("UNCHECKED_CAST")
    val trailblaze = extensions["trailblaze"] as Map<String, Any?>
    assertEquals(TrailblazeGooseExtension.type, trailblaze["type"])
    assertEquals(TrailblazeGooseExtension.uri, trailblaze["uri"])
    assertEquals(TrailblazeGooseExtension.name, trailblaze["name"])
    // Top-level non-extension fields preserved.
    assertEquals("anthropic", rewritten["GOOSE_PROVIDER"])
    assertEquals("claude-3-5-sonnet", rewritten["GOOSE_MODEL"])
  }

  @Test
  fun `is idempotent when the trailblaze extension is already installed`() {
    val configFile = tempFolder.newFile("config.yaml").apply {
      writeText(
        """
        |extensions:
        |  trailblaze:
        |    enabled: ${TrailblazeGooseExtension.enabled}
        |    type: ${TrailblazeGooseExtension.type}
        |    name: ${TrailblazeGooseExtension.name}
        |    uri: ${TrailblazeGooseExtension.uri}
        |
        """.trimMargin(),
      )
    }

    val before = configFile.readText()
    val result = TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(configFile)
    val after = configFile.readText()

    assertEquals(GooseExtensionResult.AlreadyInstalled, result)
    assertEquals(before, after, "AlreadyInstalled must not rewrite the file")
  }

  @Test
  fun `creates the extensions map when missing from an empty-but-non-zero config`() {
    val configFile = tempFolder.newFile("config.yaml").apply {
      writeText(
        """
        |GOOSE_PROVIDER: anthropic
        |
        """.trimMargin(),
      )
    }

    val result = TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(configFile)
    assertEquals(GooseExtensionResult.Added, result)

    val rewritten = parseConfig(configFile)
    @Suppress("UNCHECKED_CAST")
    val extensions = rewritten["extensions"] as Map<String, Any?>
    assertNotNull(extensions["trailblaze"])
    assertEquals("anthropic", rewritten["GOOSE_PROVIDER"])
  }

  @Test
  fun `boolean enabled field round-trips as unquoted YAML 1_2 boolean`() {
    val configFile = tempFolder.newFile("config.yaml").apply {
      writeText(
        """
        |extensions:
        |  developer:
        |    enabled: true
        |
        """.trimMargin(),
      )
    }
    TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(configFile)
    val text = configFile.readText()

    // The pre-existing `enabled: true` must remain unquoted after round-trip — kaml's
    // tree API stringifies plain scalars, but YamlEmitter.resolveYamlScalar() converts
    // canonical YAML 1.2 booleans back to Boolean(true), and formatScalar() emits
    // unquoted. This was the load-bearing behavior verified by the bundled-config
    // verification task; testing it directly here too.
    assertTrue("expected unquoted 'enabled: true' in: $text") {
      text.contains("enabled: true") && !text.contains("enabled: 'true'")
    }
  }

  @Test
  fun `nested list-of-maps three levels deep round-trips faithfully`() {
    // Defensive: the L1 fix in the emitter's appendList path mattered for deeply-nested
    // structures. Goose configs don't currently exercise this (extensions are
    // map-of-maps, no lists), but a future Goose feature might. Cover it now so the
    // emitter doesn't regress silently.
    val configFile = tempFolder.newFile("config.yaml").apply {
      writeText(
        """
        |extensions:
        |  developer:
        |    enabled: true
        |custom_groups:
        |  - name: alpha
        |    members:
        |      - role: lead
        |        slug: x
        |      - role: dev
        |        slug: y
        |
        """.trimMargin(),
      )
    }
    TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(configFile)

    val rewritten = parseConfig(configFile)
    @Suppress("UNCHECKED_CAST")
    val groups = rewritten["custom_groups"] as List<Map<String, Any?>>
    val first = groups.single()
    assertEquals("alpha", first["name"])
    @Suppress("UNCHECKED_CAST")
    val members = first["members"] as List<Map<String, Any?>>
    assertEquals(2, members.size)
    assertEquals("lead", members[0]["role"])
    assertEquals("x", members[0]["slug"])
    assertEquals("dev", members[1]["role"])
    assertEquals("y", members[1]["slug"])
  }

  @Test
  fun `returns Error when the config root is not a YAML map`() {
    // An author who's mangled their config such that the root isn't a map (e.g.,
    // accidentally truncated to a list) should get a directed Error result, not a
    // crash. This is the fail-soft path — Trailblaze can't write back, but it doesn't
    // throw out of the function.
    val configFile = tempFolder.newFile("config.yaml").apply {
      writeText("- this\n- isnt\n- a map\n")
    }
    val result = TrailblazeDesktopUtil.ensureTrailblazeExtensionInstalledIn(configFile)
    assertTrue("got: $result") { result is GooseExtensionResult.Error }
  }

  /**
   * Re-parse the config file and return its content as a Kotlin tree (via the same
   * shared `YamlEmitter.yamlMapToMutable` used in production), for assertion convenience.
   */
  private fun parseConfig(file: File): Map<String, Any?> {
    val node = yaml.parseToYamlNode(file.readText())
    return YamlEmitter.yamlMapToMutable(node as YamlMap)
  }
}
