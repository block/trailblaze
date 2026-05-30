import java.io.File

/**
 * Hand-rolled Kotlin → TypeScript codegen for the selector grammar. Reads the canonical
 * Kotlin sources in `:trailblaze-models/commonMain` (`TrailblazeNodeSelector.kt`,
 * `MatchDescriptor.kt`, `TrailblazeNode.kt`'s nested `Bounds`) and emits the generated
 * `opensource/sdks/typescript/src/generated/selectors.ts` consumed by the TypeScript SDK.
 *
 * **Why source-text parsing, not reflection.** A reflection-based generator would need
 * a JavaExec task with `:trailblaze-models`'s JVM classpath on `task.classpath`, and KDoc
 * is never reachable from kotlin-reflect because the compiler erases it. Source-text
 * parsing keeps the generator inside `build-logic` (no extra Gradle dependency wiring)
 * and preserves KDoc → TSDoc by reading the same characters the developer wrote.
 *
 * **Why hand-rolled, not `kxs-ts-gen` or a JSON Schema bridge.** The full surface
 * is nine type definitions (six [DriverNodeMatch] branches + [TrailblazeNodeSelector]
 * + [MatchDescriptor] + nested [TrailblazeNode.Bounds]); writing a focused parser
 * here is shorter than the library setup either drop-in path would require, and gives
 * us full control over the output style (the discriminated-union vs all-optional shape
 * mirrors the Kotlin data-class-with-nullable-fields convention — not a sealed `NodeSelector`
 * the way the devlog's illustrative example used). Future scope expansion (tool args,
 * result envelopes, etc.) is a separate codegen path; this one stays single-purpose.
 *
 * The parser handles the narrow Kotlin surface this codegen needs and nothing more:
 * `@Serializable data class` / `sealed interface` declarations, primary-constructor
 * parameter lists, and `/** ... */` KDoc immediately preceding each parameter.
 * Annotations on **primary-constructor parameters** are recognized and acted on —
 * `@kotlinx.serialization.Transient` excludes the parameter, and `@SerialName("...")`
 * overrides the TS field name with the wire name. The parser does NOT inspect class
 * bodies: secondary constructors, properties declared inside `class { ... }`, custom
 * accessors with computed bodies, and method bodies are all silently ignored. Nested
 * generic constraints don't appear in the source-of-truth files this generator reads.
 */
object SelectorTsCodegen {

  /**
   * Emit the full generated TypeScript file given the contents of the three input
   * Kotlin files. Returns the byte-stable TS source (LF line endings, trailing newline)
   * that the `generateSelectorsTs` task writes and `verifySelectorsTs` byte-diffs.
   */
  fun generate(
    trailblazeNodeSelectorKt: String,
    matchDescriptorKt: String,
    trailblazeNodeKt: String,
  ): String {
    // Parse the input Kotlin files. The narrow parser only understands `data class` /
    // `sealed interface` declarations + their primary-constructor parameter lists; that
    // is exactly the shape used in the source-of-truth files this codegen reads.
    val selectorClasses = KotlinSourceParser.parseAllDataClasses(trailblazeNodeSelectorKt)
    val matchDescriptorClasses = KotlinSourceParser.parseAllDataClasses(matchDescriptorKt)
    val nodeClasses = KotlinSourceParser.parseAllDataClasses(trailblazeNodeKt)

    val trailblazeNodeSelector = requireOne(selectorClasses, "TrailblazeNodeSelector")
    val driverNodeMatchBranches = listOf(
      "AndroidAccessibility",
      "AndroidMaestro",
      "Web",
      "Compose",
      "IosMaestro",
      "IosAxe",
    ).map { branch ->
      branch to requireOne(selectorClasses, branch)
    }
    val matchDescriptor = requireOne(matchDescriptorClasses, "MatchDescriptor")
    val bounds = requireOne(nodeClasses, "Bounds")

    return buildString {
      append(HEADER)
      append('\n')

      // Driver match interfaces first — the per-driver bag of optional fields the
      // selector's `androidAccessibility?`, `androidMaestro?`, etc. fields reference.
      // Emitting them up front so the downstream `TrailblazeNodeSelector` interface
      // (and the factory namespace below) can reference them with no forward-decl
      // dance.
      for ((branch, parsed) in driverNodeMatchBranches) {
        appendInterface(
          tsName = "DriverNodeMatch$branch",
          parsed = parsed,
        )
        append('\n')
      }

      appendInterface(
        tsName = "TrailblazeNodeSelector",
        parsed = trailblazeNodeSelector,
      )
      append('\n')

      appendInterface(
        tsName = "Bounds",
        parsed = bounds,
      )
      append('\n')

      appendInterface(
        tsName = "MatchDescriptor",
        parsed = matchDescriptor,
      )
      append('\n')

      appendFactoryNamespace(driverNodeMatchBranches.map { it.first })
    }
  }

  private fun requireOne(classes: List<ParsedDataClass>, name: String): ParsedDataClass =
    classes.singleOrNull { it.name == name }
      ?: throw IllegalStateException(
        "Expected exactly one `data class $name` in the parsed Kotlin source. " +
          "Found: ${classes.joinToString { it.name }}. The generator's input files " +
          "may have moved or the class may have been renamed.",
      )

  private fun StringBuilder.appendInterface(tsName: String, parsed: ParsedDataClass) {
    if (parsed.kdoc != null) append(formatTsDoc(parsed.kdoc, indent = ""))
    append("export interface ").append(tsName).append(" {\n")
    for (field in parsed.fields) {
      if (field.kdoc != null) {
        append(formatTsDoc(field.kdoc, indent = "  "))
      }
      append("  ").append(field.name)
      if (field.isOptional) append('?')
      append(": ")
      append(mapKotlinTypeToTs(field.type))
      if (field.isOptional) append(" | null")
      append(";\n")
    }
    append("}\n")
  }

  private fun StringBuilder.appendFactoryNamespace(branchNames: List<String>) {
    append(FACTORY_NAMESPACE_KDOC)
    append("export const selectors = {\n")
    for ((index, branch) in branchNames.withIndex()) {
      val camelName = branch.replaceFirstChar { it.lowercase() }
      append("  ").append(camelName).append(": (args: DriverNodeMatch").append(branch)
      append("): TrailblazeNodeSelector => ({ ").append(camelName).append(": args })")
      if (index != branchNames.lastIndex) append(',')
      append('\n')
    }
    append("};\n")
  }

  /**
   * Render a Kotlin `/** ... */` doc comment as TypeScript JSDoc, preserving the body
   * verbatim and rewriting only the leading-asterisk indentation to the requested
   * [indent]. Single-line KDoc (`/** Short. */`) stays single-line; multi-line KDoc
   * stays multi-line. Kotlin cross-refs (`[ClassName]`, `[someProperty]`) are kept
   * as-is — IDE hovers in TS render them readably even without a follow-on tooling
   * pass, and downstream consumers may eventually rewrite to TSDoc `{@link X}` form
   * but that's not a V1 blocker.
   */
  private fun formatTsDoc(kdoc: String, indent: String): String {
    val trimmed = kdoc.trim()
    return if (!trimmed.contains('\n')) {
      // Single-line: `/** body */`
      "$indent$trimmed\n"
    } else {
      // Multi-line: re-emit with the requested indent on every continuation line so
      // the body lines up under the opening `/**`. Source lines are stripped of any
      // existing leading whitespace + the `*` continuation marker before being
      // re-indented — otherwise indents from the Kotlin source's per-line columns
      // would leak into TS in spots that don't match the surrounding TS indent.
      buildString {
        val lines = trimmed.lines()
        for ((i, line) in lines.withIndex()) {
          val rewritten = if (i == 0) line else {
            val stripped = line.trimStart()
            if (stripped.startsWith("*")) "$indent $stripped"
            else "$indent$stripped"
          }
          append(rewritten)
          append('\n')
        }
      }.let { body ->
        // Prepend indent on first line only when this isn't the file-leading doc
        // (the parser strips leading whitespace already; we add the requested indent
        // back here so the opener column matches the field/interface column).
        if (indent.isEmpty()) body else "$indent${body.trimStart()}"
      }
    }
  }

  /**
   * Map a Kotlin type expression (e.g. `String`, `Int`, `Boolean`, `List<Int>`,
   * `DriverNodeMatch.AndroidAccessibility`, `TrailblazeNodeSelector`, `TrailblazeNode.Bounds`)
   * to its TypeScript counterpart. The mapping covers exactly the type space the
   * selector grammar uses and intentionally throws for anything else — a new Kotlin
   * type in the selector hierarchy must be deliberately added here, not silently
   * coerced to `unknown`.
   */
  private fun mapKotlinTypeToTs(ktType: String): String {
    // List<X> → X[]
    val listMatch = Regex("""^List<(.+)>$""").matchEntire(ktType)
    if (listMatch != null) {
      val inner = listMatch.groupValues[1].trim()
      return "${mapKotlinTypeToTs(inner)}[]"
    }
    return when (ktType) {
      "String" -> "string"
      "Boolean" -> "boolean"
      "Int", "Long", "Float", "Double" -> "number"
      "TrailblazeNodeSelector" -> "TrailblazeNodeSelector"
      "TrailblazeNode.Bounds" -> "Bounds"
      "DriverNodeMatch.AndroidAccessibility" -> "DriverNodeMatchAndroidAccessibility"
      "DriverNodeMatch.AndroidMaestro" -> "DriverNodeMatchAndroidMaestro"
      "DriverNodeMatch.Web" -> "DriverNodeMatchWeb"
      "DriverNodeMatch.Compose" -> "DriverNodeMatchCompose"
      "DriverNodeMatch.IosMaestro" -> "DriverNodeMatchIosMaestro"
      "DriverNodeMatch.IosAxe" -> "DriverNodeMatchIosAxe"
      else -> throw IllegalStateException(
        "Unmapped Kotlin type `$ktType` in selector codegen. If this is a new field on " +
          "an existing class, add a mapping in `SelectorTsCodegen.mapKotlinTypeToTs`. If " +
          "this is a stray non-selector type, the source-of-truth file may have grown " +
          "unrelated declarations the parser scooped up.",
      )
    }
  }

  private const val HEADER: String = """// AUTO-GENERATED — do not edit by hand.
//
// Source-of-truth Kotlin files:
//   opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNodeSelector.kt
//   opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/MatchDescriptor.kt
//   opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNode.kt (nested Bounds)
//
// Regenerate with:
//   ./gradlew :trailblaze-models:generateSelectorsTs
//
// CI's `verifySelectorsTs` task byte-diffs this file against a fresh generation and
// fails the build on drift, so edits made by hand will be reverted on the next CI run.
"""

  private const val FACTORY_NAMESPACE_KDOC: String = """/**
 * Ergonomic constructors for [TrailblazeNodeSelector] with scoped IDE autocomplete on
 * each driver's match-field surface. Both forms below produce identical values:
 *
 * ```ts
 * // Factory form — IDE narrows to the chosen driver's fields
 * const a: TrailblazeNodeSelector = selectors.androidAccessibility({ textRegex: "Submit" });
 *
 * // Literal form — copy-paste compatible with the YAML serialization
 * const b: TrailblazeNodeSelector = { androidAccessibility: { textRegex: "Submit" } };
 * ```
 *
 * The factory is pure sugar — its implementation is `(args) => ({ <driverKey>: args })`
 * — but it lets authors write a selector without remembering the exact wire-discriminator
 * key, and scopes autocomplete to one driver at a time. Adding a new driver is a single
 * Kotlin sealed-class branch + codegen regen; no parallel TypeScript edits to remember.
 */
"""
}

internal data class ParsedDataClass(
  val name: String,
  val kdoc: String?,
  val fields: List<ParsedField>,
)

internal data class ParsedField(
  val name: String,
  val type: String,
  val isOptional: Boolean,
  val kdoc: String?,
)

/**
 * Narrow Kotlin source parser used by [SelectorTsCodegen]. Handles only the subset of
 * Kotlin syntax that appears in the source-of-truth files: `@Serializable` and
 * `@SerialName` annotations, `data class <Name>(<primary-constructor>)` declarations
 * (possibly nested inside a `sealed interface`), and `/** ... */` KDoc preceding each
 * primary-constructor parameter.
 *
 * Anything else — class bodies, secondary constructors, custom accessors, top-level
 * functions, etc. — is silently skipped. The parser is intentionally non-defensive
 * because the source-of-truth files are CI-verified and any drift surfaces as a
 * [SelectorTsCodegen.mapKotlinTypeToTs] missing-mapping error or a verify byte-diff.
 */
internal object KotlinSourceParser {

  fun parseAllDataClasses(source: String): List<ParsedDataClass> {
    val results = mutableListOf<ParsedDataClass>()
    val text = stripLineComments(source)
    val pattern = Regex("""(?:@[A-Za-z][A-Za-z0-9_.()"\s]*\s+)*\bdata\s+class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    var searchFrom = 0
    while (searchFrom < text.length) {
      val match = pattern.find(text, searchFrom) ?: break
      val name = match.groupValues[1]
      val openParenIdx = match.range.last
      val closeParenIdx = findMatchingClose(text, openParenIdx, '(', ')')
      val paramListText = text.substring(openParenIdx + 1, closeParenIdx)
      val classKdoc = findPrecedingKdoc(text, match.range.first)
      val fields = parseParams(paramListText)
      results.add(ParsedDataClass(name = name, kdoc = classKdoc, fields = fields))
      searchFrom = closeParenIdx + 1
    }
    return results
  }

  /**
   * Strip `// ...` line comments while preserving all `/** ... */` block comments and
   * all in-line content. The parser uses `// comment` matches as noise — they appear
   * inside section-divider banners in the source (`// --- Spatial ---`) and could
   * confuse parameter-list scanning. Block comments are kept intact because they carry
   * the KDoc the generator needs to preserve.
   */
  private fun stripLineComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
      val c = source[i]
      // Don't munch through a block comment — it might contain `//` as text
      // (e.g., a URL in a KDoc). Copy block comments verbatim.
      if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
        val end = source.indexOf("*/", i + 2)
        if (end == -1) {
          out.append(source, i, source.length)
          return out.toString()
        }
        out.append(source, i, end + 2)
        i = end + 2
        continue
      }
      // Don't munch through a string literal — it could contain `//` as text.
      if (c == '"') {
        val end = findEndOfStringLiteral(source, i)
        out.append(source, i, end + 1)
        i = end + 1
        continue
      }
      if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
        // Drop to end-of-line
        val nl = source.indexOf('\n', i + 2)
        if (nl == -1) return out.toString()
        i = nl
        continue
      }
      out.append(c)
      i++
    }
    return out.toString()
  }

  private fun findEndOfStringLiteral(source: String, openQuoteIdx: Int): Int {
    var i = openQuoteIdx + 1
    while (i < source.length) {
      val c = source[i]
      if (c == '\\' && i + 1 < source.length) {
        i += 2
        continue
      }
      if (c == '"') return i
      i++
    }
    return source.length - 1
  }

  private fun findMatchingClose(text: String, openIdx: Int, open: Char, close: Char): Int {
    var depth = 0
    var i = openIdx
    while (i < text.length) {
      val c = text[i]
      // Skip past string literals so a quoted `)` doesn't confuse depth tracking.
      if (c == '"') {
        i = findEndOfStringLiteral(text, i) + 1
        continue
      }
      // Skip past block comments — they might contain `)` characters in prose.
      if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
        val end = text.indexOf("*/", i + 2)
        i = (if (end == -1) text.length else end + 2)
        continue
      }
      when (c) {
        open -> depth++
        close -> {
          depth--
          if (depth == 0) return i
        }
      }
      i++
    }
    throw IllegalStateException(
      "Unmatched `$open` starting at index $openIdx. Source-of-truth file may be malformed.",
    )
  }

  /**
   * Scan backwards from [startOfDecl] (the start of a `data class` keyword OR the start
   * of a parameter `val` keyword) to find the immediately-preceding `/** ... */` block
   * comment, skipping only whitespace + annotation lines in between. Returns the raw
   * block comment text (including the `/**` and `*/` delimiters) or null if no KDoc is
   * directly attached.
   */
  private fun findPrecedingKdoc(text: String, startOfDecl: Int): String? {
    // Walk backwards past whitespace + annotation lines until we hit either a `*/`
    // (KDoc directly above) or non-trivia content (no KDoc attached).
    var i = startOfDecl - 1
    while (i >= 0) {
      val c = text[i]
      when {
        c.isWhitespace() -> i--
        // Skip backwards past an annotation: scan to the start of `@`. The annotation
        // may span multiple lines if it has parens, but our annotations are always
        // simple — `@Serializable`, `@SerialName("...")`. The line-strip earlier means
        // we won't hit `//` comments here.
        c == ')' -> {
          // walk to the matching `(`
          var depth = 1
          var j = i - 1
          while (j >= 0 && depth > 0) {
            when (text[j]) {
              ')' -> depth++
              '(' -> depth--
            }
            j--
          }
          i = j
        }
        c == '"' -> {
          // walk to the opening quote
          var j = i - 1
          while (j >= 0 && text[j] != '"') j--
          i = j - 1
        }
        c.isLetterOrDigit() || c == '_' || c == '.' -> {
          // Part of an annotation identifier. Walk to the `@`.
          var j = i - 1
          while (j >= 0 && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '.')) j--
          if (j >= 0 && text[j] == '@') {
            i = j - 1
          } else {
            // Not an annotation, not a KDoc-relevant token. Stop.
            return null
          }
        }
        c == '/' && i >= 1 && text[i - 1] == '*' -> {
          // Found `*/` — walk back to `/**`
          val end = i + 1
          val start = text.lastIndexOf("/**", i - 1)
          if (start == -1) return null
          // Verify nothing other than whitespace/annotations between `end` and
          // [startOfDecl] — already handled by the walk above.
          return text.substring(start, end)
        }
        else -> return null
      }
    }
    return null
  }

  private fun parseParams(paramListText: String): List<ParsedField> {
    if (paramListText.isBlank()) return emptyList()
    val params = splitTopLevelCommas(paramListText)
    val fields = mutableListOf<ParsedField>()
    for (rawParam in params) {
      val param = rawParam.trim()
      if (param.isEmpty()) continue
      // Skip @Transient parameters — they shouldn't be in the wire shape.
      if (param.contains("@kotlinx.serialization.Transient") || param.contains("@Transient")) {
        continue
      }
      // Extract any leading `/** ... */` block first, and compute a "search after"
      // offset so the subsequent `\bval\s+` regex can never match the word "val"
      // appearing INSIDE the KDoc body (e.g., `/** This val is replaced... */`).
      // Without this, the kdoc body becomes a parser confounder — the regex would
      // anchor on the in-comment `val`, point `valIdx` into the comment, and the
      // downstream slice would produce a garbled field name.
      val kdocEndOffset = run {
        val end = param.indexOf("*/")
        if (end == -1) 0 else end + 2
      }
      val valSearchScope = param.substring(kdocEndOffset)
      val valMatchInScope = Regex("""\bval\s+""").find(valSearchScope) ?: continue
      val valIdx = kdocEndOffset + valMatchInScope.range.first
      val afterVal = param.substring(valIdx + 4).trim()
      // afterVal: `name: Type [? ] [= default]`
      val colonIdx = afterVal.indexOf(':')
      if (colonIdx == -1) continue
      val ktName = afterVal.substring(0, colonIdx).trim()
      val rest = afterVal.substring(colonIdx + 1).trim()
      // Strip default value: walk forward to first `=` at top level (not inside generics)
      val equalsIdx = findTopLevelEquals(rest)
      val typeText = if (equalsIdx == -1) rest else rest.substring(0, equalsIdx).trim()
      val isOptional = typeText.endsWith("?")
      val typeStripped = if (isOptional) typeText.dropLast(1).trim() else typeText
      val kdoc = extractParamKdoc(param, valIdx)
      // Honor `@SerialName("wireName")` on the parameter so the generated TS field key
      // matches what the Kotlin serializer puts on the wire. Without this, a Kotlin
      // contributor who renames a field on the wire via `@SerialName` and forgets to
      // also rename the Kotlin property would see authored selectors type-check on the
      // TS side but fail at runtime because the keys don't match the deserializer.
      // Scoped to the region BEFORE `val` so a property name happening to spell
      // `@SerialName` (unlikely, but defended against here) doesn't interfere.
      val annotationScope = param.substring(kdocEndOffset, valIdx)
      val tsName = extractSerialName(annotationScope) ?: ktName
      fields.add(ParsedField(name = tsName, type = typeStripped, isOptional = isOptional, kdoc = kdoc))
    }
    return fields
  }

  /**
   * Pull the string argument out of a `@SerialName("...")` annotation if one appears in
   * [annotationScope] (the text between the parameter's KDoc and its `val` keyword).
   * Returns `null` when the annotation isn't present.
   *
   * Two known limitations of the codegen surface (both fail loud rather than silently
   * producing broken output, so the failure mode is visible at PR / regen time):
   *
   * 1. **Named-argument form** (`@SerialName(value = "foo")`) is legal Kotlin but the
   *    regex only matches the positional form. Throws a directed error pointing at
   *    the extension function.
   * 2. **Non-identifier wire names** — Kotlin source `@SerialName("kebab-case")` would
   *    serialize to the wire key `kebab-case`, but emitting `kebab-case?: number` as a
   *    bare TS interface field is a syntax error (TS interface keys must be valid
   *    identifiers, or quoted strings). Throws rather than silently producing invalid
   *    output. To accept non-identifier wire names, the emitter would need to quote
   *    such keys and the codegen would need to un-escape the captured value before
   *    emission. Out of scope until a real use case shows up.
   *
   * The captured value is the raw source-text between the quote delimiters, so escape
   * sequences from the Kotlin source (`\"`, `\\`) appear as literal backslash+char in
   * the returned string. Because [TS_IDENTIFIER_REGEX] rejects anything containing a
   * backslash (or quote, or hyphen, etc.), this also catches any escaped-quote wire
   * name as a non-identifier and surfaces the same throw.
   */
  private fun extractSerialName(annotationScope: String): String? {
    // Positional form first — most common usage and the convention in the
    // source-of-truth files.
    val positional = Regex("""@SerialName\s*\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"\s*\)""")
      .find(annotationScope)
    if (positional != null) {
      val wireName = positional.groupValues[1]
      if (!TS_IDENTIFIER_REGEX.matches(wireName)) {
        throw IllegalStateException(
          "Found `@SerialName(\"$wireName\")` with a wire name that isn't a valid " +
            "TypeScript identifier. Emitting it as a bare interface field would " +
            "produce a TS syntax error. The codegen does not yet support quoted-key " +
            "emission for non-identifier wire names; either rename the wire key to a " +
            "valid identifier (matches `^[A-Za-z_\$][A-Za-z0-9_\$]*\$`) or extend the " +
            "emitter to wrap such field names in quotes.",
        )
      }
      return wireName
    }
    // Named-arg form — legal Kotlin (`@SerialName(value = "foo")`) but not currently
    // supported. Throw a directed error so a contributor who introduces this syntax
    // sees a clear failure at codegen time rather than a silent wire-key drift.
    val named = Regex("""@SerialName\s*\(\s*value\s*=""").find(annotationScope)
    if (named != null) {
      throw IllegalStateException(
        "Found `@SerialName(value = ...)` named-argument form in selector source. The " +
          "codegen only supports the positional form `@SerialName(\"name\")`. Either " +
          "switch to positional syntax or extend `SelectorTsCodegen.extractSerialName` " +
          "to honor named-arg forms.",
      )
    }
    return null
  }

  /**
   * Identifier shape a TypeScript interface field must satisfy for bare-key emission.
   * Mirrors the lexical subset TypeScript accepts unquoted in interface bodies — letters,
   * digits (not leading), underscore, and `$`. Wire names that fall outside this set
   * need quoted-key syntax in TS, which the emitter doesn't currently produce.
   */
  private val TS_IDENTIFIER_REGEX = Regex("""^[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*$""")

  /**
   * Extract a `/** ... */` block immediately preceding the `val ` keyword in a single
   * parameter declaration. Returns the raw substring (delimiters included, body
   * untouched) — downstream re-emission via [SelectorTsCodegen.formatTsDoc] is what
   * re-indents continuation lines under the field's column. Returns null when the
   * KDoc is separated from `val` by anything other than whitespace + annotation lines
   * (which `stripAnnotationsAndWhitespace` skips).
   */
  private fun extractParamKdoc(paramText: String, valIdx: Int): String? {
    val before = paramText.substring(0, valIdx)
    val kdocEnd = before.lastIndexOf("*/")
    if (kdocEnd == -1) return null
    val kdocStart = before.lastIndexOf("/**", kdocEnd)
    if (kdocStart == -1) return null
    // Anything between kdocEnd+2 and valIdx must be whitespace or annotation lines.
    val betweenRaw = before.substring(kdocEnd + 2, valIdx)
    val between = stripAnnotationsAndWhitespace(betweenRaw)
    if (between.isNotEmpty()) return null
    return paramText.substring(kdocStart, kdocEnd + 2)
  }

  private fun stripAnnotationsAndWhitespace(text: String): String {
    var s = text.trim()
    while (s.startsWith("@")) {
      // skip past identifier + optional (args)
      var i = 1
      while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
      if (i < s.length && s[i] == '(') {
        var depth = 1
        i++
        while (i < s.length && depth > 0) {
          when (s[i]) {
            '(' -> depth++
            ')' -> depth--
            '"' -> {
              // skip past string literal
              i++
              while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length) i++
                i++
              }
            }
          }
          i++
        }
      }
      s = s.substring(i).trim()
    }
    return s
  }

  private fun splitTopLevelCommas(text: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var angleDepth = 0
    var parenDepth = 0
    var i = 0
    while (i < text.length) {
      val c = text[i]
      // Pass through block comments verbatim — they might contain commas.
      if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
        val end = text.indexOf("*/", i + 2)
        val close = if (end == -1) text.length else end + 2
        current.append(text, i, close)
        i = close
        continue
      }
      if (c == '"') {
        val end = findEndOfStringLiteral(text, i)
        current.append(text, i, end + 1)
        i = end + 1
        continue
      }
      when (c) {
        '<' -> angleDepth++
        '>' -> angleDepth--
        '(' -> parenDepth++
        ')' -> parenDepth--
        ',' -> if (angleDepth == 0 && parenDepth == 0) {
          parts.add(current.toString())
          current.clear()
          i++
          continue
        }
      }
      current.append(c)
      i++
    }
    if (current.isNotEmpty()) parts.add(current.toString())
    return parts
  }

  private fun findTopLevelEquals(text: String): Int {
    var angleDepth = 0
    var parenDepth = 0
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '"') {
        i = findEndOfStringLiteral(text, i) + 1
        continue
      }
      when (c) {
        '<' -> angleDepth++
        '>' -> angleDepth--
        '(' -> parenDepth++
        ')' -> parenDepth--
        '=' -> if (angleDepth == 0 && parenDepth == 0) return i
      }
      i++
    }
    return -1
  }
}

/**
 * Convenience for the Gradle plugin: read each of the three Kotlin source files from
 * disk and produce the generated TS. Fails loud if any of the source files don't
 * exist — drift between the plugin wiring and the source layout should surface here,
 * not as a confusing empty-output regen.
 */
internal fun runSelectorTsCodegen(
  trailblazeNodeSelectorKt: File,
  matchDescriptorKt: File,
  trailblazeNodeKt: File,
): String {
  for (f in listOf(trailblazeNodeSelectorKt, matchDescriptorKt, trailblazeNodeKt)) {
    require(f.isFile) { "Selector-codegen input file missing: ${f.absolutePath}" }
  }
  return SelectorTsCodegen.generate(
    trailblazeNodeSelectorKt = trailblazeNodeSelectorKt.readText(Charsets.UTF_8),
    matchDescriptorKt = matchDescriptorKt.readText(Charsets.UTF_8),
    trailblazeNodeKt = trailblazeNodeKt.readText(Charsets.UTF_8),
  )
}
