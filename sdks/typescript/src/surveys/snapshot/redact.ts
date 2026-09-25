// Credential redaction for a snapshot, which is built to be published beside a page other people
// open, while a session carries the credentials its trail typed.

/**
 * Object keys whose value is a credential. A tool call carries the account it signed in with, so a
 * session's own records hold a live password in plain text — and a snapshot is built to be published
 * beside a page and handed to someone. Matched on the whole key or its last camelCase/snake_case word,
 * so `accessToken` and `api_key` go and `inputTokens` / `inputTokenBreakdown` stay: those are the
 * model cost numbers, and losing them to an over-eager rule would be its own kind of wrong.
 */
// `jwt` is not a spelling of `token`, and signed URLs commonly carry a bearer under that name — so it
// can arrive here as a request header, a broadcast extra, and a metadata key.
const CREDENTIAL_KEYS = [
  "password", "passwd", "passcode", "passphrase", "secret", "token", "jwt", "credential", "apikey", "otp",
  "pin", "authorization", "cookie", "ssn", "cvv",
];
const REDACTED = "…";

const isCredentialKey = (key: string): boolean => {
  const k = key.toLowerCase().replace(/[^a-z]/g, "");
  return CREDENTIAL_KEYS.some((c) => k === c || k.endsWith(c));
};

/**
 * The value with every credential-keyed field replaced. The shape is kept: a reader can still see
 * that a password was passed, just not what it was. Four shapes carry one, and all four appear in
 * real sessions:
 *   - a field named for it, `{"password": "hunter2"}`
 *   - a name/value pair, `{"key": "password", "value": "hunter2"}` — how broadcast extras and
 *     headers arrive, where the credential's NAME is someone else's value
 *   - prose, `"context": "login credentials -> email: a@b password: hunter2"` — the trail's own
 *     context block, which is a string as far as any walker can tell
 *   - a header printed into a line, `Authorization: Bearer <jwt>` — a whole request logged as text,
 *     where the credential runs to the end of the field rather than ending at the first space
 */
export function redactDeep(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(redactDeep);
  if (typeof v === "string") return redactText(v);
  if (v === null || typeof v !== "object") return v;
  const rec = v as Record<string, unknown>;
  const pair = typeof rec["key"] === "string" && isCredentialKey(rec["key"]);
  const out: Record<string, unknown> = {};
  for (const [k, val] of Object.entries(rec)) {
    out[k] = isCredentialKey(k) || (pair && k === "value") ? REDACTED : redactDeep(val);
  }
  return out;
}

const WORDS = "password|passwd|passcode|passphrase|pin|token|jwt|secret|apikey|api_key|credential|otp|ssn|cvv";
// A name/value pair that has already been flattened into a sentence. Every record carries a summary
// line built by printing its arguments, so the pair a structural walk would catch arrives here as
// text instead — this is the form a tool-call summary puts a login in.
const PAIR = new RegExp(`("key"\\s*:\\s*"(?:${WORDS})"\\s*,\\s*"value"\\s*:\\s*)"[^"]*"`, "gi");
// `Authorization: Bearer <jwt>`, `Cookie: a=1; b=2`. The value is the whole rest of the field rather
// than one word, so INLINE would stop at the first space and leave the credential itself on the
// line. Device logs and request summaries carry headers verbatim, which is how a live bearer token
// reaches a file built to be handed to someone.
const HEADER_WORDS = "authorization|proxy-authorization|proxy_authorization|set-cookie|set_cookie|cookie";
const HEADER = new RegExp(`(?<![A-Za-z0-9_])("?(?:${HEADER_WORDS})"?\\s*[=:]\\s*)("[^"]*"|'[^']*'|[^\\r\\n,}\\]]+)`, "gi");
// `password=hunter2`, `"password":"hunter2"`, `pin: 4321`, `accessToken=…`. The key keeps whatever
// quoting it had, so a summary that was printed from JSON still reads as JSON afterwards.
//
// A compound name counts, matching the structural walk above: `isCredentialKey` accepts any key
// ENDING in a credential word, and a text matcher that refused `accessToken` would redact the same
// credential in a field and miss it in the summary line printed from that field. What still does
// not match is a credential word with something after it — `"tokens":` and `inputTokens=5` need the
// separator to come straight after the word, and it does not.
const INLINE = new RegExp(`("?[A-Za-z0-9_]*(?:${WORDS})"?)(\\s*[=:]\\s*)("[^"]*"|'[^']*'|[^\\s,)}\\]]+)`, "gi");
// `and password '12345678'` — no separator at all, which is how a trail step written in English
// names one. Only a quoted value counts here, so "the password is wrong" keeps its sentence.
const QUOTED = new RegExp(`(?<![A-Za-z0-9_])((?:${WORDS})\\s+(?:is\\s+|of\\s+)?)(['"])[^'"]*\\2`, "gi");
// `and password 12345678` — no separator and no quotes either. A bare word after "password" is
// usually part of the sentence ("the password is wrong"), so this takes one only when it contains a
// digit. A credential of nothing but letters survives that test and this rule: the alternative is
// eating every sentence that mentions a password, which would make the summaries unreadable.
const BARE = new RegExp(`(?<![A-Za-z0-9_])((?:${WORDS})\\s+(?:is\\s+|was\\s+|of\\s+)?)((?=\\S*\\d)[^\\s,;)}\\]'"]{4,})`, "gi");

/** The same for a line of text, where the credential is spelled out rather than held in a field. */
export function redactText(s: string): string {
  return s
    .replace(PAIR, `$1"${REDACTED}"`)
    .replace(HEADER, `$1"${REDACTED}"`)
    .replace(INLINE, (_m, k: string, sep: string) => `${k}${sep}"${REDACTED}"`)
    .replace(QUOTED, (_m, lead: string, q: string) => `${lead}${q}${REDACTED}${q}`)
    .replace(BARE, (_m, lead: string) => `${lead}${REDACTED}`);
}

