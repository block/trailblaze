package xyz.block.trailblaze.host

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.SensitiveValuesTrailblazeTool
import xyz.block.trailblaze.toolcalls.isMemoryReference
import xyz.block.trailblaze.toolcalls.redactSensitiveValuesIn
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * [yaml] as a session-start log keeps it: persisted and shown in reports, so the values its tools
 * declare secret (`ios_openApp`'s launch arguments) are masked. Execution keeps the original.
 */
internal fun TrailblazeYaml.persistedTrailYaml(yaml: String, decoded: List<TrailYamlItem>): String =
  yamlWithSecretsMasked(yaml, declaredSensitiveValues(yaml, decoded))

/**
 * The values [yaml]'s tools declare secret. [decoded] is what runs on this device; a unified trail's
 * recordings for every other device are read from the document as well, since all of it is kept.
 */
internal fun TrailblazeYaml.declaredSensitiveValues(yaml: String, decoded: List<TrailYamlItem>): Set<String> {
  val everyRecording = (runCatching { decodeTrailDocument(yaml) }.getOrNull() as? TrailDocument.Unified)
    ?.trail?.let { trail -> listOfNotNull(trail.trailhead) + trail.trail }
    ?.flatMap { step -> step.recordings.values.flatten() }
    .orEmpty()
  return decoded.declaredSensitiveValues() + everyRecording.sensitiveValues()
}

/** The values this trail's tools declare secret, across its recordings, trailhead and tool items. */
internal fun List<TrailYamlItem>.declaredSensitiveValues(): Set<String> = buildSet {
  for (item in this@declaredSensitiveValues) {
    when (item) {
      is TrailYamlItem.PromptsTrailItem -> item.promptSteps.forEach { addAll(it.recording?.tools.orEmpty().sensitiveValues()) }
      is TrailYamlItem.TrailheadTrailItem -> addAll(item.trailhead.tools.orEmpty().sensitiveValues())
      is TrailYamlItem.ToolTrailItem -> addAll(item.tools.sensitiveValues())
      is TrailYamlItem.ConfigTrailItem -> Unit
    }
  }
}

/**
 * A value that is only a memory reference (`{{token}}`) names a secret rather than holding one, so it
 * stays in the copy; masking it would make the trail unrecordable.
 */
private fun List<TrailblazeToolYamlWrapper>.sensitiveValues(): List<String> =
  flatMap { wrapper -> (wrapper.trailblazeTool as? SensitiveValuesTrailblazeTool)?.sensitiveValues.orEmpty() }
    .filterNot(::isMemoryReference)

/**
 * [yaml] with every value in [secrets] masked, for a copy that is logged or persisted. Just the mask
 * when the masked text still parses to a value holding a secret: YAML can write one value several
 * ways (a quote or newline escaped differently in each place), and only the verbatim ones can be
 * found. Either way the copy holds the mask, which is what stops a recording being merged into it.
 */
internal fun yamlWithSecretsMasked(yaml: String, secrets: Collection<String>): String {
  val values = secrets.filter { it.isNotBlank() }
  if (values.isEmpty()) return yaml
  val masked = redactSensitiveValuesIn(yaml, values)
  val scalars = runCatching { Yaml.default.parseToYamlNode(masked) }.getOrNull()?.scalars()
  val clean = scalars != null && scalars.none { scalar -> values.any { it in scalar } }
  return if (clean) masked else REDACTED_TOOL_ARG_PLACEHOLDER
}

private fun YamlNode.scalars(): Sequence<String> = when (this) {
  is YamlScalar -> sequenceOf(content)
  is YamlMap -> entries.asSequence().flatMap { (key, value) -> sequenceOf(key.content) + value.scalars() }
  is YamlList -> items.asSequence().flatMap { it.scalars() }
  is YamlTaggedNode -> innerNode.scalars()
  is YamlNull -> emptySequence()
}
