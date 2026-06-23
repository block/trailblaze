#!/usr/bin/env bun
// Static-analysis shim invoked by `ScriptedToolDefinitionAnalyzer` (Kotlin) to extract
// the input/output JSON Schemas of every `export const X = trailblaze.tool<I, O>({...})`
// declaration in a set of `.ts` files.
//
// Usage:
//   bun extract-tool-defs.mjs <file1.ts> [<file2.ts> ...]
//
// Stdout (always JSON, even on failure):
//   {
//     "tools": [
//       {
//         "name": "myTool",
//         "sourcePath": "/abs/path/myTool.ts",
//         "line": 21,
//         "description": "TSDoc on the exported const, or null",
//         "inputSchema": { ...JSON Schema for I... },
//         "outputSchema": { ...JSON Schema for O... },
//         "spec": {
//           // Present ONLY when the author used the (spec, handler) overload.
//           // Each field is independently present-or-absent based on what the
//           // object literal at the call site declared. Object spread and
//           // identifier references are NOT resolved today — only inline
//           // literal values are captured. See "spec extraction" comment in
//           // the implementation below.
//           "supportedPlatforms": ["web"],
//           "requiresContext": true,
//           "requiresHost": false,
//           "supportedDrivers": ["playwright-native"]
//         }
//       },
//       ...
//     ],
//     "errors": [
//       {
//         "file": "/abs/path/badTool.ts",
//         "name": "badTool" | null,
//         "message": "human-readable error pointing at the file/type"
//       },
//       ...
//     ]
//   }
//
// Exit codes:
//   0 — produced a JSON envelope (the envelope itself may contain `errors`).
//   1 — fatal error: ran without arguments, or stdout serialization failed. Stderr
//       carries the human-readable message; stdout still emits the envelope.
//
// The shim never throws — every per-file or per-type error lands in `errors` so the
// caller (Kotlin analyzer) can decide policy (warn, abort, batch). This matches the
// `ScriptedToolImportAnalyzer` convention of folding unexpected failures into the
// returned verdict rather than relying on subprocess exit codes for control flow.
//
// **TypeScript-version coupling.** Both `import ts from "typescript"` and
// `ts-json-schema-generator` resolve TypeScript via Node's module-resolution from
// `sdks/typescript/node_modules/`. The SDK pins `typescript@6.0.3` directly; the
// generator package declares its own dep on `typescript: ^5.9.3`. Today's bun/npm
// install hoists the SDK's TS 6 to the top level so BOTH consumers see the same
// version — verified working. A future generator bump that tightens its pin to TS
// 5 exclusively, or a TS 7+ feature the generator can't parse, would break this
// assumption. If `ts-json-schema-generator` is bumped, re-run the analyzer test
// suite (which exercises Date / Record / discriminated unions / function-type
// rejection) to confirm the resolved version still handles the supported subset.

import ts from "typescript";
import { createGenerator } from "ts-json-schema-generator";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * SDK package whose `trailblaze` export defines the authoring surface this
 * analyzer recognizes. Imports from any OTHER package — including third-party
 * builder libraries that happen to publish a `.tool<I, O>(...)` shape — are
 * deliberately not matched. Pinned at module top so `collectTrailblazeAliases`
 * can reference it without hitting a temporal-dead-zone error (the function is
 * called from inside the top-level per-file loop, before any later `const`
 * declaration would have been initialized).
 *
 * `TRAILBLAZE_SDK_PACKAGE` env var overrides the default — provides a config-
 * level escape hatch for re-publishing scenarios (a future re-scope, an
 * umbrella package that re-exports `trailblaze`, a monorepo
 * distribution) without forcing a shim source edit. Empty/missing env value
 * falls back to the canonical `@trailblaze/scripting`.
 */
const TRAILBLAZE_SDK_PACKAGE =
  (process.env.TRAILBLAZE_SDK_PACKAGE && process.env.TRAILBLAZE_SDK_PACKAGE.trim()) ||
  "@trailblaze/scripting";

/**
 * Recognized field names on the typed `(spec, handler)` overload's spec object.
 * Mirrors `TrailblazeTypedToolSpec` in `sdks/typescript/src/tool.ts`. Fields not
 * listed here are silently ignored — author errors (typos like
 * `supportedPlatform: ["web"]`) surface as missing-field defaults at runtime
 * rather than build-time errors. TypeScript's own type checker is the
 * load-bearing guard against typos; the analyzer's job is extraction, not
 * authoring validation.
 *
 * Pinned at module top so [extractToolSpec] (called from inside the top-level
 * per-file loop) can reference it without hitting a temporal-dead-zone error —
 * same trap the [TRAILBLAZE_SDK_PACKAGE] comment above guards against.
 *
 * SISTER-IMPL-TAG: typed-tool-spec-fields. The bare-field-name set must stay
 * in lockstep with:
 *   - `sdks/typescript/src/tool-core.ts`                  (the SDK's TS surface — `TrailblazeTypedToolSpec`)
 *   - `trailblaze-host/.../AnalyzerScriptedToolEnrichment.kt`
 *     `projectAnalyzerSpec`                              (Kotlin projection into `_meta`)
 *   - `.../TrailblazeToolMeta.fromJsonObject`            (MCP/subprocess runtime parser)
 *   - `.../QuickJsToolMeta.fromSpec`                     (in-process runtime parser)
 * Adding a new field to `TrailblazeTypedToolSpec` requires updating all these
 * sites; there is no compile-time check that they agree.
 */
const RECOGNIZED_SPEC_FIELDS = new Set([
  "supportedPlatforms",
  "requiresContext",
  "requiresHost",
  "supportedDrivers",
  "surfaceToLlm",
  "isRecordable",
]);

/**
 * Maximum recursion depth for [literalValueOf]. The recognized
 * `TrailblazeTypedToolSpec` fields are all 1-level shapes (boolean, string,
 * string array) — depth 2 covers `supportedPlatforms: ["web"]` and friends
 * with headroom. A pathological author literal like `[[[[[[[["web"]]]]]]]]`
 * (deliberate or accidental, perhaps via `as any`) won't exhaust the JS stack
 * before we bail out with `undefined` (treated upstream as "skip this field").
 * 8 picked because it's well beyond any realistic spec depth and well below
 * Node's default stack limit (~10000 frames). Bump if a future spec field
 * legitimately nests deeper.
 *
 * Pinned at module top so [literalValueOf] (called from inside the top-level
 * per-file loop via [extractToolSpec]) can reference it without hitting a
 * temporal-dead-zone error — same trap [TRAILBLAZE_SDK_PACKAGE] and
 * [RECOGNIZED_SPEC_FIELDS] above guard against.
 */
const MAX_LITERAL_DEPTH = 8;

const args = process.argv.slice(2);
if (args.length === 0) {
  process.stderr.write("extract-tool-defs.mjs: expected at least one .ts file argument\n");
  process.stdout.write(JSON.stringify({ tools: [], errors: [] }));
  process.exit(1);
}

const tools = [];
const errors = [];

for (const arg of args) {
  const absFile = resolve(arg);
  let source;
  try {
    source = readFileSync(absFile, "utf-8");
  } catch (e) {
    errors.push({
      file: absFile,
      name: null,
      message: `failed to read file: ${e.message ?? e}`,
    });
    continue;
  }

  // One generator per file, lazy-instantiated on first schema lookup.
  // `createGenerator` builds a TypeScript Program — the most expensive step in
  // the schema pipeline — so we want to amortize that cost across every type
  // we need to extract from this file (input + output for every tool, often
  // multiple tools per file). Pinning the generator to `type: "*"` means
  // subsequent `createSchema(typeName)` calls just walk the cached program for
  // the requested root type. Without this cache, a file with 5 tools forced 10
  // independent Program builds (~250-500ms each on a cold JIT).
  let cachedGenerator = null;
  const generatorFor = () => {
    if (cachedGenerator == null) {
      cachedGenerator = createGenerator(generatorConfigForAllTypes(absFile));
    }
    return cachedGenerator;
  };

  // AST-only pass to find tool declarations. Uses `createSourceFile` directly so we
  // don't need a Program / TypeChecker — we're just identifying call expressions of
  // shape `trailblaze.tool<I, O>(...)` and pulling the type-argument node texts.
  //
  // **Receiver match is strict.** The recognized shape is
  // `<identifier>.tool<I, O>(...)` where `<identifier>` resolves to the
  // `@trailblaze/scripting` SDK's `trailblaze` export. Without a real type
  // checker we can't follow `import` aliases (`import { trailblaze as tb }`),
  // so the analyzer accepts:
  //
  //   1. the canonical bare name `trailblaze.tool<I, O>(...)`,
  //   2. any local identifier whose import binding points at `trailblaze`
  //      from `@trailblaze/scripting` (we read the source file's imports to
  //      resolve aliases),
  //   3. namespace-style access `module.trailblaze.tool<I, O>(...)` ONLY when
  //      the namespace's outer identifier comes from `@trailblaze/scripting`.
  //
  // Other `.tool<...>(...)` builders (the MCP SDK, third-party type-aware
  // builder libraries, etc.) are intentionally NOT matched — they share the
  // syntactic shape but not the semantic contract, and a false positive there
  // would have the analyzer attempting to serialize foreign type closures
  // through `ts-json-schema-generator` with confusing per-tool errors. The
  // strict-receiver check eliminates that risk by construction.
  const sourceFile = ts.createSourceFile(
    absFile,
    source,
    ts.ScriptTarget.Latest,
    /*setParentNodes*/ true,
    ts.ScriptKind.TS,
  );

  // Collect every local identifier that names the Trailblaze SDK's `trailblaze`
  // export so aliased imports (`import { trailblaze as tb } from
  // "@trailblaze/scripting"`) resolve correctly. The default set always includes
  // the bare name `"trailblaze"` so an authored file with no explicit import
  // (relying on a build-time-injected global, like the test fixtures) still
  // works.
  const trailblazeLocalNames = collectTrailblazeAliases(sourceFile);

  for (const stmt of sourceFile.statements) {
    if (!ts.isVariableStatement(stmt)) continue;
    const modifiers = ts.canHaveModifiers(stmt) ? ts.getModifiers(stmt) : undefined;
    const isExported = (modifiers ?? []).some(
      (m) => m.kind === ts.SyntaxKind.ExportKeyword,
    );
    if (!isExported) continue;

    for (const decl of stmt.declarationList.declarations) {
      const init = decl.initializer;
      if (!init || !ts.isCallExpression(init)) continue;
      const callee = init.expression;
      if (!ts.isPropertyAccessExpression(callee)) continue;
      if (callee.name.text !== "tool") continue;
      // Tighten the receiver: only match when `<expr>.tool` is rooted at the
      // Trailblaze SDK's `trailblaze` export (by bare name OR an aliased
      // import we collected above). Eliminates false-positive matches against
      // third-party `.tool<I, O>(...)` builders that happen to share the same
      // syntactic shape.
      if (!isTrailblazeReceiver(callee.expression, trailblazeLocalNames)) continue;

      const typeArgs = init.typeArguments;
      const declName = ts.isIdentifier(decl.name) ? decl.name.text : null;

      // **Type-argument arity defaults — matches the SDK's `<TInput = Record<string, never>,
      // TResult = string>` overload defaults.** Three shapes are accepted, mirroring the
      // progression an author moves through as their tool grows:
      //
      //   trailblaze.tool(handler)                          → empty-input, string output
      //   trailblaze.tool<MyInput>(handler)                 → typed input,  string output
      //   trailblaze.tool<MyInput, MyOutput>(handler)       → typed input,  typed output
      //
      // The analyzer fills omitted type arguments with the same defaults the SDK does, so
      // an author who writes the lightest shape still gets a real `inputSchema` (empty
      // object) and `outputSchema` ({"type":"string"}) without any `Record<string, never>`
      // ceremony in their source. Three-or-more type args is still an authoring error
      // (typo from a refactor) and emits a clear diagnostic.
      if (typeArgs && typeArgs.length > 2) {
        errors.push({
          file: absFile,
          name: declName,
          message:
            `tool '${declName ?? "<anonymous>"}': expected at most two type arguments ` +
            `(input, output), got ${typeArgs.length}. Drop the extra type parameter(s) ` +
            "so the signature is `trailblaze.tool<MyInput>(...)` or " +
            "`trailblaze.tool<MyInput, MyOutput>(...)`.",
        });
        continue;
      }
      const inputTypeArg = typeArgs && typeArgs.length >= 1 ? typeArgs[0] : null;
      const outputTypeArg = typeArgs && typeArgs.length >= 2 ? typeArgs[1] : null;

      const name = ts.isIdentifier(decl.name) ? decl.name.text : null;
      if (!name) {
        errors.push({
          file: absFile,
          name: null,
          message:
            "tool export uses a destructuring pattern; only `export const <name> = " +
            "trailblaze.tool(...)` is supported.",
        });
        continue;
      }

      // Type arguments (when present) must be named references (interface / type alias).
      // Inline type literals, primitive keywords, and other shapes are rejected with an
      // actionable error that names WHAT the author actually wrote — so a
      // `trailblaze.tool<string, number>` user gets "got primitive type 'string'" instead
      // of a generic "must be named references" hint that makes them re-read the docs.
      // Omitted arguments resolve to defaults below; they bypass this check.
      const inputTypeName = inputTypeArg ? readNamedTypeReference(inputTypeArg) : null;
      const outputTypeName = outputTypeArg ? readNamedTypeReference(outputTypeArg) : null;
      const reasons = [];
      if (inputTypeArg && !inputTypeName) {
        reasons.push(`input ${describeRejectedTypeArg(inputTypeArg)}`);
      }
      if (outputTypeArg && !outputTypeName) {
        reasons.push(`output ${describeRejectedTypeArg(outputTypeArg)}`);
      }
      if (reasons.length > 0) {
        errors.push({
          file: absFile,
          name,
          message:
            `tool '${name}': type parameters must be named references — ` +
            `${reasons.join("; ")}. Extract into a named interface or type ` +
            "alias and pass that name: " +
            "`interface MyInput { ... }` then `trailblaze.tool<MyInput>(...)`.",
        });
        continue;
      }

      // TSDoc on the exported const (NOT on the type interfaces — those are picked up
      // separately by ts-json-schema-generator and embedded as the schema's
      // top-level / per-property `description`s).
      const description = extractLeadingTsDoc(stmt, source);

      // Spec extraction.
      //
      // Two typed-call shapes the analyzer recognizes:
      //
      //   trailblaze.tool<I, O>(async (input, ctx) => { ... })      // bare handler
      //   trailblaze.tool<I, O>({ supportedPlatforms: ["web"] },    // spec + handler
      //                         async (input, ctx) => { ... })
      //
      // For the with-spec form, arg 0 is an object literal whose fields carry the
      // structured config that, in the YAML world, was authored under
      // `_meta: { trailblaze/... }`. We extract each recognized field as a JSON
      // value so the Kotlin enrichment layer can merge them into the runtime
      // `_meta` JSON object the framework's MCP advertisement reads.
      //
      // **Inline-literal only.** Only inline JSON-compatible literals are captured
      // today:
      //
      //   { supportedPlatforms: ["web"], requiresContext: true }    // OK — literals
      //   { ...sharedDefaults, requiresHost: true }                 // partial — spread skipped
      //   { supportedPlatforms: PLATFORMS_CONST }                   // skipped — identifier
      //
      // Resolving spread expressions and identifier references is deferred
      // (#3352's "factory helper" path). When the analyzer sees an unrecognized
      // expression shape for a value, it skips that field — the runtime falls
      // back to the framework default (false for booleans, empty for the
      // platform/driver gates). Authors who need a captured value can inline the
      // literal at the call site for now.
      //
      // **Why "skip" rather than "fail".** A future PR will resolve helper
      // function returns through the TypeScript Program, and "fail on unknown
      // expression" today would forbid the helper-function pattern entirely
      // even though it compiles and runs correctly — the helper's runtime
      // behavior is unchanged, just the build-time metadata extraction lags.
      // Skipping unknown shapes lets authors use the pattern today and benefit
      // from full metadata extraction once the resolver lands.
      const spec = extractToolSpec(init);
      // Footgun guard: the (spec, handler) overload was used but the spec arg is a non-inline
      // reference the analyzer can't read (`const SPEC = {...}` / a factory call), so the WHOLE
      // spec — supportedPlatforms / surfaceToLlm / … — was dropped. Surface it so the Kotlin layer
      // can warn (or hard-fail a descriptor-less tool) instead of silently shipping an un-gated tool.
      const uncapturedSpec = !spec && specArgIsUncapturedReference(init);

      const { line } = sourceFile.getLineAndCharacterOfPosition(decl.getStart(sourceFile));

      // Default input schema: empty object (no properties, no required fields). Matches
      // the SDK's `TInput = Record<string, never>` default.
      let inputSchema;
      if (inputTypeName) {
        try {
          inputSchema = generatorFor().createSchema(inputTypeName);
        } catch (e) {
          errors.push({
            file: absFile,
            name,
            message:
              `tool '${name}' input type '${inputTypeName}': ${describeGeneratorError(e)}`,
          });
          continue;
        }
      } else {
        inputSchema = { type: "object", properties: {} };
      }
      // Default output schema: `{"type":"string"}`. Matches the SDK's `TResult = string`
      // default — the "this tool returns a text message" shape that fits most tools.
      let outputSchema;
      if (outputTypeName) {
        try {
          outputSchema = generatorFor().createSchema(outputTypeName);
        } catch (e) {
          errors.push({
            file: absFile,
            name,
            message:
              `tool '${name}' output type '${outputTypeName}': ${describeGeneratorError(e)}`,
          });
          continue;
        }
      } else {
        outputSchema = { type: "string" };
      }

      tools.push({
        name,
        sourcePath: absFile,
        line: line + 1,
        description,
        inputSchema,
        outputSchema,
        // Only emit `spec` when at least one recognized field was captured —
        // omitting the key entirely for bare-handler calls keeps the envelope
        // shape stable for existing consumers and lets the Kotlin decoder
        // treat absent-spec the same way it treats no-such-field.
        ...(spec ? { spec } : {}),
        // Emitted only in the dangerous "spec ref, nothing captured" case (see above) so existing
        // consumers that don't read it see an unchanged envelope.
        ...(uncapturedSpec ? { uncapturedSpec: true } : {}),
      });
    }
  }
}

try {
  process.stdout.write(JSON.stringify({ tools, errors }));
} catch (e) {
  process.stderr.write(
    `extract-tool-defs.mjs: failed to serialize output: ${e.message ?? e}\n`,
  );
  process.exit(1);
}

function readNamedTypeReference(typeNode) {
  if (!typeNode) return null;
  if (!ts.isTypeReferenceNode(typeNode)) return null;
  const name = typeNode.typeName;
  if (ts.isIdentifier(name)) return name.text;
  // Qualified names (`A.B.C`) aren't in scope today — the SDK's authoring contract
  // has named top-level interfaces in the tool file itself.
  return null;
}

/**
 * Human-readable description of a rejected type argument, used in error
 * messages so the author sees what they actually wrote (not just the generic
 * "must be named references" rule). The branches cover the cases an author is
 * most likely to hit by mistake — primitive keywords like `string`, inline
 * type literals like `{ foo: string }`, tuple types, union/intersection
 * shapes, and qualified-name references.
 */
function describeRejectedTypeArg(typeNode) {
  if (!typeNode) return "type parameter is missing";
  if (ts.isTypeLiteralNode(typeNode)) {
    return "got inline type literal `{ ... }` — extract it into a named interface";
  }
  // Primitive keyword type nodes (`string`, `number`, `boolean`, `bigint`,
  // `any`, `unknown`, `never`, `void`, `null`, `undefined`, `object`) all
  // surface as their own SyntaxKind. Render the keyword verbatim so the
  // breadcrumb names what the author wrote.
  const kindName = primitiveKindName(typeNode.kind);
  if (kindName) {
    return `got primitive type '${kindName}' — wrap it in a named interface`;
  }
  if (ts.isTupleTypeNode(typeNode)) {
    return "got tuple type — extract it into a named type alias";
  }
  if (ts.isUnionTypeNode(typeNode)) {
    return "got inline union — extract it into a named type alias";
  }
  if (ts.isIntersectionTypeNode(typeNode)) {
    return "got inline intersection — extract it into a named type alias";
  }
  if (ts.isTypeReferenceNode(typeNode)) {
    // Reached only when `readNamedTypeReference` returned null on a
    // qualified-name reference (`A.B.C`) — explain the constraint.
    return "got qualified type reference (e.g. `Module.Type`) — use a top-level alias instead";
  }
  return "type parameter is not a named reference";
}

/**
 * Map a primitive type-node `SyntaxKind` to the TypeScript keyword spelling.
 * Returns null for non-primitive kinds; intentionally excludes type literals,
 * tuples, etc. which have their own branches above.
 */
function primitiveKindName(kind) {
  switch (kind) {
    case ts.SyntaxKind.StringKeyword: return "string";
    case ts.SyntaxKind.NumberKeyword: return "number";
    case ts.SyntaxKind.BooleanKeyword: return "boolean";
    case ts.SyntaxKind.BigIntKeyword: return "bigint";
    case ts.SyntaxKind.AnyKeyword: return "any";
    case ts.SyntaxKind.UnknownKeyword: return "unknown";
    case ts.SyntaxKind.NeverKeyword: return "never";
    case ts.SyntaxKind.VoidKeyword: return "void";
    case ts.SyntaxKind.NullKeyword: return "null";
    case ts.SyntaxKind.UndefinedKeyword: return "undefined";
    case ts.SyntaxKind.ObjectKeyword: return "object";
    case ts.SyntaxKind.SymbolKeyword: return "symbol";
    default: return null;
  }
}

/**
 * Walk a source file's top-level imports and return the set of local
 * identifiers that bind to the Trailblaze SDK's `trailblaze` export. Recognized
 * import forms:
 *
 *   - `import { trailblaze } from "@trailblaze/scripting"` → `{ "trailblaze" }`
 *   - `import { trailblaze as tb } from "@trailblaze/scripting"` → `{ "tb" }`
 *   - `import * as sdk from "@trailblaze/scripting"` → `{ "sdk" }` (namespace
 *     access pattern `sdk.trailblaze.tool<...>(...)` resolves through
 *     [isTrailblazeReceiver]).
 *
 * The bare name `"trailblaze"` is always included so fixtures + ad-hoc files
 * that rely on a build-time-injected global (the analyzer's tests are the main
 * users of this path) still resolve correctly. Authors writing real tool files
 * use one of the recognized import forms.
 */
function collectTrailblazeAliases(sourceFile) {
  const aliases = new Set(["trailblaze"]);
  for (const stmt of sourceFile.statements) {
    if (!ts.isImportDeclaration(stmt)) continue;
    const moduleSpecifier = stmt.moduleSpecifier;
    if (!ts.isStringLiteral(moduleSpecifier)) continue;
    if (moduleSpecifier.text !== TRAILBLAZE_SDK_PACKAGE) continue;
    const clause = stmt.importClause;
    if (!clause) continue;
    const bindings = clause.namedBindings;
    if (!bindings) continue;
    if (ts.isNamespaceImport(bindings)) {
      // `import * as sdk from "@trailblaze/scripting"` — the local binding is
      // `sdk`. Access pattern `sdk.trailblaze.tool<...>(...)` is recognized by
      // [isTrailblazeReceiver] walking the property-access chain.
      aliases.add(bindings.name.text);
      continue;
    }
    if (ts.isNamedImports(bindings)) {
      for (const spec of bindings.elements) {
        // `propertyName` is set ONLY for aliased imports (`{ trailblaze as tb }`);
        // for plain `{ trailblaze }` the binding name IS the property name.
        const sourceName = (spec.propertyName ?? spec.name).text;
        if (sourceName !== "trailblaze") continue;
        aliases.add(spec.name.text);
      }
    }
  }
  return aliases;
}

/**
 * True when [receiverNode] resolves to the Trailblaze SDK's `trailblaze` export
 * — either by direct identifier match or via a recognized namespace-access
 * chain whose root identifier is in [aliasNames]. False for any other shape
 * (including foreign `.tool<I, O>(...)` builders that happen to land on the
 * `.tool` method name).
 */
function isTrailblazeReceiver(receiverNode, aliasNames) {
  if (!receiverNode) return false;
  // Bare identifier — direct match against the alias set.
  if (ts.isIdentifier(receiverNode)) {
    return aliasNames.has(receiverNode.text);
  }
  // Namespace access (`sdk.trailblaze.tool<...>(...)`). The CALLEE is already
  // `<receiverNode>.tool`; here we're testing the `<receiverNode>` part.
  // Recognize `<root>.trailblaze` where `<root>` is in the alias set (the
  // namespace import name) and the inner property name is literally
  // `"trailblaze"`.
  if (ts.isPropertyAccessExpression(receiverNode)) {
    if (receiverNode.name.text !== "trailblaze") return false;
    const root = receiverNode.expression;
    if (!ts.isIdentifier(root)) return false;
    return aliasNames.has(root.text);
  }
  return false;
}

/**
 * Build the ts-json-schema-generator config for a `.ts` file. The generator is
 * configured with `type: "*"` so a single instance can produce schemas for any
 * named type in the file via `createSchema(typeName)` — this lets one Program
 * build amortize across multiple per-tool schema lookups in the same file.
 */
function generatorConfigForAllTypes(filePath) {
  return {
    path: filePath,
    // `"*"` lets us call `createSchema(specificTypeName)` after construction —
    // matches the docs' "Use '*' to generate schemas for all exported types."
    // Combined with `expose: "all"` below, every type in the file is a valid
    // root candidate without forcing authors to `export` every interface.
    type: "*",
    // skipTypeCheck makes the generator tolerant of unresolved external imports
    // (e.g. `@trailblaze/scripting`) when the fixture or trailmap hasn't been compiled.
    // The schema walk only needs to resolve identifiers inside the input/output
    // type closures; full program-wide type checking would force every transitive
    // dependency to resolve.
    skipTypeCheck: true,
    // `all` makes ts-json-schema-generator discover non-exported types as root
    // candidates too. The default ("export") would force every author to write
    // `export interface MyInput` instead of `interface MyInput` for the type
    // parameter to be reachable — an annoying paper-cut that doesn't reflect any
    // real constraint. The $defs bag we don't want is suppressed by `topRef:
    // false` below (the generator inlines the root type rather than emitting a
    // top-level `$ref`).
    expose: "all",
    // `topRef: false` strips the outer `{ $ref: "#/definitions/MyInput" }` wrapper
    // the generator emits by default — produces the bare object schema directly.
    topRef: false,
    additionalProperties: false,
    // Function types in input/output schemas have no JSON Schema equivalent —
    // the default `"comment"` would inline a `$comment: "(x) => string"` and let
    // the schema through, which is misleading: the LLM would see a non-runnable
    // field shape. Raising here forces the author to refactor (e.g. accept the
    // function's serialized result as a primitive) rather than silently producing
    // a schema that can't be honoured at the wire.
    functions: "fail",
  };
}

function describeGeneratorError(e) {
  // ts-json-schema-generator raises typed errors (`UnknownNodeError`,
  // `LogicError`, ...) with rich context. Keep the human-readable surface but trim
  // stack frames — they're irrelevant to the author and clutter the analyzer's
  // exception text.
  const msg = e?.message ?? String(e);
  return msg.split("\n")[0];
}

/**
 * Extract the typed-tool spec object from a `trailblaze.tool(...)` call expression.
 *
 * Returns:
 *   - `null` when the call is a bare-handler form (arg 0 is a function/arrow) —
 *     no spec to extract.
 *   - `null` when arg 0 isn't an object literal (e.g. an identifier reference to
 *     a shared constant). The analyzer doesn't resolve identifiers today; the
 *     runtime falls back to framework defaults for the missing fields. See the
 *     "Inline-literal only" caveat at the call site.
 *   - `null` when arg 0 is an object literal but no recognized fields had
 *     extractable literal values — keeps the envelope shape stable for the
 *     "spec object literal but every field uses unresolved expressions" edge
 *     case.
 *   - An object carrying each recognized field whose value at the call site is
 *     an inline JSON-compatible literal. Spread expressions, computed property
 *     names, and non-literal values are silently skipped.
 */
function extractToolSpec(callExpression) {
  const args = callExpression.arguments;
  if (!args || args.length === 0) return null;
  const arg0 = args[0];
  // Bare-handler form: arg 0 is a function/arrow. Nothing to extract.
  if (
    ts.isArrowFunction(arg0) ||
    ts.isFunctionExpression(arg0)
  ) {
    return null;
  }
  // With-spec form requires an object literal at arg 0. Anything else (identifier
  // reference, call expression, etc.) we skip — see the inline-literal caveat at
  // the call site.
  if (!ts.isObjectLiteralExpression(arg0)) return null;

  const captured = {};
  for (const prop of arg0.properties) {
    // Spread elements (`...sharedDefaults`) — skip. A future PR can resolve the
    // spread source through the TypeScript Program; today we accept the
    // partial-extraction outcome to keep the helper-function pattern working.
    if (!ts.isPropertyAssignment(prop)) continue;
    // Computed property names (`{ [key]: value }`) — skip. The recognized field
    // set is identifier-based; computed keys can't match.
    if (!ts.isIdentifier(prop.name) && !ts.isStringLiteral(prop.name)) continue;
    // Both `Identifier` and `StringLiteral` AST nodes expose `.text` (the guard
    // above is what makes this safe), so no branch on the kind is needed here.
    const fieldName = prop.name.text;
    if (!RECOGNIZED_SPEC_FIELDS.has(fieldName)) continue;
    const captured_value = literalValueOf(prop.initializer);
    if (captured_value === undefined) continue; // unresolvable value — skip
    captured[fieldName] = captured_value;
  }
  return Object.keys(captured).length > 0 ? captured : null;
}

/**
 * True when the call uses the `(spec, handler)` overload but its spec argument is a non-inline
 * reference the analyzer can't read — `trailblaze.tool<I>(SPEC, handler)` with `const SPEC = {...}`,
 * `tool(Specs.foo, handler)`, or `tool(makeSpec(), handler)`. In every such case the ENTIRE spec
 * (supportedPlatforms / surfaceToLlm / requiresHost / …) is dropped, silently un-gating the tool.
 *
 * Distinguished from the harmless forms:
 *   - bare handler `tool(handler)` / `tool(namedHandlerFn)` — ONE arg, so not flagged;
 *   - inline literal `tool({ ... }, handler)` — arg 0 is an object literal (captured, possibly
 *     partially — that's the existing skip policy, not this whole-spec-dropped case);
 *   - imperative `tool(name, spec, handler)` — arg 0 is a string literal.
 *
 * Requiring a second argument is what separates `tool(SPEC, handler)` (2 args → spec ref) from
 * `tool(namedHandlerFn)` (1 arg → the handler itself is a reference, which is fine).
 */
function specArgIsUncapturedReference(callExpression) {
  const args = callExpression.arguments;
  // Scope to exactly the 2-arg typed overload `tool<I,O>(spec, handler)`. One arg is a bare handler;
  // 3+ args is the imperative `tool(name, spec, handler)` form (a different shape the typed analyzer
  // doesn't own) — neither is the "spec reference dropped" case this guard targets.
  if (!args || args.length !== 2) return false;
  const arg0 = args[0];
  if (ts.isArrowFunction(arg0) || ts.isFunctionExpression(arg0)) return false;
  if (ts.isObjectLiteralExpression(arg0)) return false;
  if (ts.isStringLiteral(arg0)) return false;
  return true;
}

/**
 * Convert a TypeScript AST node into a JSON-compatible literal value, or
 * `undefined` if the node isn't an inline literal we can safely read.
 *
 * Recognized shapes (matched against the value-shapes used by the recognized
 * `TrailblazeTypedToolSpec` fields, which are all boolean / string /
 * string[] today):
 *   - `"text"` / `'text'` → string
 *   - `true` / `false` → boolean
 *   - `[expr, expr, ...]` → array (recursively; an element that doesn't
 *     resolve to a literal causes the whole array to return `undefined`)
 *
 * Identifier references, template literals with substitutions, function calls,
 * spread elements, numeric literals, the `null` keyword, and other expression
 * shapes all return `undefined` — the caller treats that as "skip this field."
 * Numbers and `null` are deliberately unsupported: no `TrailblazeTypedToolSpec`
 * field is typed as a number or as nullable today, so accepting them would let
 * a malformed literal like `requiresHost: null` (via `as any`) flow through to
 * the runtime `_meta` and degrade to "unrestricted" behavior silently. If a
 * future spec field needs `number` or `null` values, re-introduce the
 * corresponding branches AND add a test fixture so they don't bit-rot.
 *
 * The `depth` parameter is the recursion-depth counter (defaults to 0 from
 * top-level call sites). Caps at [MAX_LITERAL_DEPTH] to defend against
 * pathologically nested array literals exhausting the JS stack.
 */
function literalValueOf(node, depth = 0) {
  if (depth > MAX_LITERAL_DEPTH) return undefined;
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) {
    return node.text;
  }
  if (node.kind === ts.SyntaxKind.TrueKeyword) return true;
  if (node.kind === ts.SyntaxKind.FalseKeyword) return false;
  if (ts.isArrayLiteralExpression(node)) {
    const result = [];
    for (const elt of node.elements) {
      // Reject spread inside arrays — same partial-resolution policy as the
      // outer spec extraction. A single unresolvable element fails the whole
      // array so we don't silently emit a half-populated list (which would
      // produce a misleading "platforms: [web]" extraction when the author
      // actually wrote `["web", ...EXTRA]` and meant `[web, android]`).
      if (ts.isSpreadElement(elt)) return undefined;
      const v = literalValueOf(elt, depth + 1);
      if (v === undefined) return undefined;
      result.push(v);
    }
    return result;
  }
  return undefined;
}

function extractLeadingTsDoc(node, source) {
  const ranges = ts.getLeadingCommentRanges(source, node.getFullStart());
  if (!ranges || ranges.length === 0) return null;
  // Prefer the LAST `/** ... */` block immediately above the declaration — TS
  // attaches multiple comment ranges (line comments and earlier block comments)
  // when authors stack unrelated commentary above the doc block.
  for (let i = ranges.length - 1; i >= 0; i--) {
    const range = ranges[i];
    if (range.kind !== ts.SyntaxKind.MultiLineCommentTrivia) continue;
    const text = source.slice(range.pos, range.end);
    if (!text.startsWith("/**")) continue;
    return cleanJsDocText(text);
  }
  return null;
}

function cleanJsDocText(rawBlock) {
  // Strip the `/** ... */` envelope and leading `* ` on each line. Matches the
  // shape TSDoc parsers emit for the `description` field of a documentation
  // comment.
  const inner = rawBlock
    .replace(/^\/\*\*/, "")
    .replace(/\*\/$/, "")
    .replace(/^[ \t]*\*[ \t]?/gm, "")
    .trim();
  return inner.length > 0 ? inner : null;
}
