// Credential redaction is what lets a snapshot be published: these are the shapes a live
// credential arrives in, and the look-alikes that must survive.

import { describe, expect, test } from "bun:test";
import { redactDeep, redactText } from "./redact.js";

describe("credentials do not survive redaction", () => {
  test("redaction is by key name, and leaves keys that only look like one alone", () => {
    const out = redactDeep({ accessToken: "x", api_key: "y", inputTokens: 5, inputTokenBreakdown: { tokens: 7 }, pinned: true }) as Record<string, unknown>;
    expect(out).toEqual({ accessToken: "…", api_key: "…", inputTokens: 5, inputTokenBreakdown: { tokens: 7 }, pinned: true });
  });

  test("a jwt is a credential under its own name, not only as a token", () => {
    // Signed URLs commonly carry a bearer as `jwt`, so it can arrive as a field, as a
    // name/value pair, and quoted in a message. No spelling of `token` covers it.
    const out = redactDeep({ jwt: "ey.J0eXAi.sig", extras: [{ key: "X-Amz-Jwt", value: "ey.live.sig" }] });
    expect(JSON.stringify(out)).not.toContain("sig");
    expect(redactText(`could not parse jwt "ey.J0eXAi.sig"`)).toBe('could not parse jwt "…"');
  });

  test("a name/value pair loses its value and keeps its name", () => {
    expect(redactDeep({ extras: [{ key: "password", value: "hunter2" }, { key: "locale", value: "en-US" }] })).toEqual({
      extras: [{ key: "password", value: "…" }, { key: "locale", value: "en-US" }],
    });
  });

  test("prose redaction keeps the sentence and drops only the value", () => {
    expect(redactText("signed in with password: hunter2 and pin=4321")).toBe('signed in with password: "…" and pin="…"');
    expect(redactText("tapped Pay")).toBe("tapped Pay");
  });

  test("a step written in English, with no separator before the value, is redacted too", () => {
    // How a trail step names a login: quoted, with nothing but a space in front of it.
    expect(redactText("Sign in with email 'a@b.com' and password '12345678'")).toBe("Sign in with email 'a@b.com' and password '…'");
    expect(redactText('the passcode is "4321"')).toBe('the passcode is "…"');
    // Unquoted too, when the value could not be an English word.
    expect(redactText("sign in with email a@b.com and password 12345678")).toBe("sign in with email a@b.com and password …");
    // A bare word that reads as part of the sentence is left alone, or every summary that mentions
    // a password would lose its next word.
    expect(redactText("the password is wrong")).toBe("the password is wrong");
    expect(redactText("a token ring network")).toBe("a token ring network");
    expect(redactText("Verify the password field is visible")).toBe("Verify the password field is visible");
  });

  test("a header logged as a line of text loses its whole value, not just its first word", () => {
    // Device logs and request summaries carry headers verbatim, and the credential runs to the end
    // of the field. Taking one word leaves `Authorization: Bearer "…"` with the token still on the
    // line — the redaction that looks like it worked is worse than none.
    expect(redactText("Authorization: Bearer not-a-real-token")).toBe('Authorization: "…"'); // sadscan:disable np.http.2 — a fake header: this test is that bearer values are redacted
    expect(redactText("Cookie: session=live; theme=dark")).toBe('Cookie: "…"');
    expect(redactText('{"authorization":"Bearer live","path":"/v1/pay"}')).toBe('{"authorization":"…","path":"/v1/pay"}');
    // Stops at the field boundary, so a header printed next to its siblings does not eat them.
    expect(redactText("{set-cookie: a=1; b=2, content-type: json}")).toBe('{set-cookie: "…", content-type: json}');
  });

  test("a compound credential name is redacted in text, the same as it is in a field", () => {
    // `isCredentialKey` accepts any key ENDING in a credential word, so `accessToken` is redacted
    // structurally. A text matcher that insisted on a whole word missed the SAME credential in the
    // summary line printed from that field — one file, redacted in one place and not the other.
    expect(redactDeep({ accessToken: "live" })).toEqual({ accessToken: "…" });
    expect(redactText("accessToken=live")).toBe('accessToken="…"');
    expect(redactText("refresh_token: live")).toBe('refresh_token: "…"');
    // A credential word with something after it is still not one: the separator has to come next.
    expect(redactText("inputTokens=5")).toBe("inputTokens=5");
    expect(redactText('"tokens": 7')).toBe('"tokens": 7');
  });
});
