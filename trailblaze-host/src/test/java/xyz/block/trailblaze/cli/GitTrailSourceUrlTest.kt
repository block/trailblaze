package xyz.block.trailblaze.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitTrailSourceUrlTest {
  @Test
  fun committedSourceUsesItsOwnRepositoryAndRevisionAndRejectsEdits() {
    val root = Files.createTempDirectory("trail-source").toFile()
    fun git(vararg args: String): String {
      val process = ProcessBuilder("git", "-C", root.path, *args).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      check(process.waitFor() == 0) { output }
      return output.trim()
    }
    try {
      git("init")
      git("remote", "add", "origin", "git@github.com:example/trails.git")
      val file = root.resolve("cases/Case #1/trail.yaml")
      file.parentFile.mkdirs()
      val yaml = "trail: []\n"
      file.writeText(yaml)
      git("add", ".")
      git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture")
      val sha = git("rev-parse", "HEAD")
      val env = mapOf("TRAIL_FILES" to "cases/Case #1/trail.yaml", "TRAIL_SOURCE_REPO" to "https://github.com/example/trails.git", "TRAIL_SOURCE_REF" to sha)
      assertEquals("https://github.com/example/trails/blob/$sha/cases/Case%20%231/trail.yaml", GitTrailSourceUrl.capture(file, yaml, env::get))
      file.writeText("trail: [edited]\n")
      assertNull(GitTrailSourceUrl.capture(file, file.readText(), env::get))
      assertNull(GitTrailSourceUrl.capture(file, yaml, (env + ("TRAIL_SOURCE_REPO" to "https://github.com/example/runner.git"))::get))
      assertNull(GitTrailSourceUrl.capture(file, yaml, (env + ("TRAIL_SOURCE_REF" to "b".repeat(40)))::get))
      assertNull(GitTrailSourceUrl.capture(file, yaml) { null })
      val untracked = root.resolve("untracked.trail.yaml").apply { writeText(yaml) }
      assertNull(GitTrailSourceUrl.capture(untracked, yaml, env::get))
    } finally {
      root.deleteRecursively()
    }
  }
}
