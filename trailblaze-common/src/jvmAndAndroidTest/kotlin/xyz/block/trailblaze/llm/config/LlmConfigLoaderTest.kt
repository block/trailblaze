package xyz.block.trailblaze.llm.config

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmConfigLoaderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `load returns empty config when no files exist`() {
    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root,
      projectDir = tempFolder.root,
    )
    assertTrue(config.providers.isEmpty())
    assertNull(config.defaults.model)
  }

  @Test
  fun `load reads user-level config`() {
    val trailblazeDir = File(tempFolder.root, ".trailblaze").also { it.mkdirs() }
    File(trailblazeDir, "trailblaze.yaml").writeText(
      """
      llm:
        providers:
          openai:
            models:
              - id: gpt-4.1
        defaults:
          model: openai/gpt-4.1
      """.trimIndent(),
    )

    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root,
      projectDir = null,
    )

    assertEquals(1, config.providers.size)
    assertEquals("gpt-4.1", config.providers["openai"]!!.models.single().id)
    assertEquals("openai/gpt-4.1", config.defaults.model)
  }

  @Test
  fun `load reads project-level config`() {
    val projectConfigFile = File(tempFolder.root, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    projectConfigFile.writeText(
      """
      llm:
        providers:
          anthropic:
            models:
              - id: claude-sonnet-4-6
        defaults:
          model: anthropic/claude-sonnet-4-6
      """.trimIndent(),
    )

    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root, // no user config here
      projectDir = tempFolder.root,
    )

    assertEquals(1, config.providers.size)
    assertEquals("claude-sonnet-4-6", config.providers["anthropic"]!!.models.single().id)
    assertEquals("anthropic/claude-sonnet-4-6", config.defaults.model)
  }

  @Test
  fun `load walks up from projectDir to find trailblaze_yaml in an ancestor`() {
    // projectDir no longer needs to be the exact workspace root — findWorkspaceRoot walks
    // up looking for trails/config/trailblaze.yaml. Regression guard for anyone running `trailblaze`
    // from a nested subdirectory of their project.
    val projectConfigFile = File(tempFolder.root, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    projectConfigFile.writeText(
      """
      llm:
        providers:
          openai:
            models:
              - id: gpt-4.1
        defaults:
          model: openai/gpt-4.1
      """.trimIndent(),
    )
    val nested = File(tempFolder.root, "nested/deep").apply { mkdirs() }

    val config = LlmConfigLoader.load(
      userHomeDir = File(tempFolder.root, "no-user-config"),
      projectDir = nested,
    )

    assertEquals("openai/gpt-4.1", config.defaults.model)
  }

  @Test
  fun `project-level config overrides user-level`() {
    // User-level config
    val trailblazeDir = File(tempFolder.root, ".trailblaze").also { it.mkdirs() }
    File(trailblazeDir, "trailblaze.yaml").writeText(
      """
      llm:
        providers:
          openai:
            models:
              - id: gpt-4.1
        defaults:
          model: openai/gpt-4.1
      """.trimIndent(),
    )

    // Project-level config overrides the default model
    val projectConfigFile = File(tempFolder.root, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    projectConfigFile.writeText(
      """
      llm:
        defaults:
          model: openai/gpt-4.1-mini
      """.trimIndent(),
    )

    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root,
      projectDir = tempFolder.root,
    )

    // Provider from user-level is preserved
    assertEquals("gpt-4.1", config.providers["openai"]!!.models.single().id)
    // Default overridden by project-level
    assertEquals("openai/gpt-4.1-mini", config.defaults.model)
  }

  @Test
  fun `malformed user config is gracefully ignored`() {
    val trailblazeDir = File(tempFolder.root, ".trailblaze").also { it.mkdirs() }
    File(trailblazeDir, "trailblaze.yaml").writeText("invalid: [yaml: {{broken")

    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root,
      projectDir = null,
    )

    assertTrue(config.providers.isEmpty())
  }

  @Test
  fun `project config without llm key is ignored`() {
    val projectConfigFile = File(tempFolder.root, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    projectConfigFile.writeText(
      """
      target: my_app
      """.trimIndent(),
    )

    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root,
      projectDir = tempFolder.root,
    )

    assertTrue(config.providers.isEmpty())
  }

  @Test
  fun `blank config files are ignored`() {
    val trailblazeDir = File(tempFolder.root, ".trailblaze").also { it.mkdirs() }
    File(trailblazeDir, "trailblaze.yaml").writeText("")
    val projectConfigFile = File(tempFolder.root, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    projectConfigFile.writeText("")

    val config = LlmConfigLoader.load(
      userHomeDir = tempFolder.root,
      projectDir = tempFolder.root,
    )

    assertTrue(config.providers.isEmpty())
  }

  @Test
  fun `validate warns when default model not in any provider`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
      defaults = LlmDefaultsConfig(model = "openai/gpt-5-nonexistent"),
    )

    val warnings = LlmConfigLoader.validate(config)
    assertEquals(1, warnings.size)
    assertTrue(warnings.first().contains("gpt-5-nonexistent"))
    assertTrue(warnings.first().contains("not found"))
  }

  @Test
  fun `validate warns when provider has no models`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom_provider" to LlmProviderConfig(models = emptyList()),
      ),
    )

    val warnings = LlmConfigLoader.validate(config)
    assertEquals(1, warnings.size)
    assertTrue(warnings.first().contains("custom_provider"))
    assertTrue(warnings.first().contains("no models"))
  }

  @Test
  fun `validate returns no warnings for valid config`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
      defaults = LlmDefaultsConfig(model = "openai/gpt-4.1"),
    )

    val warnings = LlmConfigLoader.validate(config)
    assertTrue(warnings.isEmpty())
  }
}
