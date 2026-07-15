package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AgentSkillMaterializer] guarantees a conversation's child CLI can discover the trailblaze
 * authoring skill: absent from the workspace, the bundled copy (a JAR-resource zip) is unpacked
 * into `<cwd>/.claude/skills/trailblaze/`; already present, the workspace's own copy is left
 * untouched.
 */
class AgentSkillMaterializerTest {
  private fun withTempRoot(block: (root: File) -> Unit) {
    val root = createTempDirectory("tb-skill-materializer").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun materializesTheBundledSkillIntoAWorkspaceWithoutOne() = withTempRoot { root ->
    val ws = File(root, "workspace").apply { mkdirs() }
    File(ws, ".git").mkdirs()

    AgentSkillMaterializer.ensureTrailblazeSkill(ws)

    val skillMd = File(ws, ".claude/skills/trailblaze/SKILL.md")
    assertTrue(skillMd.isFile, "SKILL.md should be unpacked")
    assertTrue(skillMd.readText().contains("name: trailblaze"))
    assertTrue(File(ws, ".claude/skills/trailblaze/references").isDirectory, "references should be unpacked")
    // The unpacked copy must be what discovery (and therefore the child CLI) actually finds.
    assertTrue(discoverAgentSkills(ws).any { it.name == "trailblaze" && it.scope == "workspace" })
  }

  @Test
  fun materializesTheAuthoringSkillAlongsideTheTrailblazeSkill() = withTempRoot { root ->
    val ws = File(root, "workspace").apply { mkdirs() }
    File(ws, ".git").mkdirs()

    AgentSkillMaterializer.ensureTrailblazeSkill(ws)

    // Both bundled skills land, so the demonstrate-first generation agent finds trailblaze-author.
    val authorMd = File(ws, ".claude/skills/trailblaze-author/SKILL.md")
    assertTrue(authorMd.isFile, "trailblaze-author SKILL.md should be unpacked")
    assertTrue(authorMd.readText().contains("name: trailblaze-author"))
    val names = discoverAgentSkills(ws).map { it.name }.toSet()
    assertTrue(names.contains("trailblaze"))
    assertTrue(names.contains("trailblaze-author"))
  }

  @Test
  fun leavesAnExistingWorkspaceSkillUntouched() = withTempRoot { root ->
    val ws = File(root, "workspace").apply { mkdirs() }
    File(ws, ".git").mkdirs()
    val dir = File(ws, ".claude/skills/trailblaze").apply { mkdirs() }
    val sentinel = "---\nname: trailblaze\ndescription: workspace-local edition\n---\ncustom body"
    File(dir, "SKILL.md").writeText(sentinel)

    AgentSkillMaterializer.ensureTrailblazeSkill(ws)

    assertEquals(sentinel, File(dir, "SKILL.md").readText(), "an existing workspace skill wins over the bundled one")
  }
}
