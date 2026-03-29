---
title: "Device-Specific Trail Recordings"
type: decision
date: 2026-01-29
---

# Trailblaze Decision 019: Device-Specific Trail Recordings

## Context

Trailblaze tests need to execute across multiple platforms (Android, iOS, web) and device types (phones, tablets, custom hardware). While the *intent* of a test is the same—"Sign in and verify the dashboard loads"—the actual UI interactions often differ significantly between platforms and device form factors.

Consider these real-world differences:

- **Platform differences**: iOS uses accessibility identifiers while Android uses resource IDs; navigation patterns differ (back gesture vs. back button)
- **Form factor differences**: Tablets may show split-view layouts where phones show single screens; tablet tests might need additional taps to select items from a master list
- **Hardware-specific UI**: Custom hardware devices may have unique screen sizes and hardware buttons that differ from consumer devices
- **Resolution/density differences**: Scroll distances, tap coordinates, and visible element counts vary between device sizes

Attempting to use a single recording across all platforms and devices leads to flaky tests and false failures.

## Decision

**Trail recordings are stored per-platform and per-device type, with `trail.yaml` (natural language) as the authoritative source of truth.**

### The Source of Truth: `trail.yaml`

The `trail.yaml` file contains the natural language test steps. This file defines *what* the test should do, independent of *how* it's executed on any specific device:

```yaml
- prompts:
    - step: Launch the app signed in with test@example.com
    - step: Navigate to the Items screen
    - step: Create a new item named "Test Product"
    - step: Verify the item appears in the list
```

This `trail.yaml` is the source of truth because:

1. **It represents the test intent**: The natural language steps capture what the test is verifying, not implementation details
2. **It's human-readable and reviewable**: QE engineers can understand and update test steps without knowing platform-specific interactions
3. **It can be managed externally**: Test case management systems can generate or sync the natural language steps
4. **It enables AI interpretation**: When no recording exists, the LLM agent interprets these steps to execute the test

> **Important:** If a device-specific recording exists (e.g., `android-phone.trail.yaml`), it is used instead of interpreting the natural language. The recording contains the same steps but with captured tool invocations.

### Device Classifiers

Trailblaze uses a **classifier system** to identify devices and resolve the appropriate recording. Classifiers are ordered from most general to most specific:

1. **Platform classifier** (required, always first): `android`, `ios`, `web`, or custom platform identifiers
2. **Form factor classifier**: `phone`, `tablet`, `iphone`, `ipad`, etc.
3. **Future classifiers** (extensible): API version, orientation (`landscape`/`portrait`), specific device models

At runtime, the `TrailblazeDeviceClassifiersProvider` interface detects the current device and returns its classifiers. For example, a Pixel phone returns `["android", "phone"]` and an iPad returns `["ios", "ipad"]`.

The framework provides a default implementation for standard Android and iOS devices. Custom implementations can support additional hardware platforms.

Recording filenames are derived by joining classifiers with dashes: `{platform}-{form_factor}.trail.yaml`

> **Note:** Classifier names themselves cannot contain dashes, as dashes are used as the delimiter in filenames.

### Platform and Device-Specific Recordings

Each successful execution generates a recording specific to that device's classifiers:

```
trails/login/
├── trail.yaml                  # Source of truth (natural language)
├── android-phone.trail.yaml    # Android phone recording
├── android-tablet.trail.yaml   # Android tablet recording
├── ios-iphone.trail.yaml       # iPhone recording
└── ios-ipad.trail.yaml         # iPad recording
```

Each recording captures the exact tool calls that succeeded on that device type:

```yaml
# android-phone.trail.yaml
- prompts:
    - step: Navigate to the Items screen
      recording:
        tools:
          - tapOnElementWithResourceId:
              resourceId: "com.example.myapp:id/nav_items"
```

```yaml
# ios-iphone.trail.yaml
- prompts:
    - step: Navigate to the Items screen
      recording:
        tools:
          - tapOnElementWithAccessibilityIdentifier:
              accessibilityIdentifier: "items_tab"
```

### Why This Matters

#### 1. Higher Confidence in CI Failures

When tests use deterministic recordings, failures signal real issues—not LLM interpretation variance or platform-specific flakiness. A failing `ios_phone.trail.yaml` recording tells you exactly which tool call failed and what changed in the app.

#### 2. Faster Execution

Recorded mode skips LLM interpretation entirely, replaying tool calls directly. This dramatically speeds up CI runs and reduces API costs.

#### 3. Independent Recording Lifecycle

Each platform recording can be updated independently. If the Android UI changes, only the Android recordings need re-recording—iOS recordings remain stable.

#### 4. Gradual Coverage Expansion

Teams can start with recordings for their primary device type (e.g., `ios_phone`) and expand coverage to tablets and other platforms over time. Tests still run on unrecorded platforms via AI interpretation.

### Runtime Resolution

When executing a test, Trailblaze resolves the recording based on the device's classifiers:

1. **Device-specific recording exists**: Use `android-phone.trail.yaml` (deterministic replay)
2. **No matching recording**: Fall back to `trail.yaml` (AI interprets natural language)

There is intentionally **no middle-tier fallback** (e.g., `android.trail.yaml` for all Android devices). Device-specific recordings scale better for automated generation and require less manual maintenance—when a recording is generated, it's tied to the exact device type that produced it.

> **Note on AI Fallback:** When a recorded test fails during execution, Trailblaze can attempt to recover using AI interpretation. This "AI fallback" functionality is covered in [Decision 021: AI Fallback](2026-01-29-ai-fallback.md).

### Recording Generation Workflow

Recordings are created through two primary mechanisms:

#### 1. Desktop Application (Current)

After a successful test execution, the recording is available in the test report. The desktop application provides a **"Save Recording"** feature that writes the recording to the correct location on disk (based on the test directory and device classifiers). Once committed, this recording is used for subsequent CI runs.

#### 2. CI Auto-Generation (Planned)

To scale recording coverage without requiring local runs, we are building CI pipeline automation that:

1. Detects successful AI-interpreted test executions (tests without existing recordings)
2. Automatically generates a pull request containing the new device-specific recording
3. Links the PR to the successful CI run for traceability

**Human-in-the-loop approval is required.** A QE engineer must review and approve each recording PR to ensure the recorded tool sequence correctly implements the test intent.

**Recording PR Review Checklist:**
- Verify the recorded steps match the test's intention (not just that it passed)
- Check for extraneous steps that shouldn't be part of the recording
- Confirm the tool calls are appropriate for the device type
- Review the linked CI run to understand the execution context

### Out-of-Sync Recordings

When `trail.yaml` changes (steps added, modified, or removed), existing device recordings may become **out of sync** with the natural language source.

**Current behavior:**
- Out-of-sync recordings are **still allowed to execute**—they do not block test runs
- The desktop application displays indicators when a recording is out of sync (see [Decision 016](2026-01-28-desktop-application.md))
- It is the test author's responsibility to reconcile out-of-sync recordings (re-record or update)

This approach prioritizes test continuity while providing visibility into sync status. However, keeping recordings in sync is important—out-of-sync recordings undermine the value of having natural language as the source of truth.

### Custom Tools for Platform-Specific Logic

Rather than embedding platform conditionals in recordings, platform-specific behavior is encapsulated in **custom Trailblaze tools** (see [Decision 005: Tool Naming Convention](2026-01-14-tool-naming-convention.md)). These tools:

- Can be app-specific (e.g., `myapp_ios_launchAppSignedIn`) or platform-specific
- Encapsulate complex, multi-step actions into a single tool call
- Handle internal conditionals in code, making recordings simpler and more stable

This keeps trail recordings as simple lists of tool invocations while allowing sophisticated platform-specific behavior where needed.

### Recording Naming Convention

Device-specific recordings follow the pattern: `{platform}-{form_factor}.trail.yaml`

| Platform | Form Factor | Filename |
| :--- | :--- | :--- |
| Android | Phone | `android-phone.trail.yaml` |
| Android | Tablet | `android-tablet.trail.yaml` |
| iOS | iPhone | `ios-iphone.trail.yaml` |
| iOS | iPad | `ios-ipad.trail.yaml` |
| Web | (TBD) | `web.trail.yaml` (or `web-chromium.trail.yaml`, etc.) |

> **Why `ios-iphone` instead of `ios-phone`?** Form factor classifiers use terminology natural to each platform. iOS users and engineers refer to "iPhone" and "iPad," not "phone" and "tablet." This makes recordings immediately recognizable.

The classifier system is extensible. Future classifiers could enable recordings like:
- `android-phone-api34.trail.yaml` (API version-specific)
- `ios-ipad-landscape.trail.yaml` (orientation-specific)
- `web-chromium-mobile.trail.yaml` (browser + viewport)

Currently, platform + form factor is sufficient for most test differentiation needs. Web platform classifiers are still being defined and may include browser type and/or viewport size.

## Consequences

**Positive:**

- Clear separation between test intent (`trail.yaml`) and platform-specific execution (recordings)
- Higher CI reliability through deterministic, device-specific replay
- Independent recording updates per platform without affecting others
- Graceful fallback enables testing on new device types without upfront recording
- Natural language source of truth enables external test case management integration

**Negative:**

- Multiple recordings per test increases maintenance surface area
- Recordings may drift out of sync with each other or with `trail.yaml`
- Storage grows linearly with supported device types
- Teams must decide which device types warrant dedicated recordings vs. AI interpretation
