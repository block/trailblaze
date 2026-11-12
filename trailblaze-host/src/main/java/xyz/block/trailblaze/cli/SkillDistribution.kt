package xyz.block.trailblaze.cli

import java.nio.file.Path

/**
 * A skill manager that installs and updates the agent skill in place of `trailblaze skill install`
 * writing the copy bundled in this JAR. A distribution whose users already get skills from their own
 * manager registers one through [SkillDistributions.register] before `TrailblazeCli.run`; the
 * open-source build registers none, so `skill install` writes the bundled copy.
 */
interface SkillDistribution {
  /** Name of the manager, for output (e.g. `acme skills`). */
  val managerName: String

  /** Command that refreshes a copy this manager owns, printed by `skill status`. */
  val updateCommand: String

  /**
   * Install the skill for a `skill install` that names no `--dir`: into the project at [projectDir],
   * or under [homeDir] when [global]. Returns the exit code when the manager handled the install, or
   * null when it can't run here (not on PATH, or it failed), so the bundled copy is written instead.
   */
  fun install(global: Boolean, projectDir: Path, homeDir: Path): Int?

  /**
   * True when [path] — a directory or a file, existing or not — is, or sits inside, a copy this
   * manager owns, checked through symlinks. `skill install` asks this for every path it would write
   * and writes nothing into a destination where any answer is true: the manager would see its copy
   * changed under it.
   */
  fun owns(path: Path): Boolean

  /** Commit of this repository that the managed copy at [dir] was published from, or null if unknown. */
  fun sourceCommitOf(dir: Path): String?
}

/** Holds the registered [SkillDistribution], if any. */
object SkillDistributions {
  @Volatile var current: SkillDistribution? = null
    private set

  fun register(distribution: SkillDistribution?) {
    current = distribution
  }
}
