# scrcpy server (third-party)

`scrcpy-server-v4.1` is the unmodified server from the
[scrcpy v4.1 release](https://github.com/Genymobile/scrcpy/releases/tag/v4.1),
downloaded from
<https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-server-v4.1>.

SHA-256: `deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae`
(the digest GitHub publishes for that asset; `DeviceFrameTimesTest` checks it).

Trailblaze runs it on Android devices to stream the screen for session recordings, because it
reports when the device drew each frame and `screenrecord` does not.

Copyright (C) 2018 Genymobile
Copyright (C) 2018-2026 Romain Vimont

Licensed under the Apache License, Version 2.0; see `LICENSE` in this directory.

## Upgrading

The server only accepts a client of its own exact version, and its wire protocol changes between
releases. To upgrade, replace the file, then update `VERSION`, `SHA256` and `RESOURCE` in
`AdbScrcpyProducerFactory` and this README together, and re-check the frame header layout that
`ScrcpyAnnexBInputStream` parses against the new release's `doc/develop.md`.
