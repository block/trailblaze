# Trail format

The canonical trail document schema, YAML codec, raw tool arguments, classifier lineage,
and recording selection live here. Reading a document does not discover or execute tools.
The module depends only on Kotlin serialization and Kaml; it packages no SDK or device assets.

`TrailFormatYaml()` preserves unknown tool names and arguments as data. Runtime callers may
supply serializers for known tools. `trailblaze-models` re-exports this module and retains
`TrailblazeYaml`, whose `Default` supplies the runtime tool registry. Session recording generation
and tool discovery remain in the runtime module.

The document and runtime paths use the same parsing, validation, closest-wins selection,
explicit no-op recording, multi-device configuration, and skip rules. Format tests live with
their implementation; runtime integration tests remain in `trailblaze-models`.

The full framework builds JVM, Android, Wasm and iOS simulator variants, preserving each
platform’s console implementation. JVM-only builds set `-Ptrailblaze.format.android=false`
to configure the document library without an Android build plugin or SDK prerequisite.
Enable the optional platform gates with
`-Ptrailblaze.wasm=true` and `-Ptrailblaze.ios=true`.
