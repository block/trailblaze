@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor

/**
 * Shared, multiplatform-Compose leaf widgets for the recording surface. Lives in
 * `trailblaze-ui/commonMain` so the same widgets compile to JVM (Compose Desktop, where the
 * full recording tab lives today) and wasmJs (where a future browser client will). No JVM-only
 * types in any signature — the widgets take primitives plus [TrailblazeToolDescriptor] /
 * [TrailblazeToolParameterDescriptor], both of which are already in trailblaze-models'
 * commonMain.
 *
 * The widgets that DO reach into JVM-only state (recorder mutations, deviceManager dispatch)
 * stay in `trailblaze-host`. This module is "render a parameter field given a descriptor and
 * a value/onValueChange pair" — pure data-driven rendering, easy to lift.
 */

/**
 * Type-aware single-line input for one tool parameter. Renders an enum dropdown when the
 * descriptor declares `validValues` (Koog `ToolParameterType.Enum` or JSON schema
 * `enum: [...]`), and a plain text field otherwise. Anything the descriptor doesn't
 * explicitly call out as Int/Long/Boolean falls back to a String field whose contents pass
 * through to YAML verbatim, so the author stays unblocked on tools whose param types are
 * richer than the current widget set covers.
 */
@Composable
fun ParameterField(
  param: TrailblazeToolParameterDescriptor,
  value: String,
  onValueChange: (String) -> Unit,
) {
  val validValues = param.validValues
  if (validValues != null && validValues.isNotEmpty()) {
    EnumParameterDropdown(param = param, value = value, options = validValues, onValueChange = onValueChange)
    return
  }
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text("${param.name} (${param.type})") },
    placeholder = param.description?.takeIf { it.isNotBlank() }?.let { { Text(it.take(80)) } },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
  )
}

/**
 * Read-only dropdown bound to one enum-typed parameter. Selection writes the chosen string
 * verbatim to [onValueChange] — same shape the YAML emitter expects — so an author can flip
 * between options without learning the underlying enum names. The label still includes
 * `(${param.type})` for consistency with the free-text fields above; the dropdown affordance
 * is what differentiates this widget from a String input.
 */
@Composable
fun EnumParameterDropdown(
  param: TrailblazeToolParameterDescriptor,
  value: String,
  options: List<String>,
  onValueChange: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = Modifier.fillMaxWidth(),
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text("${param.name} (${param.type})") },
      placeholder = param.description?.takeIf { it.isNotBlank() }?.let { { Text(it.take(80)) } },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
      textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = {
            Text(text = option, style = MaterialTheme.typography.bodyMedium)
          },
          onClick = {
            onValueChange(option)
            expanded = false
          },
        )
      }
    }
  }
}

/**
 * Per-tool parameter form — the editing surface used by both the Tool Palette dialog (when
 * adding a new tool) and the in-card edit mode (when modifying an existing recorded tool).
 * Renders Required and Optional sections with [ParameterField] for each entry, so enum-typed
 * params get a dropdown and free-text params get a `name (Type)` text field.
 *
 * **State contract.** [paramValues] is read-only here; mutations come through [onValueChange]
 * `(name, value)`. Caller owns a [androidx.compose.runtime.snapshots.SnapshotStateMap]
 * (or equivalent) and renders this composable inside its own `LaunchedEffect` /
 * `remember(descriptor)` block so switching the active tool resets the value bag — without
 * that the "tap" tool's `x`/`y` would leak into the next-selected tool's same-named fields.
 *
 * Empty when the descriptor has no parameters at all (some `assertVisible`-style tools);
 * caller should gate on the descriptor's parameter counts before rendering if a "no
 * configuration needed" placeholder fits the surrounding layout better.
 */
@Composable
fun ToolParamForm(
  descriptor: TrailblazeToolDescriptor,
  paramValues: Map<String, String>,
  onValueChange: (String, String) -> Unit,
) {
  if (descriptor.requiredParameters.isNotEmpty()) {
    Text(
      text = "Required",
      style = MaterialTheme.typography.labelMedium,
    )
    Spacer(Modifier.height(4.dp))
    descriptor.requiredParameters.forEach { param ->
      ParameterField(param = param, value = paramValues[param.name] ?: "") { v ->
        onValueChange(param.name, v)
      }
      Spacer(Modifier.height(6.dp))
    }
  }

  if (descriptor.optionalParameters.isNotEmpty()) {
    if (descriptor.requiredParameters.isNotEmpty()) Spacer(Modifier.height(8.dp))
    Text(
      text = "Optional",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    descriptor.optionalParameters.forEach { param ->
      ParameterField(param = param, value = paramValues[param.name] ?: "") { v ->
        onValueChange(param.name, v)
      }
      Spacer(Modifier.height(6.dp))
    }
  }
}

/**
 * Slim clickable affordance between cards in the recording list — opens the Tool Palette
 * pre-targeted to insert at this position. Hover-affordance only; kept low-contrast so it
 * doesn't visually compete with the cards themselves. The user notices it when scanning a
 * card boundary, not before.
 */
@Composable
fun InsertHerePlus(onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    TextButton(
      onClick = onClick,
      contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
      modifier = Modifier.height(20.dp),
    ) {
      Icon(
        Icons.Filled.Add,
        contentDescription = "Insert tool here",
        modifier = Modifier.size(12.dp),
      )
      Spacer(Modifier.width(4.dp))
      Text(
        text = "Insert",
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

/**
 * Build a single-tool YAML block from a descriptor + filled-in [paramValues]. Output shape
 * (`name:\n  field: value`) mirrors what [xyz.block.trailblaze.recording.RecordingYamlCodec.singleToolToYaml]
 * emits, so the result drops cleanly into any path that already accepts that shape.
 *
 * Empty optional values are skipped — leaving a blank `param:` in YAML would either fail to
 * decode or push an explicit null where the tool's default value should win. Required params
 * with empty values still emit (the resulting decode failure surfaces to the parent's
 * Run/Insert error path, which is more honest than letting the dialog "succeed" with a blank
 * value).
 *
 * No quoting is applied to string values — YAML's bare scalar handling covers the typical
 * case, and authors who need explicit quoting for `:`/`#`/leading-space etc. can drop into
 * the in-card YAML editor on the resulting Insert.
 */
fun buildSingleToolYaml(
  descriptor: TrailblazeToolDescriptor,
  paramValues: Map<String, String>,
): String {
  val requiredNames = descriptor.requiredParameters.map { it.name }.toSet()
  val emittedParams = (descriptor.requiredParameters + descriptor.optionalParameters).filter { param ->
    val raw = paramValues[param.name]
    raw != null && (param.name in requiredNames || raw.isNotBlank())
  }
  if (emittedParams.isEmpty()) {
    return "${descriptor.name}:\n"
  }
  val body = emittedParams.joinToString("\n") { param ->
    val raw = paramValues[param.name].orEmpty()
    "  ${param.name}: $raw"
  }
  return "${descriptor.name}:\n$body"
}
