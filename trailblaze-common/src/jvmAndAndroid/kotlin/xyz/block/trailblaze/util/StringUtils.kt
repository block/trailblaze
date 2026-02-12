package xyz.block.trailblaze.util

/**
 * Shared string utilities for generating generic identifiers
 * (camelCase, snake_case, and package-safe names) from arbitrary input.
 */

/** Returns a PascalCase identifier generated from arbitrary input. */
fun toPascalCaseIdentifier(input: String, prefixIfStartsWithDigit: String = "C"): String {
  val baseName = stripPathAndExtension(input)
  var cleaned = baseName.replace(Regex("[^A-Za-z0-9_]"), "_")
  cleaned = cleaned.replace(Regex("_+"), "_").trim('_')

  if (cleaned.isEmpty()) return "${prefixIfStartsWithDigit}_"

  val parts = cleaned.split('_').filter { it.isNotEmpty() }.map { it.lowercase() }
  if (parts.isEmpty()) return "${prefixIfStartsWithDigit}_"

  var result = parts.joinToString("") { it.replaceFirstChar { c -> c.titlecase() } }

  if (result.firstOrNull()?.isDigit() == true) {
    result = "$prefixIfStartsWithDigit$result"
  }
  return result
}

/** Returns a snake_case identifier generated directly from arbitrary input. */
fun toSnakeCaseIdentifier(input: String, prefixIfStartsWithDigit: String = "c_"): String {
  val baseName = stripPathAndExtension(input)
  var cleaned = baseName.replace(Regex("[^A-Za-z0-9]"), "_")
  cleaned = cleaned.replace(Regex("_+"), "_").trim('_').lowercase()
  if (cleaned.isEmpty()) return prefixIfStartsWithDigit
  if (cleaned.first().isDigit()) return "$prefixIfStartsWithDigit$cleaned"
  return cleaned
}

/** Convenience: snake_case identifier with an id appended using an underscore. */
fun toSnakeCaseWithId(
  input: String,
  id: Any,
): String {
  val snake = toSnakeCaseIdentifier(input)
  return "${snake}_$id"
}

/** Returns the file name without any path or extension. */
fun stripPathAndExtension(input: String): String = input.substringAfterLast('/').substringBeforeLast('.')

/** Returns a lowercase, single-segment, package-safe identifier. */
fun toPackageIdentifier(input: String): String {
  val baseName = stripPathAndExtension(input).lowercase()
  var cleaned = baseName.replace(Regex("[^a-z0-9]"), "_")
  cleaned = cleaned.replace(Regex("_+"), "_").trim('_')
  cleaned = cleaned.replace(Regex("^[^a-z]+"), "")
  return cleaned.ifEmpty { "pkg" }
}
