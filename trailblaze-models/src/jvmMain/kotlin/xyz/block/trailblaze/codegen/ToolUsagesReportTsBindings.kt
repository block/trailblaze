package xyz.block.trailblaze.codegen

import kotlinx.serialization.serializer
import xyz.block.trailblaze.usages.ToolUsagesReport
import java.io.File

/**
 * Generates the TypeScript types for `trailblaze usages --json` — the report a CI consumer parses to
 * decide which trails to replay.
 *
 * One root is enough: [ToolUsagesReport] reaches every nested type through its own fields, so a new
 * field on a nested model appears here without an edit. That is the point of deriving it rather than
 * hand-maintaining a `.d.ts` — the JSON contract and the Kotlin model cannot disagree, and a field
 * added on the Kotlin side without regenerating fails `verifyDtoTs` instead of shipping a TypeScript
 * consumer that silently cannot see it.
 *
 * Not part of [HostRpcDtoTsBindings]: this is not an `RpcRequest` and it is not a daemon endpoint.
 * It is a CLI stdout contract with a different audience and a different lifetime, which is why it
 * gets its own binding and its own committed file.
 *
 * Deliberately NOT re-exported from the SDK's `src/index.ts`, following `host-rpc.ts` and
 * `trailrunner-dtos.ts`: the audience is a CI script parsing CLI output, not a scripted tool running
 * on a device, and adding it to the SDK barrel would put it in every tool's type surface. Consumers
 * import the generated module directly.
 *
 * Run via `./gradlew :trailblaze-models:generateDtoTsUsagesReport`; CI's `verifyDtoTs` byte-diffs the
 * committed `usages-report.ts`.
 */
internal object ToolUsagesReportTsBindings {

  fun generate(): String =
    SerialDescriptorTsCodegen.generate(
      roots = listOf(serializer<ToolUsagesReport>().descriptor),
      header = HEADER,
    )

  private const val HEADER: String =
    "// AUTO-GENERATED — do not edit by hand.\n" +
      "//\n" +
      "// TypeScript types for the `trailblaze usages --json` report, derived from the Kotlin\n" +
      "// @Serializable models. Kotlin is canonical; this is the derived artifact.\n" +
      "//\n" +
      "// Field semantics, the diagnostic-kind registry, and the versioning policy are documented in\n" +
      "// `docs/usages-json.md`. Read that before gating CI on any field.\n" +
      "//\n" +
      "// Regenerate with `./gradlew :trailblaze-models:generateDtoTsUsagesReport`; CI's `verifyDtoTs`\n" +
      "// byte-diffs this file against a fresh generation and fails the build on drift, so hand edits\n" +
      "// are reverted on the next CI run.\n"
}

/** Entry point for the `generateDtoTsUsagesReport` Gradle task. `args[0]` is the output file path. */
internal fun main(args: Array<String>) {
  val outFile = File(args.firstOrNull() ?: error("usage: ToolUsagesReportTsBindingsKt <output-file.ts>"))
  outFile.parentFile?.mkdirs()
  outFile.writeText(ToolUsagesReportTsBindings.generate(), Charsets.UTF_8)
  println("Wrote usages report TypeScript bindings to ${outFile.absolutePath}")
}
