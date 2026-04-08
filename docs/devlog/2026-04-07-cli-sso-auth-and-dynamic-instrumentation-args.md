---
title: "CLI-Based SSO/Auth and Dynamic On-Device Instrumentation Args"
type: plan
date: 2026-04-07
---

# Trailblaze Plan 037: CLI-Based SSO/Auth and Dynamic On-Device Instrumentation Args

## Status

- **Part 1 (Dynamic Instrumentation Args):** Complete as of 2026-04-07.
- **Part 2 (CLI-Based SSO/Auth):** Future work — direction and design documented below but not yet implemented.

## Summary

Two related problems: (1) SSO/OAuth for LLM providers is hardcoded to Databricks, and (2) on-device Android LLM calls use hardcoded env var names that don't scale to arbitrary YAML-configured providers. Part 1 introduced a convention-based instrumentation arg scheme for on-device token passing. Part 2 proposes shell-out token commands for auth.

## Context

### Problem 1: SSO diversity

Organization-specific OAuth implementations (e.g., a custom OAuth client and JVM token provider for a corp LLM gateway) are hardcoded and don't generalize. Different organizations use different SSO/OAuth/SAML flows. We can't model every auth flow variant in the framework.

### Problem 2: Hardcoded on-device instrumentation args (resolved by Part 1)

Previously, the host side wrote API keys using hardcoded env var names (`DATABRICKS_TOKEN`, `OPENAI_API_KEY`) and the Android side had a hardcoded `when(provider)` block. This is now resolved — `LlmAuthResolver` reads `auth.env_var` from the YAML config and uses the dynamic `trailblaze.llm.auth.token.<provider_id>` convention. The Android side reads tokens generically via `AndroidLlmClientResolver`.

## Decision

### Part 1: Dynamic Instrumentation Args for On-Device LLM

Replace hardcoded env var names with a convention keyed by provider ID:

```
trailblaze.llm.auth.token.<provider_id> = <token>
```

**Host side writes:**
```kotlin
// In LlmAuthResolver.toInstrumentationArgs()
for ((providerId, auth) in auths) {
    val token = auth.token ?: continue
    put("trailblaze.llm.auth.token.$providerId", token)
}

// For the selected provider, also pass connection info
selectedAuth?.providerConfig?.let { config ->
    config.type?.let { put("trailblaze.llm.provider.type", it.name.lowercase()) }
    config.baseUrl?.let { put("trailblaze.llm.provider.base_url", it) }
    config.chatCompletionsPath?.let { put("trailblaze.llm.provider.chat_completions_path", it) }
}
```

**Android side reads generically:**
```kotlin
fun getTokenForProvider(providerId: String): String? =
    instrumentationArgs.getString("trailblaze.llm.auth.token.$providerId")

fun getProviderType(): String? =
    instrumentationArgs.getString("trailblaze.llm.provider.type")
```

**Android client construction becomes generic** — uses provider type + base URL + path from args instead of a hardcoded `when(provider)` block.

#### Benefits
- No hardcoded env var names on the Android side
- Any YAML-configured provider works on-device automatically
- Provider ID is the single key — fully dynamic
- Host-side env var mapping stays (for CI/desktop), doesn't leak into the device

#### Implementation steps (all complete)
1. ~~Update `LlmAuthResolver.toInstrumentationArgs()` to use `trailblaze.llm.auth.token.<id>` convention~~ — Done
2. ~~Keep writing legacy provider-specific args temporarily for backward compat~~ — Skipped; migrated all consumers directly
3. ~~Update Android token provider to read dynamic args~~ — Done (uses `LlmAuthResolver.resolve()`)
4. ~~Replace hardcoded `when(provider)` in Android test rule with generic client construction~~ — Done. Uses `AndroidLlmClientResolver.resolveModel()` for model resolution and generic `OpenAILLMClient` construction from instrumentation args (`base_url`, `chat_completions_path`).
5. ~~Update `scripts/atf.sh`~~ — Done (uses `trailblaze.llm.auth.token.<id>` convention)
6. ~~Audit other CI scripts for hardcoded arg references~~ — Done (scripts set env vars; `atf.sh` converts to new arg format)
7. ~~Remove legacy arg writes~~ — Complete; no legacy arg writes remain in active code paths

### Part 2: CLI-Based SSO/Auth for LLM Providers (Future Work)

Instead of implementing OAuth flows in the framework, let users specify CLI commands that produce tokens. The framework handles caching and lifecycle; the user's tooling handles auth complexity. **This part is not yet implemented — the design below captures the intended direction.**

#### YAML schema extension

```yaml
providers:
  my-corp-llm:
    type: openai_compatible
    base_url: https://llm.corp.example.com
    auth:
      # Priority: env_var > cached token > token_command
      env_var: CORP_LLM_TOKEN                # CI/manual override (highest priority)
      token_command: "corp-auth get-token"    # shell out, stdout = token
      refresh_command: "corp-auth refresh"    # optional, proactive refresh
      token_cache: ~/.trailblaze/tokens/corp-llm.json
      token_ttl: 3600                         # optional hint if token doesn't self-describe expiry
```

#### Token resolution priority (per provider)

1. **Environment variable** (`env_var`) — always wins, critical for CI
2. **Cached token** — check `token_cache` file, use if not expired
3. **Refresh command** (`refresh_command`) — if cached token is close to expiry
4. **Token command** (`token_command`) — full auth flow (may open browser, prompt user)

#### Token cache format

```json
{
  "token": "eyJ...",
  "expires_at": 1720000000,
  "refresh_token": "optional...",
  "metadata": {}
}
```

The framework reads/writes this file. The `token_command` can also write it directly.

#### Command contract

**`token_command`:**
- Runs when no valid cached token exists
- Does whatever it needs (browser OAuth, CLI login, keychain lookup)
- Stdout = token (bare string) or JSON `{"token": "...", "expires_at": ...}`
- Non-zero exit = auth failure
- May be interactive (opens browser, prompts user)

**`refresh_command`** (optional):
- Runs proactively before token expiry
- Same output contract as `token_command`
- Should be non-interactive (silent refresh)
- Falls back to `token_command` on failure

#### Example: corp LLM gateway migration

Existing organization-specific OAuth code becomes a CLI command behind the same interface:

```yaml
corp-llm:
  type: openai_compatible
  base_url: https://llm-gateway.corp.example.com
  auth:
    env_var: CORP_LLM_TOKEN
    token_command: "trailblaze auth corp-llm"
    token_cache: ~/.trailblaze/tokens/corp-llm.json
```

The custom OAuth client moves into a `trailblaze auth <provider>` command. The JVM token provider simplifies to just reading YAML config.

#### Implementation steps (TODO)

1. Extend `LlmAuthConfig` with `token_command`, `refresh_command`, `token_cache`, `token_ttl` fields
2. Generalize token cache into a generic implementation (read/write JSON token files, expiry checking)
3. Create `CommandTokenProvider` (execute commands via `ProcessBuilder`, parse stdout, write to cache)
4. Wire into `LlmAuthResolver` (env_var > cache > command priority chain)
5. Migrate existing OAuth flows behind `trailblaze auth <provider>` CLI commands
6. Desktop app picks this up automatically via existing JVM path

## What This Enables

- Any SSO provider (Okta, Azure AD, Google Workspace, custom SAML)
- Corporate proxy auth
- Hardware token / MFA flows
- Keychain-based token retrieval (macOS Keychain, Linux secret-service)
- Cloud provider CLI auth (`gcloud auth print-access-token`, `aws sso get-role-credentials`)
- On-device LLM calls with any YAML-configured provider
- No hardcoded provider knowledge on the Android side

## Relationship to Existing Code

| Component | Part 1 Status (Done) | Part 2 Status (Future) |
|-----------|---------------------|----------------------|
| `LlmAuthResolver` | Uses `trailblaze.llm.auth.token.<id>` convention | Will gain `CommandTokenProvider` path |
| Android token provider | Reads dynamic args via `LlmAuthResolver.resolve()` | No change needed |
| Android test rule | Generic client construction via `AndroidLlmClientResolver` + instrumentation args | No change needed |
| Custom OAuth client | Unchanged | Will move behind `trailblaze auth <provider>` CLI command |
| Token cache | Unchanged | Will generalize into `GenericTokenCache` |
| Custom JVM token provider | Unchanged | Will simplify to reading YAML config |

## Open Questions (Part 2)

1. **Command output format**: Support both bare string and JSON — try JSON parse, fall back to bare string + `token_ttl`.
2. **Interactive commands**: `token_command` may open a browser. Framework should allow TTY passthrough, not capture stderr.
3. **Timeout**: Some OAuth flows wait up to 5 minutes. Should be configurable per provider.
4. **Security**: Token cache files should have restricted permissions (600).
5. **Auto-refresh scheduling**: Some flows check every 2 minutes. Generic version should support configurable interval.

## Related Documents

- [030: LLM Provider Configuration](2026-02-04-llm-provider-configuration.md) — YAML schema for providers
- [036: Workspace Config Resolution](2026-04-07-trailblaze-yaml-config-resolution.md) — Where config files live
- Current implementation: `LlmAuthResolver.kt`
