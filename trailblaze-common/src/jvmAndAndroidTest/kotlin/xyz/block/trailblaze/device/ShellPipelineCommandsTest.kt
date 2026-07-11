package xyz.block.trailblaze.device

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins the wire shapes of [wrapShellPipelineForTransport] and [buildRunAsFileWriteCommand] — the
 * transport-safe pipeline primitive behind `executeShellPipelineAs` / `writeFileAs`.
 *
 * - Shell-backed transport (host: dadb → adbd `sh -c`): the inner command must arrive at the
 *   device shell as ONE single-quoted expression — unquoted, the outer shell evaluates the
 *   pipeline one level too early and the inner command silently degrades.
 * - Shell-less transport (on-device: UiAutomation → `Runtime.exec` StringTokenizer, no shell):
 *   the payload must be a single whitespace-free token that a device-side `sh` expands into a
 *   base64-decode trampoline reproducing the inner command byte-for-byte.
 */
class ShellPipelineCommandsTest {

  private val innerCommand =
    "mkdir -p /data/data/com.example.app/shared_prefs && " +
      "printf %s AAAA | base64 -d > /data/data/com.example.app/shared_prefs/settings.xml"

  @Test
  fun `shell transport single-quotes the inner command for the device shell`() {
    val wrapped = wrapShellPipelineForTransport(usesShellInterpreter = true, innerCommand = innerCommand)

    assertEquals("sh -c '$innerCommand'", wrapped)
  }

  @Test
  fun `shell transport escapes embedded single quotes so the inner command survives intact`() {
    val wrapped = wrapShellPipelineForTransport(
      usesShellInterpreter = true,
      innerCommand = "printf %s can't stop",
    )

    // POSIX escape for an embedded quote: close the quote, emit \', reopen — the device shell
    // reassembles the original text as one `sh -c` argument instead of truncating at the quote.
    assertEquals("sh -c 'printf %s can'\\''t stop'", wrapped)
  }

  @Test
  fun `shell-less transport payload survives Runtime exec tokenization as one token`() {
    val wrapped = wrapShellPipelineForTransport(usesShellInterpreter = false, innerCommand = innerCommand)

    // Runtime.exec(String) splits on whitespace with no shell: the command must tokenize to
    // exactly [sh, -c, <payload>] or the payload shatters and the inner command never runs.
    val tokens = wrapped.split(Regex("\\s+"))
    assertEquals(3, tokens.size, "expected [sh, -c, payload], got $tokens")
    assertEquals("sh", tokens[0])
    assertEquals("-c", tokens[1])
  }

  @Test
  fun `shell-less payload expands to a decode trampoline carrying the inner command`() {
    val wrapped = wrapShellPipelineForTransport(usesShellInterpreter = false, innerCommand = innerCommand)

    // What the device-side `sh` does with the payload: expand ${IFS} to whitespace, producing
    // `printf %s <b64>|base64 -d|sh`, which decodes the base64 and executes it. Simulate the
    // expansion and assert the decoded command is the inner command, verbatim.
    val payload = wrapped.split(Regex("\\s+")).last()
    val expanded = payload.replace("\${IFS}", " ")
    val b64 = expanded.removePrefix("printf %s ").substringBefore("|")
    assertEquals("printf %s $b64|base64 -d|sh", expanded)
    assertEquals(innerCommand, String(Base64.getDecoder().decode(b64), Charsets.UTF_8))
  }

  @Test
  fun `a multi-line inner command rides the shell-less trampoline without shattering`() {
    val multiLine = "mkdir -p /sdcard/example\nls /sdcard/example | head -1"

    val wrapped = wrapShellPipelineForTransport(usesShellInterpreter = false, innerCommand = multiLine)

    // The newline lives inside the base64 payload, so the wrapped command still tokenizes to
    // [sh, -c, payload] and the decoded script preserves the line structure.
    val tokens = wrapped.split(Regex("\\s+"))
    assertEquals(3, tokens.size, "expected [sh, -c, payload], got $tokens")
    val b64 = tokens.last().replace("\${IFS}", " ").removePrefix("printf %s ").substringBefore("|")
    assertEquals(multiLine, String(Base64.getDecoder().decode(b64), Charsets.UTF_8))
  }

  @Test
  fun `blank inner command is rejected on both transports`() {
    assertFailsWith<IllegalArgumentException> {
      wrapShellPipelineForTransport(usesShellInterpreter = true, innerCommand = " ")
    }
    assertFailsWith<IllegalArgumentException> {
      wrapShellPipelineForTransport(usesShellInterpreter = false, innerCommand = "")
    }
  }

  @Test
  fun `file-write plan creates the parent then decodes the payload into the destination`() {
    val content = """<map><boolean name="example_flag" value="true" /></map>""".toByteArray()

    val command = buildRunAsFileWriteCommand(
      devicePath = "/data/data/com.example.app/shared_prefs/settings.xml",
      content = content,
    )

    val b64 = Base64.getEncoder().encodeToString(content)
    assertEquals(
      "mkdir -p '/data/data/com.example.app/shared_prefs' && " +
        "printf %s $b64 | base64 -d > '/data/data/com.example.app/shared_prefs/settings.xml'",
      command,
    )
  }

  @Test
  fun `file-write payload round-trips arbitrary binary content`() {
    val content = ByteArray(256) { it.toByte() }

    val command = buildRunAsFileWriteCommand(devicePath = "/sdcard/blob.bin", content = content)

    val b64 = command.substringAfter("printf %s ").substringBefore(" |")
    assertContentEquals(content, Base64.getDecoder().decode(b64))
  }

  @Test
  fun `file-write single-quotes paths so spaces cannot split words in the inner shell`() {
    val command = buildRunAsFileWriteCommand(
      devicePath = "/sdcard/My Files/settings.xml",
      content = "x".toByteArray(),
    )

    assertEquals(
      "mkdir -p '/sdcard/My Files' && printf %s eA== | base64 -d > '/sdcard/My Files/settings.xml'",
      command,
    )
  }

  @Test
  fun `file-write destination without a parent directory skips the mkdir step`() {
    val command = buildRunAsFileWriteCommand(devicePath = "settings.xml", content = "x".toByteArray())

    assertEquals("printf %s eA== | base64 -d > 'settings.xml'", command)
  }

  @Test
  fun `file-write rejects blank and directory-shaped destinations`() {
    assertFailsWith<IllegalArgumentException> {
      buildRunAsFileWriteCommand(devicePath = " ", content = "x".toByteArray())
    }
    assertFailsWith<IllegalArgumentException> {
      buildRunAsFileWriteCommand(devicePath = "/sdcard/example/", content = "x".toByteArray())
    }
  }

  @Test
  fun `file-write of empty content produces an empty-payload pipeline that truncates the file`() {
    val command = buildRunAsFileWriteCommand(devicePath = "settings.xml", content = ByteArray(0))

    // Base64 of zero bytes is the empty string: `printf %s` emits nothing, `base64 -d` decodes
    // nothing, and the redirect truncates the destination to a 0-byte file.
    assertEquals("printf %s  | base64 -d > 'settings.xml'", command)
  }

  @Test
  fun `file-write escapes a single quote in the destination path`() {
    val command = buildRunAsFileWriteCommand(
      devicePath = "/sdcard/it's here/x.bin",
      content = "x".toByteArray(),
    )

    assertEquals(
      "mkdir -p '/sdcard/it'\\''s here' && printf %s eA== | base64 -d > '/sdcard/it'\\''s here/x.bin'",
      command,
    )
  }

  @Test
  fun `file-write rejects content over the single-argument cap and accepts content at it`() {
    assertFailsWith<IllegalArgumentException> {
      buildRunAsFileWriteCommand(
        devicePath = "/sdcard/blob.bin",
        content = ByteArray(MAX_RUN_AS_WRITE_CONTENT_BYTES + 1),
      )
    }
    // At the cap is legal — the bound is sized so even the shell-less transport's double encoding
    // stays under the kernel's per-argument limit.
    buildRunAsFileWriteCommand(
      devicePath = "/sdcard/blob.bin",
      content = ByteArray(MAX_RUN_AS_WRITE_CONTENT_BYTES),
    )
  }

  @Test
  fun `file-write composed with the host wrapper hands the device shell the inner command verbatim`() {
    val content = """<map><boolean name="example_flag" value="true" /></map>""".toByteArray()
    val fileWrite = buildRunAsFileWriteCommand(
      devicePath = "/data/data/com.example.app/shared_prefs/settings.xml",
      content = content,
    )

    val wrapped = wrapShellPipelineForTransport(usesShellInterpreter = true, innerCommand = fileWrite)

    // Full composition (`writeFileAs` on the host transport — the live path today): simulate the
    // outer device shell consuming the single-quoted argument (strip the outer quotes, collapse
    // the '\'' escapes) and assert the inner `sh -c` receives the file-write pipeline verbatim.
    val payload = wrapped.removePrefix("sh -c ")
    assertEquals('\'', payload.first())
    assertEquals('\'', payload.last())
    assertEquals(fileWrite, payload.drop(1).dropLast(1).replace("'\\''", "'"))
  }

  @Test
  fun `file-write composed with the shell-less wrapper round-trips end to end`() {
    val content = """<map><boolean name="example_flag" value="true" /></map>""".toByteArray()
    val fileWrite = buildRunAsFileWriteCommand(
      devicePath = "/data/data/com.example.app/shared_prefs/settings.xml",
      content = content,
    )

    val wrapped = wrapShellPipelineForTransport(usesShellInterpreter = false, innerCommand = fileWrite)

    // Full composition (`writeFileAs` on the on-device transport): tokenize like Runtime.exec,
    // expand like the device shell, decode like the trampoline — the file-write pipeline must
    // come back byte-for-byte, still carrying the escaped destination path.
    val tokens = wrapped.split(Regex("\\s+"))
    assertEquals(3, tokens.size, "expected [sh, -c, payload], got $tokens")
    val b64 = tokens.last().replace("\${IFS}", " ").removePrefix("printf %s ").substringBefore("|")
    assertEquals(fileWrite, String(Base64.getDecoder().decode(b64), Charsets.UTF_8))
  }
}
