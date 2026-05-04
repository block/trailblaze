# Trailblaze Config — iOS Contacts Example

This directory demonstrates the same single-file JavaScript `tools:` authoring flow against
the iOS system Contacts app. The goal is not to teach the LLM how to tap through Contacts;
the goal is to expose a few higher-level decisions like "open a new draft" or "create a
contact" while keeping the interaction details contained in small scripts.

## Layout

```text
trailblaze-config/
├── README.md
├── trailblaze.yaml          # workspace anchor — references the pack below
├── packs/
│   └── contacts/
│       ├── pack.yaml        # pack manifest
│       └── tools/           # one file per scripted tool
└── tools/                   # JS modules referenced from the pack's tools/<name>.yaml
    ├── contacts_ios_createContact.js
    ├── contacts_ios_openNewContact.js
    ├── contacts_ios_openSavedContact.js
    └── contacts_shared.js
```

## TypeScript IDE Setup

The `tools/` directory carries a `package.json` and `tsconfig.json` so VS Code, IntelliJ, and
other TypeScript-aware IDEs surface autocomplete and type information for `args`, `ctx`, and
`client` in both `.ts` files and JSDoc-annotated `.js` files. To enable it:

```bash
cd examples/ios-contacts/trailblaze-config/tools
npm install   # or `bun install`
```

That symlinks `@trailblaze/scripting` into `node_modules/` from the local SDK source at
`sdks/typescript/`. No registry account or auth required — the dependency uses an
npm `file:` link, not a published package. After installing, open any `.ts` or JSDoc-annotated
`.js` file in this directory and hover `ctx`, `client`, or `args` to confirm autocomplete
resolves against the SDK's typed interfaces. The reference file `_example_typescript_tool.ts`
is a runnable template demonstrating the pattern.

**Path note for adopting this pattern in another pack:** the `file:` link in `package.json`
is relative to the `tools/` directory. The number of `..` segments depends on how deep your
`tools/` sits relative to the repo root. Walk up from your `tools/` to the repo root,
then descend into `sdks/typescript/`. For this example the path is
`file:../../../../sdks/typescript` (4 levels up: `tools/` → `trailblaze-config/` →
`ios-contacts/` → `examples/` → repo root, then down to `sdks/typescript`). Adjust the
depth to match your own pack's location before copying.

This setup is validated in CI on every PR. To run the same validation locally before pushing:

```bash
cd examples/ios-contacts/trailblaze-config/tools
npm install   # or `bun install`
npx tsc --noEmit
```

One expected error surfaces from `sdks/typescript/src/tool.ts` — a known SDK
type-tightening tracked by an open issue. Every other tsc error is real and should be fixed
before merging.

## Running the example

Point Trailblaze at this config directory, restart the daemon, and select the `contacts`
target (the pack id, formerly `ioscontacts`):

```bash
export TRAILBLAZE_CONFIG_DIR=$PWD/examples/ios-contacts/trailblaze-config
TRAILBLAZE_PORT=42424 ./trailblaze app --stop
TRAILBLAZE_PORT=42424 ./trailblaze app --headless & disown
TRAILBLAZE_PORT=42424 ./trailblaze toolbox --device ios --target contacts
TRAILBLAZE_PORT=42424 ./trailblaze tool contacts_ios_createContact --device ios --target contacts -o "Create a new contact in the iOS Contacts app"
```

After editing any file in `targets/` or `tools/`, restart the daemon again. Discovery happens at
daemon startup.

## What This Example Is Trying To Show

- The target exposes the iOS system Contacts app as a normal Trailblaze target.
- The JS wrappers make the model think in terms of outcomes instead of raw taps.
- The wrapper code stays narrow and auditable instead of expanding the built-in Kotlin tool
  surface for every app-specific workflow.
- The registered tool names follow `{target}_{platform}_{tool}`, for example
  `contacts_ios_createContact`.

## How Registration Works

- `name:` inside each `packs/contacts/tools/<id>.yaml` file becomes the real Trailblaze tool id.
- `script:` points at a JS module Trailblaze imports at session start.
- Trailblaze invokes the exported function whose name matches the YAML `name:` with
  `(args, ctx, client)`.
- Helper functions can live above that export or in imported sibling modules like
  `./contacts_shared.js`.

The function does need to be an export today because the runtime loads the module and calls the
symbol whose name matches the registered tool id. A bare top-level script would need a different
argument/result contract.

## Conditional Pattern

If/else logic belongs in these scripts rather than in the trail recording. One simple pattern is
to try an assertion first, then take the alternate path:

```js
export async function contacts_ios_openDraftIfNeeded(args, ctx, client) {
  if (!ctx) {
    throw new Error("This tool requires a live Trailblaze session context.");
  }

  await client.callTool("launchApp", {
    appId: "com.apple.MobileAddressBook",
    launchMode: "FORCE_RESTART",
  });

  try {
    await client.callTool("assertVisibleWithText", { text: "First name" });
    return "A new-contact draft was already open.";
  } catch {
    await client.callTool("tapOnElementWithAccessibilityText", {
      accessibilityText: "Add",
    });
    await client.callTool("assertVisibleWithText", { text: "First name" });
    return "Opened a new-contact draft.";
  }
}
```

## Notes

- The target uses the iOS system app bundle id `com.apple.MobileAddressBook`.
- The scripts default to common English labels like `Add`, `First name`, and `Done`.
- Different iOS versions or simulator locales may use slightly different labels, so each tool
  accepts optional override arguments for the key UI strings.
- Each scripted tool has its own YAML file under `packs/contacts/tools/`; the pack manifest
  references them via `target.tools:`. The pack loader translates the flat author-friendly
  `inputSchema:` into a JSON-Schema-conformant object before runtime registration.
