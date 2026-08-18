package xyz.block.trailblaze.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitRepoUrlsTest {

  @Test
  fun parsesEveryFormAGitRemoteActuallyTakes() {
    val expected = GitRepoUrls.Parsed(host = "github.com", slug = "example/mobile-app")
    listOf(
      "git@github.com:example/mobile-app.git",
      "git@github.com:example/mobile-app",
      "https://github.com/example/mobile-app.git",
      "https://github.com/example/mobile-app",
      "https://github.com/example/mobile-app/",
      "ssh://git@github.com/example/mobile-app.git",
      "ssh://git@github.com:22/example/mobile-app.git",
      "  https://github.com/example/mobile-app.git  ",
    ).forEach { cloneUrl ->
      assertEquals(expected, GitRepoUrls.parse(cloneUrl), "failed to parse: $cloneUrl")
    }
  }

  @Test
  fun hostIsComparedInLowercaseSoCaseDoesNotDecideWhetherALinkExists() {
    assertEquals(
      "https://github.com/example/mobile-app",
      GitRepoUrls.webBaseUrl("https://GitHub.com/example/mobile-app.git"),
    )
  }

  @Test
  fun credentialsInTheUrlNeverReachTheOutput() {
    assertEquals(
      "https://github.com/example/mobile-app",
      GitRepoUrls.webBaseUrl("https://x-access-token:tokenvalue@github.com/example/mobile-app.git"),
    )
  }

  @Test
  fun aTokenContainingASlashFailsToParseRatherThanLeavingATail() {
    // Defeats the userinfo strip, which can't cross a `/`; the slug check is the backstop.
    assertNull(GitRepoUrls.parse("https://user:se/cret@github.com/mobile-app.git"))
  }

  @Test
  fun anythingThatIsNotExactlyHostOwnerRepoIsNull() {
    listOf(
      null,
      "",
      "   ",
      "github.com",
      "example/mobile-app",
      // A browse URL: its last two segments would name `tree/main` as the repo.
      "https://github.com/example/mobile-app/tree/main",
    ).forEach { assertNull(GitRepoUrls.parse(it), "expected null for: $it") }
  }

  @Test
  fun nonGitHubForgesParseButGetNoUrlBecauseTheirPathShapesDiffer() {
    assertEquals(
      GitRepoUrls.Parsed(host = "gitlab.example.com", slug = "team/trails"),
      GitRepoUrls.parse("git@gitlab.example.com:team/trails.git"),
    )
    assertNull(GitRepoUrls.webBaseUrl("git@gitlab.example.com:team/trails.git"))
  }

  @Test
  fun gitHubEnterpriseHostsGetLinksToo() {
    // The prefix matching this replaces compared against github.com literally, so GHE got no link.
    assertEquals(
      "https://github.example.com/example/mobile-app",
      GitRepoUrls.webBaseUrl("git@github.example.com:example/mobile-app.git"),
    )
  }
}
