"""mitmproxy addon: map HTTP(S) flows to Trailblaze NetworkEvent NDJSON.

Run via `mitmdump -s mitmproxy_netcapture.py`. Reads two env vars:

  TRAILBLAZE_SESSION_ID   the session id stamped onto every event
  TRAILBLAZE_SESSION_DIR  directory to write `network.ndjson` (+ `bodies/`) into

Each flow produces a REQUEST_START line (on request) and a RESPONSE_END line (on
response), sharing the flow id, plus a FAILED line on transport error. The output
schema, redaction, and body rules deliberately mirror the web capture path
(`WebNetworkCapture`) so events render identically in the Network tab / HTML report:

  - request `Authorization` and response `Set-Cookie` header values -> "***REDACTED***"
  - bodies <= 4 KB and "likely text" are inlined; larger/binary go to `bodies/`;
    bodies over 1 MB are truncated on disk with `truncated=true` (original size kept)
  - null / default fields are omitted from the JSON (matches the Kotlin serializer's
    encodeDefaults=false / explicitNulls=false), and the reader decodes leniently

The pure mapping helpers (`redact_headers`, `decide_body`, `build_event`) take plain
values and no mitmproxy imports, so they're unit-tested directly in
`test_mitmproxy_netcapture.py`. Only the thin mitmproxy hook layer at the bottom
touches the `flow` object.
"""

from __future__ import annotations

import json
import os
import sys
import threading
from typing import Any, Optional

REDACTED_VALUE = "***REDACTED***"
SOURCE_ANDROID = "ANDROID"

# Keep these in sync with WebNetworkCapture: only these header values are scrubbed.
SENSITIVE_REQUEST_HEADERS = {"authorization"}
SENSITIVE_RESPONSE_HEADERS = {"set-cookie"}

INLINE_BODY_MAX_BYTES = 4 * 1024
BODY_TRUNCATE_BYTES = 1024 * 1024

# Content types we treat as "likely text" and therefore safe to inline as a string.
_TEXT_CONTENT_HINTS = (
    "text/",
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-www-form-urlencoded",
    "+json",
    "+xml",
)


def redact_headers(
    headers: dict[str, str] | None,
    sensitive_lowercased: set[str],
) -> Optional[dict[str, str]]:
    """Return a copy of `headers` with sensitive values replaced. None stays None."""
    if not headers:
        return None
    out: dict[str, str] = {}
    for key, value in headers.items():
        out[key] = REDACTED_VALUE if key.lower() in sensitive_lowercased else value
    return out


def _is_text_content(content_type: Optional[str]) -> bool:
    if not content_type:
        return False
    ct = content_type.lower()
    return any(hint in ct for hint in _TEXT_CONTENT_HINTS)


def decide_body(
    body: Optional[bytes],
    content_type: Optional[str],
    event_id: str,
    kind: str,
) -> tuple[Optional[dict[str, Any]], Optional[tuple[str, bytes]]]:
    """Decide how to represent a captured body.

    Returns (body_ref_dict, sidecar) where:
      - body_ref_dict is the BodyRef JSON object (or None for an empty body)
      - sidecar is (relative_path, bytes_to_write) when the body must be written to
        disk, else None. The caller performs the write so this function stays pure.
    """
    if not body:
        return None, None

    size = len(body)
    if size <= INLINE_BODY_MAX_BYTES and _is_text_content(content_type):
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError:
            text = None
        if text is not None:
            ref: dict[str, Any] = {"sizeBytes": size, "inlineText": text}
            if content_type:
                ref["contentType"] = content_type
            return ref, None

    # Sidecar path: write (possibly truncated) bytes to bodies/<kind>_<id>.bin. event_id is a
    # mitmproxy flow id (a UUID) in practice; sanitize defensively so an unexpected value can't
    # escape the bodies/ directory.
    truncated = size > BODY_TRUNCATE_BYTES
    payload = body[:BODY_TRUNCATE_BYTES] if truncated else body
    safe_id = "".join(c if (c.isalnum() or c in "-_") else "_" for c in event_id) or "unknown"
    rel_path = f"bodies/{kind}_{safe_id}.bin"
    ref = {"sizeBytes": size, "blobPath": rel_path}
    if content_type:
        ref["contentType"] = content_type
    if truncated:
        ref["truncated"] = True
    return ref, (rel_path, payload)


def build_event(
    *,
    event_id: str,
    session_id: str,
    timestamp_ms: int,
    phase: str,
    method: str,
    url: str,
    url_path: str,
    status_code: Optional[int] = None,
    duration_ms: Optional[int] = None,
    request_headers: Optional[dict[str, str]] = None,
    response_headers: Optional[dict[str, str]] = None,
    request_body_ref: Optional[dict[str, Any]] = None,
    response_body_ref: Optional[dict[str, Any]] = None,
) -> dict[str, Any]:
    """Assemble a NetworkEvent dict, omitting null fields (matches the Kotlin model)."""
    event: dict[str, Any] = {
        "id": event_id,
        "sessionId": session_id,
        "timestampMs": timestamp_ms,
        "phase": phase,
        "method": method,
        "url": url,
        "urlPath": url_path,
        "source": SOURCE_ANDROID,
    }
    if status_code is not None:
        event["statusCode"] = status_code
    if duration_ms is not None:
        event["durationMs"] = duration_ms
    if request_headers:
        event["requestHeaders"] = request_headers
    if response_headers:
        event["responseHeaders"] = response_headers
    if request_body_ref:
        event["requestBodyRef"] = request_body_ref
    if response_body_ref:
        event["responseBodyRef"] = response_body_ref
    return event


# --------------------------------------------------------------------------------------
# mitmproxy hook layer (the only part that imports / touches the flow object)
# --------------------------------------------------------------------------------------


class _Sink:
    """Appends NDJSON lines to <sessionDir>/network.ndjson and writes body sidecars."""

    def __init__(self, session_id: str, session_dir: str) -> None:
        self.session_id = session_id
        self.session_dir = session_dir
        self.ndjson_path = os.path.join(session_dir, "network.ndjson")
        self.bodies_dir = os.path.join(session_dir, "bodies")
        self._lock = threading.Lock()
        os.makedirs(session_dir, exist_ok=True)

    def write(self, event: dict[str, Any], sidecars: list[tuple[str, bytes]]) -> None:
        with self._lock:
            session_root = os.path.realpath(self.session_dir)
            for rel_path, payload in sidecars:
                abs_path = os.path.realpath(os.path.join(self.session_dir, rel_path))
                # Defense in depth: rel_path is built from a sanitized event_id (see
                # decide_body), but never let a sidecar escape the session dir even if a
                # future caller passes an unsanitized path.
                if abs_path != session_root and not abs_path.startswith(session_root + os.sep):
                    print(f"[mitm-capture] refusing to write sidecar outside session dir: {rel_path}", file=sys.stderr)
                    continue
                os.makedirs(os.path.dirname(abs_path), exist_ok=True)
                with open(abs_path, "wb") as fh:
                    fh.write(payload)
            with open(self.ndjson_path, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(event, separators=(",", ":")) + "\n")


def _headers_to_dict(headers: Any) -> dict[str, str]:
    # mitmproxy Headers is a multidict; collapse to a plain dict (last value wins).
    return {k: v for k, v in headers.items()}


def _epoch_ms(ts: Optional[float]) -> int:
    return int((ts or 0.0) * 1000)


class TrailblazeNetworkCapture:
    def __init__(self) -> None:
        session_id = os.environ.get("TRAILBLAZE_SESSION_ID", "unknown-session")
        session_dir = os.environ.get("TRAILBLAZE_SESSION_DIR")
        if not session_dir:
            raise RuntimeError("TRAILBLAZE_SESSION_DIR env var is required")
        self.sink = _Sink(session_id, session_dir)
        self.session_id = session_id

    def request(self, flow: Any) -> None:
        req = flow.request
        body_ref, sidecar = decide_body(
            req.raw_content, req.headers.get("content-type"), flow.id, "req"
        )
        event = build_event(
            event_id=flow.id,
            session_id=self.session_id,
            timestamp_ms=_epoch_ms(req.timestamp_start),
            phase="REQUEST_START",
            method=req.method,
            url=req.url,
            url_path=req.path.split("?", 1)[0],
            request_headers=redact_headers(
                _headers_to_dict(req.headers), SENSITIVE_REQUEST_HEADERS
            ),
            request_body_ref=body_ref,
        )
        self.sink.write(event, [sidecar] if sidecar else [])

    def response(self, flow: Any) -> None:
        req, resp = flow.request, flow.response
        body_ref, sidecar = decide_body(
            resp.raw_content, resp.headers.get("content-type"), flow.id, "resp"
        )
        duration_ms = None
        if req.timestamp_start and resp.timestamp_end:
            duration_ms = int((resp.timestamp_end - req.timestamp_start) * 1000)
        event = build_event(
            event_id=flow.id,
            session_id=self.session_id,
            timestamp_ms=_epoch_ms(resp.timestamp_end),
            phase="RESPONSE_END",
            method=req.method,
            url=req.url,
            url_path=req.path.split("?", 1)[0],
            status_code=resp.status_code,
            duration_ms=duration_ms,
            response_headers=redact_headers(
                _headers_to_dict(resp.headers), SENSITIVE_RESPONSE_HEADERS
            ),
            response_body_ref=body_ref,
        )
        self.sink.write(event, [sidecar] if sidecar else [])

    def error(self, flow: Any) -> None:
        req = flow.request
        if req is None:
            return
        event = build_event(
            event_id=flow.id,
            session_id=self.session_id,
            timestamp_ms=_epoch_ms(req.timestamp_start),
            phase="FAILED",
            method=req.method,
            url=req.url,
            url_path=req.path.split("?", 1)[0],
        )
        self.sink.write(event, [])


addons = [TrailblazeNetworkCapture()] if os.environ.get("TRAILBLAZE_SESSION_DIR") else []
