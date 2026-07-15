package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Guarantees the child CLI an external-agent conversation spawns can discover the Trailblaze
 * authoring skills from its working directory. Composition sessions lean on `trailblaze` (trail
 * syntax, selector rules, authoring workflow); the demonstrate-first Create generation agent leans
 * on `trailblaze-author` (turning a captured demonstration bundle into a verified trail). Skill
 * discovery is filesystem-based - a workspace with neither skill anywhere in its ancestry would
 * leave the agent without it. Each skill ships as a zip inside this module's JAR (see
 * `zipTrailblazeSkill` / `zipTrailblazeAuthorSkill` in build.gradle.kts) and is unpacked on demand
 * into `<cwd>/.claude/skills/<name>/`.
 */
internal object AgentSkillMaterializer {
  private data class BundledSkill(val name: String, val resource: String)

  private val BUNDLED_SKILLS = listOf(
    BundledSkill("trailblaze", "/xyz/block/trailblaze/trailrunner/trailblaze-skill.zip"),
    BundledSkill("trailblaze-author", "/xyz/block/trailblaze/trailrunner/trailblaze-author-skill.zip"),
  )

  /**
   * Materializes each bundled skill into [cwd]'s `.claude/skills/` unless a skill of that name is
   * already discoverable from [cwd] (same walk-up the CLI itself does, plus `~/.claude/skills`) - a
   * workspace or user copy always wins over the bundled one. Failures are logged and swallowed per
   * skill: a missing skill degrades the session, it must not block it, and one skill's failure must
   * not skip the others.
   */
  fun ensureTrailblazeSkill(cwd: File) {
    val discovered = runCatching { discoverAgentSkills(cwd).map { it.name }.toSet() }.getOrDefault(emptySet())
    BUNDLED_SKILLS.forEach { skill -> materialize(cwd, skill, discovered) }
  }

  private fun materialize(cwd: File, skill: BundledSkill, discovered: Set<String>) {
    runCatching {
      if (skill.name in discovered) return
      val resource = AgentSkillMaterializer::class.java.getResourceAsStream(skill.resource)
      if (resource == null) {
        Console.log("[AgentSkillMaterializer] bundled skill resource missing from classpath: ${skill.name}; skipping")
        return
      }
      val target = File(cwd, ".claude/skills/${skill.name}").canonicalFile
      target.mkdirs()
      ZipInputStream(resource.buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          val out = File(target, entry.name).canonicalFile
          require(out == target || out.path.startsWith(target.path + File.separator)) {
            "zip entry escapes the skill directory: ${entry.name}"
          }
          if (entry.isDirectory) {
            out.mkdirs()
          } else {
            out.parentFile?.mkdirs()
            out.outputStream().use { zip.copyTo(it) }
          }
          entry = zip.nextEntry
        }
      }
      Console.log("[AgentSkillMaterializer] materialized the bundled ${skill.name} skill into $target")
    }.onFailure { e ->
      Console.log("[AgentSkillMaterializer] could not materialize the ${skill.name} skill: ${e.message}")
    }
  }
}
