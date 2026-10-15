package xyz.block.trailblaze.util

import java.io.File

/**
 * This file as a `file:` URL that terminals, browsers and [java.net.URI] all accept:
 * `file:///Users/me/report.html`, or `file:///D:/a/report.html` on Windows, with spaces and other
 * reserved characters percent-encoded. Gluing `file://` onto an absolute path gives
 * `file://D:\a\report.html` on Windows, which reads `D:` as a host.
 */
fun File.fileUrl(): String = absoluteFile.toPath().toUri().toString()
