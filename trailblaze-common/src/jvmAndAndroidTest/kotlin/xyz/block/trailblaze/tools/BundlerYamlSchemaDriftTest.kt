package xyz.block.trailblaze.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drift check between the bundler's slim `@Serializable` schema
 * (`trailblaze-pack-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/BundlerYamlSchema.kt`)
 * and the authoritative shape in `:trailblaze-models`.
 *
 * The bundler deliberately re-declares a slim view of the pack-manifest YAML to keep its
 * own classpath independent of the heavy trailblaze-models graph. kaml's `strictMode =
 * false` tolerates fields the bundler doesn't read — but doesn't help when a field the
 * bundler *does* read gets renamed or removed in trailblaze-models. Without this test,
 * such drift only surfaces when a real consumer's pack manifest exercises the field, in
 * production.
 *
 * **Method.** Pure static-text scan, no reflection. Reads both files, extracts
 * `@SerialName("...")` values, asserts every bundler-side name has a match in the
 * authoritative file. Mirrors the pattern of [BuiltInToolsBindingDriftTest].
 *
 * **Drift modes covered.** Renames in trailblaze-models without a corresponding update in
 * BundlerYamlSchema. **Drift modes NOT covered.** Type changes (e.g.,
 * `inputSchema: String` → `inputSchema: List<String>`); the textual scan can't see types,
 * only field names. Type drift surfaces at decode time when a real manifest hits the
 * mismatch — acceptable trade for the simplicity of this check.
 */
class BundlerYamlSchemaDriftTest {

  @Test
  fun `every SerialName in BundlerYamlSchema has a match in trailblaze-models authoritative shape`() {
    val bundlerSchema = locate("trailblaze-pack-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/BundlerYamlSchema.kt")
    val packManifest = locate("trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailblazePackManifest.kt")
    val packScriptedToolFile = locate("trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/PackScriptedToolFile.kt")

    val bundlerNames = extractSerialNames(bundlerSchema.readText())
    // Authoritative names: union of TrailblazePackManifest.kt + PackScriptedToolFile.kt
    // (the latter declares ScriptedToolProperty alongside the tool-file class). Each
    // file contributes BOTH explicit `@SerialName(...)` values AND plain property names
    // (where `@SerialName` is absent, kotlinx-serialization uses the Kotlin property name
    // as the wire key — trailblaze-models leans on that for the common case). Combining
    // both prevents false negatives on fields like `name` / `description` that exist in
    // both schemas but are declared without redundant `@SerialName` on the authoritative
    // side.
    val authoritativeNames = extractSerialNames(packManifest.readText()) +
      extractKotlinPropertyNames(packManifest.readText()) +
      extractSerialNames(packScriptedToolFile.readText()) +
      extractKotlinPropertyNames(packScriptedToolFile.readText())

    assertTrue("Expected to find SerialNames in BundlerYamlSchema; got 0") { bundlerNames.isNotEmpty() }
    assertTrue("Expected to find SerialNames in trailblaze-models; got 0") { authoritativeNames.isNotEmpty() }

    val missing = bundlerNames - authoritativeNames
    if (missing.isNotEmpty()) {
      fail(
        buildString {
          appendLine("Drift detected — these @SerialName values are declared in")
          appendLine("  $bundlerSchema")
          appendLine("but no matching @SerialName exists in the authoritative trailblaze-models files:")
          appendLine("  $packManifest")
          appendLine("  $packScriptedToolFile")
          appendLine()
          missing.sorted().forEach { appendLine("  - $it") }
          appendLine()
          append(
            "Either trailblaze-models renamed/removed the field (update BundlerYamlSchema in " +
              "lockstep) or BundlerYamlSchema has a typo. Slim-schema drift policy is " +
              "documented at the top of BundlerYamlSchema.kt.",
          )
        },
      )
    }
  }

  /**
   * Walk up from the JVM working dir to find the repo-root-anchored file. Same anchor
   * pattern as `BuiltInToolsBindingDriftTest.locateBuiltInToolsTs` — robust to invocation
   * from any module's project dir.
   */
  private fun locate(repoRelativePath: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }

  private fun extractSerialNames(content: String): Set<String> {
    val names = mutableSetOf<String>()
    SERIAL_NAME_PATTERN.findAll(content).forEach { match ->
      names += match.groupValues[1]
    }
    return names
  }

  /**
   * Extract Kotlin property names from constructor / class-body declarations like
   * `val foo: String` and `var bar: Int`. Used to gather the implicit YAML keys that
   * kotlinx-serialization uses when no explicit `@SerialName` is present. Conservative
   * regex — matches `val name: Type` or `var name: Type` at any indent, including with
   * a trailing comma or default-value expression. Does NOT exclude properties annotated
   * with `@SerialName` separately — they end up in the result set redundantly with the
   * `@SerialName` extractor, which is harmless (we union both sets anyway).
   */
  private fun extractKotlinPropertyNames(content: String): Set<String> {
    val names = mutableSetOf<String>()
    PROPERTY_NAME_PATTERN.findAll(content).forEach { match ->
      names += match.groupValues[1]
    }
    return names
  }

  private companion object {
    // `@SerialName("foo")` — kotlinx-serialization's annotation, single-arg or named-arg form.
    private val SERIAL_NAME_PATTERN = Regex("""@SerialName\s*\(\s*(?:value\s*=\s*)?"([^"]+)"""")
    // `val foo: Type` / `var foo: Type` declarations. Non-greedy on the property name so
    // trailing whitespace / colon doesn't get pulled in.
    private val PROPERTY_NAME_PATTERN = Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:""")
  }
}
