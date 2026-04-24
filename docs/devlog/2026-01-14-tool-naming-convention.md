---
title: "Tool Naming Convention"
type: decision
date: 2026-01-14
---

# Tool Naming Convention

With multiple tool authors contributing, we needed naming consistency.

## Background

Tool names must be **globally unique** because our serialization system uses the tool name as the sole lookup key. Additionally, we want to minimize LLM context by only exposing relevant tools, and keep schemas simple by avoiding platform-conditional parameters.

## What we decided

### Naming Convention

| Category | Format | Example |
| :--- | :--- | :--- |
| Universal primitive | `{verbNoun}` | `tap`, `scroll`, `inputText` |
| Platform primitive | `{platform}_{verbNoun}` | `ios_clearKeychain`, `android_pressSystemBack` |
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
