package xyz.block.trailblaze.revyl

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.revyl.tools.RevylNativeAssertTool
import xyz.block.trailblaze.revyl.tools.RevylNativeBackTool
import xyz.block.trailblaze.revyl.tools.RevylNativeDoubleTapTool
import xyz.block.trailblaze.revyl.tools.RevylNativeNavigateTool
import xyz.block.trailblaze.revyl.tools.RevylNativePressKeyTool
import xyz.block.trailblaze.revyl.tools.RevylNativeSwipeTool
import xyz.block.trailblaze.revyl.tools.RevylNativeTapTool
import xyz.block.trailblaze.revyl.tools.RevylNativeToolSet
import xyz.block.trailblaze.revyl.tools.RevylNativeTypeTool

class RevylNativeToolSetTest {

  @Test
  fun `core tool set contains all interaction tools`() {
    val classes = RevylNativeToolSet.RevylCoreToolSet.toolClasses
    assertThat(classes).contains(RevylNativeTapTool::class)
    assertThat(classes).contains(RevylNativeDoubleTapTool::class)
    assertThat(classes).contains(RevylNativeTypeTool::class)
    assertThat(classes).contains(RevylNativeSwipeTool::class)
    assertThat(classes).contains(RevylNativeNavigateTool::class)
    assertThat(classes).contains(RevylNativeBackTool::class)
    assertThat(classes).contains(RevylNativePressKeyTool::class)
  }

  @Test
  fun `assertion tool set contains assert tool`() {
    val classes = RevylNativeToolSet.RevylAssertionToolSet.toolClasses
    assertThat(classes).contains(RevylNativeAssertTool::class)
  }

  @Test
  fun `LLM tool set is superset of core and assertion tool sets`() {
    val llmClasses = RevylNativeToolSet.RevylLlmToolSet.toolClasses
    val coreClasses = RevylNativeToolSet.RevylCoreToolSet.toolClasses
    val assertionClasses = RevylNativeToolSet.RevylAssertionToolSet.toolClasses

    assertThat(llmClasses.containsAll(coreClasses)).isTrue()
    assertThat(llmClasses.containsAll(assertionClasses)).isTrue()
  }
}
