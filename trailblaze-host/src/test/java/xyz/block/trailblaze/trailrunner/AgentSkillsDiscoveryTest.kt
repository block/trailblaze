package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [discoverAgentSkills] mirrors the child CLI's skill lookup, so the Skills panel shows what the
 * agent can actually reach: `.claude/skills` from the trails root up to the project boundary
 * (`.git`), each skill named and described from its SKILL.md frontmatter.
 */
class AgentSkillsDiscoveryTest {
  // Everything a test writes (including the "above the boundary" fixture) lives inside its own
  // temp root - never the shared system temp dir - and is removed even when an assertion fails.
  private fun withTempRoot(block: (root: File) -> Unit) {
    val root = createTempDirectory("tb-skills").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun writeSkill(root: File, name: String, frontmatter: String) {
    val dir = File(root, ".claude/skills/$name").apply { mkdirs() }
    File(dir, "SKILL.md").writeText(frontmatter)
  }

  @Test
  fun findsWorkspaceSkillsUpToTheProjectBoundary() = withTempRoot { root ->
    val repo = File(root, "repo").apply { mkdirs() }
    File(repo, ".git").mkdirs()
    writeSkill(repo, "compose-notes", "---\nname: compose-notes\ndescription: Notes for composing.\n---\nbody")
    val trails = File(repo, "trails").apply { mkdirs() }
    // A skills dir ABOVE the .git boundary must not leak in.
    writeSkill(root, "outside", "---\nname: outside\ndescription: should not appear\n---\n")

    val skills = discoverAgentSkills(trails)
    val workspace = skills.filter { it.scope == "workspace" }

    assertTrue(workspace.any { it.name == "compose-notes" && it.description == "Notes for composing." })
    assertTrue(workspace.none { it.name == "outside" })
  }

  @Test
  fun parsesBlockScalarDescriptionsAndFallsBackToTheDirName() = withTempRoot { root ->
    val repo = File(root, "repo").apply { mkdirs() }
    File(repo, ".git").mkdirs()
    writeSkill(
      repo,
      "multi-line",
      "---\nname: multi-line\ndescription: |\n  Line one of the description.\n  Line two continues it.\n---\nbody",
    )
    writeSkill(repo, "no-frontmatter", "just a body, no frontmatter block")

    val byName = discoverAgentSkills(repo).filter { it.scope == "workspace" }.associateBy { it.name }

    assertEquals("Line one of the description. Line two continues it.", byName.getValue("multi-line").description)
    // Malformed frontmatter degrades to the directory name, never to a missing entry.
    assertTrue("no-frontmatter" in byName)
    assertEquals(null, byName.getValue("no-frontmatter").description)
  }
}
