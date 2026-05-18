---
title: "Tool Naming Convention"
type: decision
date: 2026-01-14
---

# Tool Naming Convention

> **Status (as of 2026-04-26):** This document accurately describes the **current flat
> runtime tool namespace** and the conventions tools registered against that namespace
> still use. The original rationale — that names were tightly coupled to finding backing
> Kotlin classes during serialization and registration — no longer holds. YAML-described
> tools now point at fully-qualified class names directly, so id shape is no longer
> driven by class lookup. The forward direction is the pack model in
> [Target Packs: Local-First Packaging](2026-04-26-target-packs-local-first.md), where
> ownership is implicit by directory layout and the canonical id shape becomes
> `<pack>:<local-name>` (e.g. `gmail:web`, `clock:alarm-tab`) rather than the underscore
> prefix form below. Treat this document as a record of current state, **not** the
> design argument against pack-scoped simple names going forward.
>
> **Update (2026-05-15):** Enforcement now exists. A unit test in
> `:trailblaze-common` (`ToolNamingConventionTest`) asserts every
> `@TrailblazeToolClass(name = ...)` value matches the structural regex below. The
> three known pre-existing non-conformant names (`adbShell`, `androidSystemUiDemoMode`,
> `clearAppData`) were renamed in the same change that added the enforcement, so the
> grandfather allowlist is empty and every tool in the flat namespace conforms. See
> the "Enforcement" and "Migration history" sections.

With multiple tool authors contributing, we needed naming consistency.

## Background

Tool names must be **globally unique** because our serialization system uses the tool name as the sole lookup key. Additionally, we want to minimize LLM context by only exposing relevant tools, and keep schemas simple by avoiding platform-conditional parameters.

## What we decided

### Naming Convention

| Category | Format | Example |
| :--- | :--- | :--- |
| Universal primitive | `{verbNoun}` | `tap`, `scroll`, `inputText` |
| Platform primitive | `{platform}_{verbNoun}` | `ios_clearKeychain`, `android_pressSystemBack` |
| Cross-platform mobile primitive | `mobile_{verbNoun}` | `mobile_clearAppData`, `mobile_listInstalledApps` |
| Org-wide | `org_{verbNoun}` | `org_mockServer`, `org_resetTestEnvironment` |
| Org-wide + platform | `org_{platform}_{verbNoun}` | `org_ios_configureTestUser` |
| App-specific | `{app}_{verbNoun}` | `myapp_launchAppSignedIn`, `checkout_applyCoupon` |
| App + platform | `{app}_{platform}_{verbNoun}` | `myapp_ios_scroll` |

**Key rules:**
- Use **underscores** as separators (dots aren't supported in OpenAI function names)
- **Device type** (phone/tablet) should NOT appear in names—use execution context instead
- **Versioning**: Append `_v2`, `_v3` for breaking changes (e.g., `myapp_ios_scroll_v2`)

### When to Use App + Platform Tools

Only when parameter schemas differ materially between platforms:

```kotlin
// Good: focused schemas
myapp_ios_launchAppSignedIn(permissions: [String], virtualCard: Bool)
myapp_android_launchAppSignedIn(permissions: [String], overlayPermission: Bool)

// Avoid: confusing conditional parameters
myapp_launchAppSignedIn(permissions: [String], virtualCard: Bool?, overlayPermission: Bool?)
```

### Tool Metadata

```kotlin
@TrailblazeToolClass(
    name = "myapp_ios_launchAppSignedIn",
    isForLlm = true,        // false = deprecated or implementation-detail tool
    isRecordable = true,
    platforms = [Platform.IOS],
)
```

Tools are filtered before LLM exposure based on `isForLlm`, target app, and current platform. The executor must also reject unsupported tool calls at runtime.

### Reserved Names

Centrally owned and validated at build time:
- Global primitives (`tap`, `scroll`, etc.)
- Platform primitives (`ios_*`, `android_*`)
- Org-wide (`org_*`)
- App prefixes (`myapp`, `payments`, etc.)

### Enforcement

A unit test in `:trailblaze-common` (`ToolNamingConventionTest`) iterates over
`TrailblazeToolSet.NonLlmTrailblazeTools` ∪ `TrailblazeToolSet.DefaultLlmTrailblazeTools`
— the union of every class-backed tool reachable from the catalog — and asserts each
`@TrailblazeToolClass(name = ...)` value matches:

```
^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*)*$
```

That regex catches malformed shapes (`Adb_Shell`, `_foo`, `2tap`, `android__foo`,
`foo_`) but cannot semantically tell that a bare-verb name like `clearAppData` should
really have been `android_clearAppData` — only a human reading the tool knows that.
Names in that semantic-but-not-structural gap are tracked in the test's
`grandfatheredNonConformantNames` set, each with a "rename to X" comment naming the
intended destination. The list is intended to burn down to zero; when it empties, the
convention is fully enforced.

**Adding a new tool**: name it per the convention table above. Don't extend the
grandfather list — that's for pre-existing names only.

**Renaming a grandfathered tool**: change the `@TrailblazeToolClass(name = ...)` value
AND remove the old name from the allowlist in the same change. A second test in the
same file asserts the allowlist is a strict subset of the actual tool name set, so a
stale entry left behind after a rename fails CI loudly.

### Migration history

| Old name | New name | Renamed |
| :--- | :--- | :--- |
| `adbShell` | `android_adbShell` | 2026-05-15 |
| `androidSystemUiDemoMode` | `android_systemUiDemoMode` | 2026-05-15 |
| `clearAppData` | `mobile_clearAppData` | 2026-05-15 |

All three landed together because the enforcement test (above) acts as a safety net:
any callsite missed during a rename surfaces as a build failure rather than a silent
runtime miss. With that net in place the previous "one tool at a time" caution no
longer applies. The grandfather allowlist is empty as of this entry; future
additions are restricted to pre-existing non-conformant names discovered later, not
newly-authored tools.

### Shared Implementation

Platform-specific tools can share logic via delegation:

```kotlin
class MyAppIosLaunchAppSignedIn : Tool {
    override fun execute(args: Args) = sharedLaunchLogic(args, platformConfig = iosConfig)
}
```

## What changed

**Positive:** Globally unique names, minimized LLM context, simple schemas, clear tool provenance.

**Negative:** Requires naming discipline and potential migration of existing tools.
