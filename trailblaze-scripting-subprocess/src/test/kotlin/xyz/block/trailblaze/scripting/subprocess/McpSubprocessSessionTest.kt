package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport.StderrSeverity
import kotlin.test.Test

class McpSubprocessSessionTest {

  @Test fun `default stderr classifier maps error lines to WARNING`() {
    val classify = McpSubprocessSession.DEFAULT_STDERR_CLASSIFIER

    assertThat(classify("Error: could not fetch user")).isEqualTo(StderrSeverity.WARNING)
    assertThat(classify("ERROR pre-flight check failed")).isEqualTo(StderrSeverity.WARNING)
    assertThat(classify("Handler errored out")).isEqualTo(StderrSeverity.WARNING)
  }

  @Test fun `default stderr classifier routes non-error output to DEBUG`() {
    val classify = McpSubprocessSession.DEFAULT_STDERR_CLASSIFIER

    assertThat(classify("tool 'myapp_login' registered")).isEqualTo(StderrSeverity.DEBUG)
    assertThat(classify("listening on stdio")).isEqualTo(StderrSeverity.DEBUG)
  }

  @Test fun `default client info identifies trailblaze`() {
    assertThat(McpSubprocessSession.DEFAULT_CLIENT_INFO.name).isEqualTo("trailblaze")
  }
}
