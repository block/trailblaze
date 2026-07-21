# Android on-device RPC protocol

This module owns the binary contract between the host daemon and Trailblaze's Android on-device
runner. Square Wire generates Kotlin messages from
`src/main/proto/trailblaze/ondevice/rpc/v1/ondevice_rpc.proto` for both JVM and Android builds.

The existing Trailblaze request and response data classes remain the driver API. The codec maps
those models to generated messages only at the transport boundary. Every host-to-runner RPC has a
typed protobuf request and response: screen capture, YAML/tool execution, session draining,
progress subscription, execution status, and active-session listing. Raw screenshot bytes and both
Android tree shapes stay binary. All calls share one multiplexed connection per Android device.
The daemon keeps that connection open across separate CLI commands and releases it with the
persistent device session.

Android log delivery uses a separate persistent device-to-host `/logs-ws` connection so logging
does not depend on an active CLI request. Agent logs lift hierarchy fields into the same typed tree
messages, screenshots remain raw bytes, and traces use binary protobuf fields. The host
acknowledges uploads after they are written. Existing log data classes and JSON files on disk are
unchanged; failed or ambiguous uploads retain the existing Android file fallback.

The host prefers `ws://<forwarded-device-port>/rpc-ws`. If the runner does not expose that route,
the request is sent through the existing HTTP/JSON endpoint instead. This keeps new hosts
compatible with older runner APKs, while older hosts continue to use the unchanged HTTP routes on
new APKs.

`TRAILBLAZE_ANDROID_WIRE_TRANSPORT` is the rollback switch. `auto` (the default) prefers protobuf
WebSockets and falls back to HTTP/JSON only when connection setup fails before a frame is sent.
`protobuf` requires binary host-to-device RPCs. `json` forces those RPCs through HTTP and leaves the
log-upload WebSocket unregistered, which makes an already-running Android client safely use HTTP
without an instrumentation restart. The daemon reads the setting once at startup. File-based
logging remains available in every mode when the server is unavailable or rejects an upload.
