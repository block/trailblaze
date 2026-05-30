package xyz.block.trailblaze.scripting.subprocess

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasClass
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import java.io.File
import kotlin.test.Test

class BunRuntimeTest {

  private val bunPath = File("/opt/homebrew/bin/bun")

  @Test fun `detect resolves bun when present`() {
    val runtime = BunRuntimeDetector.detect { name ->
      if (name == "bun") bunPath else null
    }
    assertThat(runtime).isEqualTo(BunRuntime(bunPath))
  }

  @Test fun `detect falls through to bun dot exe on Windows-style PATH`() {
    // Mirrors ScriptedToolDefinitionAnalyzer.resolveBunBinary's dual-name probe so a Windows
    // checkout that ships bun as `bun.exe` resolves the same way both bun-locating sites do.
    val bunExePath = File("C:/bun/bun.exe")
    val runtime = BunRuntimeDetector.detect { name ->
      if (name == "bun.exe") bunExePath else null
    }
    assertThat(runtime).isEqualTo(BunRuntime(bunExePath))
  }

  @Test fun `detect prefers bun over bun dot exe when both are present`() {
    // Pins the probe order: on a misconfigured machine that has both, the POSIX-style name
    // wins (matches the order in BUN_BINARY_NAMES).
    val bunExePath = File("/opt/homebrew/bin/bun.exe")
    val runtime = BunRuntimeDetector.detect { name ->
      when (name) {
        "bun" -> bunPath
        "bun.exe" -> bunExePath
        else -> null
      }
    }
    assertThat(runtime).isEqualTo(BunRuntime(bunPath))
  }

  @Test fun `detect does not fall back to other runtimes`() {
    // Trailblaze is bun-only — having tsx (or anything else) on PATH must not satisfy
    // detection. Asserts the contract from the bun-only refactor.
    assertFailure {
      BunRuntimeDetector.detect { name ->
        if (name == "tsx") File("/usr/local/bin/tsx") else null
      }
    }.hasClass(NoBunRuntimeException::class)
  }

  @Test fun `detect errors when bun is missing with install hint`() {
    assertFailure {
      BunRuntimeDetector.detect { null }
    }.all {
      hasClass(NoBunRuntimeException::class)
      messageContains("bun")
      messageContains("https://bun.sh")
    }
  }

  @Test fun `argv runs script via bun run`() {
    val script = File("/tmp/fixture.ts")
    val argv = BunRuntime(bunPath).argv(script)
    assertThat(argv).isEqualTo(listOf(bunPath.absolutePath, "run", script.absolutePath))
  }
}
