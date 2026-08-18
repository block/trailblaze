package xyz.block.trailblaze.config

import org.junit.Test
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral coverage for the "this target lives in another repo" messaging.
 *
 * What matters to a reader of these messages — and so what these tests assert — is that a target
 * declared elsewhere produces a repo name and a command that gets them there, and that an
 * installation declaring nothing says nothing extra. The exact sentence wording is not pinned.
 */
class KnownTargetMessagesTest {

  private val appTrails = KnownTargetWorkspace(
    repo = "git@github.com:example-org/app-trails.git",
    url = "https://github.com/example-org/app-trails",
    description = "App and App Lite",
    targets = listOf("myapp", "myappLite"),
  )

  private val otherTrails = KnownTargetWorkspace(
    repo = "git@github.com:example-org/other-trails.git",
    description = "Other App and its siblings",
    targets = listOf("otherapp", "otherapptwo", "otherappthree"),
  )

  private fun resourceSourceOf(contents: Map<String, String>): ConfigResourceSource =
    object : ConfigResourceSource {
      override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
        if (directoryPath == TrailblazeConfigPaths.KNOWN_TARGET_WORKSPACES_DIR) {
          contents.filterKeys { it.endsWith(suffix) }
        } else {
          emptyMap()
        }
    }

  @Test
  fun `hint names the repo and a command that reaches it`() {
    val hint = KnownTargetMessages.unavailableTargetHint(
      targetId = "myapp",
      workspaces = listOf(appTrails, otherTrails),
    )
    requireNotNull(hint)
    assertTrue(hint.contains("example-org/app-trails"), hint)
    assertTrue(hint.contains("git clone git@github.com:example-org/app-trails.git"), hint)
    assertTrue(hint.contains("cd app-trails"), hint)
  }

  @Test
  fun `hint reports the directory the command ran from so the fix is unambiguous`() {
    val hint = KnownTargetMessages.unavailableTargetHint(
      targetId = "myapp",
      workingDirectory = "/Users/someone/scratch",
      workspaces = listOf(appTrails),
    )
    assertTrue(hint!!.contains("/Users/someone/scratch"), hint)
  }

  @Test
  fun `target id resolves case-insensitively like every other target surface`() {
    assertTrue(
      KnownTargetMessages.unavailableTargetHint("MYAPPLITE", workspaces = listOf(appTrails))
        .orEmpty().contains("example-org/app-trails"),
    )
  }

  @Test
  fun `an installation that declares nothing adds nothing`() {
    assertNull(KnownTargetMessages.unavailableTargetHint("myapp", workspaces = emptyList()))
    assertNull(KnownTargetMessages.homeAnnotation("otherapp", workspaces = emptyList()))
    assertEquals(
      emptyList(),
      KnownTargetMessages.notInstalledListing(listOf("default"), workspaces = emptyList()),
    )
  }

  @Test
  fun `an undeclared target adds nothing even when other repos are declared`() {
    assertNull(
      KnownTargetMessages.unavailableTargetHint("typo", workspaces = listOf(appTrails, otherTrails)),
    )
  }

  @Test
  fun `listing covers only the declared targets this installation lacks`() {
    val lines = KnownTargetMessages.notInstalledListing(
      availableTargetIds = listOf("otherapp", "otherapptwo", "otherappthree", "default"),
      workspaces = listOf(appTrails, otherTrails),
    )
    val body = lines.joinToString("\n")
    assertTrue(body.contains("myapp"), body)
    assertTrue(body.contains("myappLite"), body)
    assertTrue(body.contains("example-org/app-trails"), body)
    // other-trails' targets are all installed here, so it must not be advertised as missing.
    assertTrue(!body.contains("other-trails"), body)
  }

  @Test
  fun `a repeated target id is listed once`() {
    val lines = KnownTargetMessages.notInstalledListing(
      availableTargetIds = emptyList(),
      workspaces = listOf(
        KnownTargetWorkspace(
          repo = "git@github.com:example-org/app-trails.git",
          targets = listOf("myapp", "myapp", "MYAPP"),
        ),
      ),
    )
    assertTrue(lines.any { it.contains("targets: myapp") }, lines.joinToString("\n"))
    assertTrue(
      lines.none { it.contains("myapp, myapp") || it.contains("myapp, MYAPP") },
      "a record repeating an id must not render it twice: ${lines.joinToString("\n")}",
    )
  }

  @Test
  fun `the single-line summary carries the repo and clone command without newlines`() {
    // Trail validation normalizes every error to one line, so the hint it appends must already be
    // one line rather than get truncated mid-sentence.
    val summary = KnownTargetMessages.unavailableTargetSummary("myapp", workspaces = listOf(appTrails))
    requireNotNull(summary)
    assertTrue(!summary.contains("\n"), summary)
    assertTrue(summary.contains("example-org/app-trails"), summary)
    assertTrue(summary.contains("git clone git@github.com:example-org/app-trails.git"), summary)
    assertNull(KnownTargetMessages.unavailableTargetSummary("typo", workspaces = listOf(appTrails)))
  }

  @Test
  fun `listing is empty when every declared target is installed`() {
    assertEquals(
      emptyList(),
      KnownTargetMessages.notInstalledListing(
        availableTargetIds = listOf("myapp", "myappLite"),
        workspaces = listOf(appTrails),
      ),
    )
  }

  @Test
  fun `an installed target still reports the repo its trails live in`() {
    assertEquals(
      "trails in example-org/other-trails",
      KnownTargetMessages.homeAnnotation("otherapp", workspaces = listOf(otherTrails)),
    )
  }

  @Test
  fun `records load from the known-target-workspaces resource directory`() {
    val source = resourceSourceOf(
      mapOf(
        "app-trails.yaml" to """
          repo: git@github.com:example-org/app-trails.git
          url: https://github.com/example-org/app-trails
          description: App and App Lite
          targets:
            - myapp
            - myappLite
        """.trimIndent(),
      ),
    )
    val loaded = KnownTargetWorkspaceLoader.discover(source)
    assertEquals(1, loaded.size)
    assertEquals("example-org/app-trails", loaded.single().shortName)
    assertEquals(listOf("myapp", "myappLite"), loaded.single().targets)
    assertEquals("app-trails", loaded.single().directoryName)
  }

  @Test
  fun `one file can inline several records under a workspaces list`() {
    val source = resourceSourceOf(
      mapOf(
        "all-repos.yaml" to """
          workspaces:
            - repo: git@github.com:example-org/app-trails.git
              description: App and App Lite
              targets:
                - myapp
                - myappLite
            - repo: git@github.com:example-org/other-trails.git
              targets:
                - otherapp
        """.trimIndent(),
      ),
    )
    val loaded = KnownTargetWorkspaceLoader.discover(source)
    assertEquals(listOf("example-org/app-trails", "example-org/other-trails"), loaded.map { it.shortName })
    assertEquals(listOf("myapp", "myappLite"), loaded.first().targets)
    assertTrue(
      KnownTargetMessages.unavailableTargetHint("otherapp", workspaces = loaded)
        .orEmpty().contains("example-org/other-trails"),
    )
  }

  @Test
  fun `list-shaped and bare-record files merge across the directory`() {
    // Distinct filenames are what makes contributions merge rather than shadow, so a workspace using
    // one shape must not erase a bundled file using the other.
    val source = resourceSourceOf(
      mapOf(
        "bundled.yaml" to """
          workspaces:
            - repo: git@github.com:example-org/app-trails.git
              targets: [myapp]
        """.trimIndent(),
        "workspace-local.yaml" to """
          repo: git@github.com:example-org/other-trails.git
          targets:
            - otherapp
        """.trimIndent(),
      ),
    )
    val loaded = KnownTargetWorkspaceLoader.discover(source)
    assertEquals(2, loaded.size, "both files must contribute: $loaded")
    assertEquals(listOf("myapp", "otherapp"), loaded.flatMap { it.targets }.sorted())
  }

  @Test
  fun `a record with no repo is dropped rather than rendering a blank pointer`() {
    val source = resourceSourceOf(
      mapOf(
        "broken.yaml" to """
          repo: ""
          targets:
            - myapp
        """.trimIndent(),
      ),
    )
    assertEquals(emptyList(), KnownTargetWorkspaceLoader.discover(source))
  }

  @Test
  fun `https clone urls resolve to the same short name as ssh`() {
    assertEquals(
      "example-org/app-trails",
      KnownTargetWorkspace(repo = "https://github.com/example-org/app-trails.git").shortName,
    )
  }

  @Test
  fun `a nested group path is kept whole rather than truncated to two segments`() {
    // Truncating to `group/repo` would print an org that doesn't own the repo — a pointer that looks
    // authoritative and leads nowhere.
    val nested = KnownTargetWorkspace(repo = "git@gitlab.example.com:org/group/app-trails.git")
    assertEquals("org/group/app-trails", nested.shortName)
    assertEquals("app-trails", nested.directoryName)
  }

  @Test
  fun `a host segment is dropped but a bare org slash repo is preserved`() {
    assertEquals(
      "example-org/app-trails",
      KnownTargetWorkspace(repo = "https://github.com/example-org/app-trails").shortName,
    )
    assertEquals(
      "example-org/app-trails",
      KnownTargetWorkspace(repo = "example-org/app-trails").shortName,
    )
  }

  @Test
  fun `a trailing slash does not leak into the clone directory`() {
    assertEquals(
      "app-trails",
      KnownTargetWorkspace(repo = "https://github.com/example-org/app-trails/").directoryName,
    )
  }

  @Test
  fun `a degenerate repo is dropped rather than rendering an empty pointer`() {
    // `/` and `git@host:` are non-blank, so a blank-check alone would keep them and render
    // "lives in " followed by nothing.
    val source = resourceSourceOf(
      mapOf(
        "slash.yaml" to "repo: \"/\"\ntargets:\n  - myapp\n",
        "hostonly.yaml" to "repo: git@github.com:\ntargets:\n  - myapp\n",
      ),
    )
    assertEquals(emptyList(), KnownTargetWorkspaceLoader.discover(source))
  }

  @Test
  fun `a discovery failure degrades to no records instead of throwing`() {
    // Callers build these hints inside error paths — one appends to an exception message being
    // thrown — so a throw here would replace a precise "unknown target" failure with an unrelated
    // one.
    val exploding = object : ConfigResourceSource {
      override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
        error("classpath scan blew up")
    }
    assertEquals(emptyList(), KnownTargetWorkspaceLoader.discover(exploding))
    assertNull(KnownTargetMessages.unavailableTargetHint("myapp", workspaces = KnownTargetWorkspaceLoader.discover(exploding)))
  }

  @Test
  fun `a record whose targets key is misspelled loads as declaring nothing`() {
    // `strictMode = false` accepts the typo; the record then points nobody anywhere, so the loader
    // keeps it (the repo is usable) but it must not claim any target.
    val source = resourceSourceOf(
      mapOf("typo.yaml" to "repo: git@github.com:example-org/app-trails.git\ntarget:\n  - myapp\n"),
    )
    val loaded = KnownTargetWorkspaceLoader.discover(source)
    assertEquals(emptyList(), loaded.single().targets)
    assertNull(KnownTargetMessages.unavailableTargetHint("myapp", workspaces = loaded))
  }
}
