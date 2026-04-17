package xyz.block.trailblaze.cli

/** Builds YAML for tool invocations from (possibly nested) argument maps. */
internal object ToolYamlBuilder {

  fun build(toolName: String, args: Map<String, Any>): String {
    if (args.isEmpty()) return "- $toolName: {}"
    val sb = StringBuilder("- $toolName:\n")
    appendYamlMap(sb, args, indent = 4)
    return sb.toString().trimEnd()
  }

  /** Append a map's entries as YAML at the given indent level. */
  @Suppress("UNCHECKED_CAST")
  private fun appendYamlMap(sb: StringBuilder, map: Map<String, Any>, indent: Int) {
    val prefix = " ".repeat(indent)
    for ((key, value) in map) {
      when (value) {
        is Map<*, *> -> {
          sb.appendLine("$prefix$key:")
          appendYamlMap(sb, value as Map<String, Any>, indent + 2)
        }
        is List<*> -> {
          sb.appendLine("$prefix$key:")
          appendYamlList(sb, value as List<Any>, indent + 2)
        }
        else -> sb.appendLine("$prefix$key: ${formatScalar(value)}")
      }
    }
  }

  /** Append list items as YAML at the given indent level. */
  @Suppress("UNCHECKED_CAST")
  private fun appendYamlList(sb: StringBuilder, list: List<Any>, indent: Int) {
    val prefix = " ".repeat(indent)
    for (item in list) {
      when (item) {
        is Map<*, *> -> {
          val entries = (item as Map<String, Any>).entries.toList()
          if (entries.isEmpty()) {
            sb.appendLine("$prefix- {}")
          } else {
            // First entry on the same line as the dash
            val (firstKey, firstValue) = entries.first()
            when (firstValue) {
              is Map<*, *> -> {
                sb.appendLine("$prefix- $firstKey:")
                appendYamlMap(sb, firstValue as Map<String, Any>, indent + 4)
              }
              is List<*> -> {
                sb.appendLine("$prefix- $firstKey:")
                appendYamlList(sb, firstValue as List<Any>, indent + 4)
              }
              else -> sb.appendLine("$prefix- $firstKey: ${formatScalar(firstValue)}")
            }
            // Remaining entries indented under the dash
            for (j in 1 until entries.size) {
              val (k, v) = entries[j]
              when (v) {
                is Map<*, *> -> {
                  sb.appendLine("$prefix  $k:")
                  appendYamlMap(sb, v as Map<String, Any>, indent + 4)
                }
                is List<*> -> {
                  sb.appendLine("$prefix  $k:")
                  appendYamlList(sb, v as List<Any>, indent + 4)
                }
                else -> sb.appendLine("$prefix  $k: ${formatScalar(v)}")
              }
            }
          }
        }
        is List<*> -> {
          sb.appendLine("$prefix-")
          appendYamlList(sb, item as List<Any>, indent + 2)
        }
        else -> sb.appendLine("$prefix- ${formatScalar(item)}")
      }
    }
  }

  /** Format a scalar value for YAML output. */
  private fun formatScalar(value: Any): String = when (value) {
    is Boolean -> value.toString()
    is Int -> value.toString()
    is Long -> value.toString()
    is Double -> value.toString()
    is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    else -> "\"$value\""
  }
}
