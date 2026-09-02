package xyz.block.trailblaze.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import xyz.block.trailblaze.prompt.withPerStepSystemPromptContext

class PerStepSystemPromptContextTest {

  @Test
  fun `appends dynamic context without mutating the base prompt`() {
    assertThat("base".withPerStepSystemPromptContext("active: seller"))
      .isEqualTo("base\n\nactive: seller")
    assertThat("base".withPerStepSystemPromptContext("active: buyer"))
      .isEqualTo("base\n\nactive: buyer")
  }

  @Test
  fun `null or blank context leaves a single-device prompt unchanged`() {
    assertThat("base".withPerStepSystemPromptContext(null)).isEqualTo("base")
    assertThat("base".withPerStepSystemPromptContext("  ")).isEqualTo("base")
  }
}
