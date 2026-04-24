package xyz.block.trailblaze.scripting.subprocess

import assertk.all
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.hasClass
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import java.io.File
import kotlin.test.Test

class NodeRuntimeTest {

  private val bunPath = File("/opt/homebrew/bin/bun")
  private val tsxPath = File("/usr/local/bin/tsx")

  @Test fun `detect prefers bun when both present`() {
    val runtime = NodeRuntimeDetector.detect { name ->
      when (name) {
        "bun" -> bunPath
        "tsx" -> tsxPath
        else -> null
      }
    }
    assertThat(runtime).isEqualTo(NodeRuntime.Bun(bunPath))
  }

  @Test fun `detect falls back to tsx when bun missing`() {
    val runtime = NodeRuntimeDetector.detect { name ->
      if (name == "tsx") tsxPath else null
    }
    assertThat(runtime).isEqualTo(NodeRuntime.Tsx(tsxPath))
  }

  @Test fun `detect errors when neither present with install hint`() {
    assertFailure {
      NodeRuntimeDetector.detect { null }
    }.all {
      hasClass(NoCompatibleTsRuntimeException::class)
      messageContains("bun")
      messageContains("tsx")
      messageContains("npm install -g tsx")
    }
  }

  @Test fun `bun argv runs script via bun run`() {
    val script = File("/tmp/fixture.ts")
    val argv = NodeRuntime.Bun(bunPath).argv(script)
    assertThat(argv).isEqualTo(listOf(bunPath.absolutePath, "run", script.absolutePath))
  }

  @Test fun `tsx argv invokes tsx with script path`() {
    val script = File("/tmp/fixture.ts")
    val argv = NodeRuntime.Tsx(tsxPath).argv(script)
    assertThat(argv).isEqualTo(listOf(tsxPath.absolutePath, script.absolutePath))
  }
}
