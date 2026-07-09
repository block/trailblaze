"""Unit tests for the pure mapping helpers in mitmproxy_netcapture.

Run with plain python3 (no mitmproxy needed — these test only the pure functions):

    python3 trailblaze-host/src/main/resources/networkcapture/test_mitmproxy_netcapture.py

Asserts the observable contract: redaction, body inline/sidecar/truncation decisions, and
the assembled NetworkEvent shape (null fields omitted). Mirrors the WebNetworkCapture rules.
"""

import mitmproxy_netcapture as m


def _check(name, cond):
    if not cond:
        raise AssertionError(f"FAILED: {name}")
    print(f"ok - {name}")


def test_redaction():
    out = m.redact_headers(
        {"Authorization": "Bearer secret", "Accept": "application/json"},
        m.SENSITIVE_REQUEST_HEADERS,
    )
    _check("authorization value redacted", out["Authorization"] == m.REDACTED_VALUE)
    _check("non-sensitive header preserved", out["Accept"] == "application/json")
    _check("case-insensitive key match", m.redact_headers({"authorization": "x"}, m.SENSITIVE_REQUEST_HEADERS)["authorization"] == m.REDACTED_VALUE)
    _check("set-cookie redacted on response set", m.redact_headers({"Set-Cookie": "a=b"}, m.SENSITIVE_RESPONSE_HEADERS)["Set-Cookie"] == m.REDACTED_VALUE)
    _check("none headers stay none", m.redact_headers(None, m.SENSITIVE_REQUEST_HEADERS) is None)
    _check("empty headers stay none", m.redact_headers({}, m.SENSITIVE_REQUEST_HEADERS) is None)


def test_body_inline_small_text():
    ref, sidecar = m.decide_body(b'{"a":1}', "application/json", "id1", "req")
    _check("small json inlined", ref["inlineText"] == '{"a":1}')
    _check("inline carries size", ref["sizeBytes"] == 7)
    _check("inline content type", ref["contentType"] == "application/json")
    _check("inline has no sidecar", sidecar is None)
    _check("inline not truncated", "truncated" not in ref)


def test_body_binary_goes_to_sidecar():
    body = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    ref, sidecar = m.decide_body(body, "image/png", "id2", "resp")
    _check("binary not inlined", "inlineText" not in ref)
    _check("binary uses blobPath", ref["blobPath"] == "bodies/resp_id2.bin")
    _check("binary sidecar returned", sidecar == ("bodies/resp_id2.bin", body))
    _check("binary size preserved", ref["sizeBytes"] == len(body))


def test_large_text_goes_to_sidecar():
    # Over the inline cap but text -> sidecar (not inlined), not truncated under 1 MB.
    body = b"x" * (m.INLINE_BODY_MAX_BYTES + 1)
    ref, sidecar = m.decide_body(body, "text/plain", "id3", "req")
    _check("over-cap text not inlined", "inlineText" not in ref)
    _check("over-cap text sidecar'd", ref["blobPath"] == "bodies/req_id3.bin")
    _check("over-cap size preserved", ref["sizeBytes"] == m.INLINE_BODY_MAX_BYTES + 1)


def test_truncation_over_1mb():
    body = b"y" * (m.BODY_TRUNCATE_BYTES + 50)
    ref, sidecar = m.decide_body(body, "application/octet-stream", "id4", "resp")
    _check("oversize marked truncated", ref["truncated"] is True)
    _check("oversize keeps original size", ref["sizeBytes"] == m.BODY_TRUNCATE_BYTES + 50)
    _check("oversize sidecar truncated to cap", len(sidecar[1]) == m.BODY_TRUNCATE_BYTES)


def test_empty_body():
    ref, sidecar = m.decide_body(b"", "application/json", "id5", "req")
    _check("empty body -> no ref", ref is None and sidecar is None)
    ref2, _ = m.decide_body(None, None, "id6", "req")
    _check("none body -> no ref", ref2 is None)


def test_build_event_omits_nulls():
    e = m.build_event(
        event_id="abc", session_id="s1", timestamp_ms=1700, phase="REQUEST_START",
        method="GET", url="https://ex.com/p?q=1", url_path="/p",
    )
    _check("source is ANDROID", e["source"] == m.SOURCE_ANDROID)
    _check("required fields present", e["id"] == "abc" and e["method"] == "GET")
    _check("null statusCode omitted", "statusCode" not in e)
    _check("null durationMs omitted", "durationMs" not in e)
    _check("null bodies omitted", "requestBodyRef" not in e and "responseBodyRef" not in e)


def test_build_event_response_fields():
    e = m.build_event(
        event_id="abc", session_id="s1", timestamp_ms=1800, phase="RESPONSE_END",
        method="POST", url="https://ex.com/v1", url_path="/v1",
        status_code=204, duration_ms=90,
        response_headers={"Set-Cookie": m.REDACTED_VALUE},
    )
    _check("status present", e["statusCode"] == 204)
    _check("duration present", e["durationMs"] == 90)
    _check("response headers present", e["responseHeaders"]["Set-Cookie"] == m.REDACTED_VALUE)


if __name__ == "__main__":
    test_redaction()
    test_body_inline_small_text()
    test_body_binary_goes_to_sidecar()
    test_large_text_goes_to_sidecar()
    test_truncation_over_1mb()
    test_empty_body()
    test_build_event_omits_nulls()
    test_build_event_response_fields()
    print("\nAll mitmproxy_netcapture mapping tests passed.")
