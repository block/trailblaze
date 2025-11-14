package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.http.ReverseProxyHeaders
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.util.*

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object ReverseProxyEndpoint {

    val client = HttpClient(OkHttp) {
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    // Log the request and response details
                    println("ReverseProxy: $message")
                }
            }
            level = LogLevel.NONE
        }
    }

    fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
        // Initialize HarCollector with the LogsRepo for session management
        HarCollector.initialize(logsRepo)

        route("/reverse-proxy") {
            handle {
                val startTime = Clock.System.now()
                val callBytes = call.receiveChannel().toByteArray()
                val httpMethod = call.request.httpMethod
                val callHeaders = call.request.headers
                val targetUrl = callHeaders[ReverseProxyHeaders.ORIGINAL_URI]
                    ?: error("No header value for ${ReverseProxyHeaders.ORIGINAL_URI}")

                val beforeRequestTime = Clock.System.now()
                val proxiedResponse = client.request(targetUrl) {
                    this.method = httpMethod
                    this.headers.appendAll(callHeaders)
                    this.headers.remove(HttpHeaders.Host)
                    this.headers.remove(HttpHeaders.ContentLength)
                    this.headers.remove(ReverseProxyHeaders.ORIGINAL_URI)
                    if (httpMethod != HttpMethod.Get && httpMethod != HttpMethod.Head) {
                        setBody(callBytes)
                    }
                }
                val afterRequestTime = Clock.System.now()

                // Copy status, headers, and body to the response
                proxiedResponse.headers.forEach { key, values ->
                    if (!HttpHeaders.isUnsafe(key) && key != HttpHeaders.ContentLength && key != HttpHeaders.ContentType) {
                        values.forEach { call.response.headers.append(key, it) }
                    }
                }
                val proxiedRequestResponseBytes = proxiedResponse.bodyAsChannel().toByteArray()

                // Determine the content type - handle cases where it might be missing
                val originalContentType = proxiedResponse.contentType()
                val responseContentType = when {
                    // If content type is already set, use it
                    originalContentType != null -> originalContentType

                    // If response body looks like JSON, set JSON content type
                    proxiedRequestResponseBytes.isNotEmpty() &&
                            isLikelyJsonContent(proxiedRequestResponseBytes) -> ContentType.Application.Json

                    // If requesting JSON (Accept header) and no content type set, assume JSON
                    callHeaders[HttpHeaders.Accept]?.let { acc ->
                        val lower = acc.lowercase()
                        lower.contains("application/json") || lower.contains("+json") || lower.contains("json")
                    } == true -> ContentType.Application.Json

                    // Default fallback
                    else -> ContentType.Application.OctetStream
                }

                // If the client expects JSON but upstream returned a non‑JSON error body, wrap it into a JSON error envelope
                val acceptJson: Boolean = callHeaders[HttpHeaders.Accept]?.let { acc ->
                    val lower = acc.lowercase()
                    lower.contains("application/json") || lower.contains("+json") || lower.contains("json")
                } == true
                val isResponseJson: Boolean = (originalContentType?.toString()?.lowercase()?.contains("json") == true) ||
                        isLikelyJsonContent(proxiedRequestResponseBytes)

                var finalBytes = proxiedRequestResponseBytes
                var finalContentType = responseContentType
                if (proxiedResponse.status.value >= 400 && acceptJson && !isResponseJson) {
                    val text = try {
                        val s = String(proxiedRequestResponseBytes, Charsets.UTF_8).trim()
                        if (s.isNotEmpty()) s else "Upstream error with empty body"
                    } catch (e: Exception) {
                        "Upstream error (non-text body)"
                    }
                    val jsonError = buildJsonObject { put("error", JsonPrimitive(text)) }.toString()
                    finalBytes = jsonError.toByteArray(Charsets.UTF_8)
                    finalContentType = ContentType.Application.Json
                }

                // Calculate timings
                val totalTime = (afterRequestTime - startTime).inWholeMilliseconds.toDouble()
                val requestTime = (afterRequestTime - beforeRequestTime).inWholeMilliseconds.toDouble()

                // Create a HAR entry with essential information
                val harEntry = buildJsonObject {
                    put("startedDateTime", JsonPrimitive(startTime.toString()))
                    put("time", JsonPrimitive(totalTime))
                    put(
                        "request",
                        buildJsonObject {
                            put("method", JsonPrimitive(httpMethod.value))
                            put("url", JsonPrimitive(targetUrl))
                            put("httpVersion", JsonPrimitive("HTTP/1.1"))
                            put(
                                "headers",
                                buildJsonArray {
                                    callHeaders.entries()
                                        .filter { it.key != ReverseProxyHeaders.ORIGINAL_URI }
                                        .forEach { header ->
                                            add(
                                                buildJsonObject {
                                                    put("name", JsonPrimitive(header.key))
                                                    put(
                                                        "value",
                                                        JsonPrimitive(
                                                            header.value.joinToString(", ")
                                                        )
                                                    )
                                                },
                                            )
                                        }
                                },
                            )
                            put(
                                "queryString",
                                buildJsonArray {
                                    Url(targetUrl).parameters.entries().forEach { param ->
                                        add(
                                            buildJsonObject {
                                                put("name", JsonPrimitive(param.key))
                                                put(
                                                    "value",
                                                    JsonPrimitive(param.value.joinToString(", "))
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                            put("headersSize", JsonPrimitive(calculateHeadersSize(callHeaders)))
                            put("bodySize", JsonPrimitive(callBytes.size))
                            if (callBytes.isNotEmpty() &&
                                (httpMethod == HttpMethod.Post || httpMethod == HttpMethod.Put || httpMethod == HttpMethod.Patch)
                            ) {
                                val requestContentType =
                                    callHeaders[HttpHeaders.ContentType]
                                        ?: "application/octet-stream"
                                val isRequestTextContent =
                                    requestContentType.startsWith("text/") ||
                                            requestContentType.contains("json") ||
                                            requestContentType.contains("xml") ||
                                            requestContentType.contains("form")
                                put(
                                    "postData",
                                    buildJsonObject {
                                        put("mimeType", JsonPrimitive(requestContentType))
                                        put(
                                            "text",
                                            JsonPrimitive(
                                                if (isRequestTextContent) {
                                                    String(callBytes, Charsets.UTF_8)
                                                } else {
                                                    Base64.getEncoder()
                                                        .encodeToString(callBytes)
                                                },
                                            ),
                                        )
                                        if (!isRequestTextContent) {
                                            put("encoding", JsonPrimitive("base64"))
                                        }
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "response",
                        buildJsonObject {
                            put("status", JsonPrimitive(proxiedResponse.status.value))
                            put("statusText", JsonPrimitive(proxiedResponse.status.description))
                            put("httpVersion", JsonPrimitive("HTTP/1.1"))
                            put(
                                "headers",
                                buildJsonArray {
                                    proxiedResponse.headers.entries().forEach { header ->
                                        add(
                                            buildJsonObject {
                                                put("name", JsonPrimitive(header.key))
                                                put(
                                                    "value",
                                                    JsonPrimitive(header.value.joinToString(", "))
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                            put(
                                "content",
                                buildJsonObject {
                                    val responseContentTypeString = finalContentType.toString()
                                    val isTextContent =
                                        responseContentTypeString.startsWith("text/") ||
                                                responseContentTypeString.contains("json") ||
                                                responseContentTypeString.contains("xml") ||
                                                responseContentTypeString.contains("javascript")
                                    put("size", JsonPrimitive(finalBytes.size))
                                    put("mimeType", JsonPrimitive(responseContentTypeString))
                                    put(
                                        "text",
                                        JsonPrimitive(
                                            if (isTextContent) {
                                                String(
                                                    finalBytes,
                                                    Charsets.UTF_8
                                                )
                                            } else {
                                                Base64.getEncoder()
                                                    .encodeToString(finalBytes)
                                            },
                                        ),
                                    )
                                    if (!isTextContent) {
                                        put("encoding", JsonPrimitive("base64"))
                                    }
                                },
                            )
                            put(
                                "headersSize",
                                JsonPrimitive(calculateHeadersSize(proxiedResponse.headers))
                            )
                            put("bodySize", JsonPrimitive(finalBytes.size))
                        },
                    )
                    put("cache", buildJsonObject {})
                    put(
                        "timings",
                        buildJsonObject {
                            put("blocked", JsonPrimitive(-1))
                            put("dns", JsonPrimitive(-1))
                            put("connect", JsonPrimitive(-1))
                            put("send", JsonPrimitive(1))
                            put("wait", JsonPrimitive(requestTime - 1))
                            put("receive", JsonPrimitive(1))
                            put("ssl", JsonPrimitive(-1))
                        },
                    )
                }

                // Add HAR entry to the collector - this will automatically write to file
                HarCollector.addEntry(harEntry)

                // Log summary information
                println("HAR Entry collected for request to: $targetUrl")
                println("Request method: ${httpMethod.value}, Response status: ${proxiedResponse.status.value}")
                println("Total HAR entries collected for current session: ${HarCollector.getEntryCount()}")
                println("HAR file path: ${HarCollector.getHarFilePath()}")

                call.respond(
                    ByteArrayContent(
                        bytes = finalBytes,
                        contentType = finalContentType,
                        status = proxiedResponse.status,
                    ),
                )
            }
        }
    }

    private fun isLikelyJsonContent(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false

        return try {
            val jsonString = String(bytes, Charsets.UTF_8).trim()
            (jsonString.startsWith("{") && jsonString.endsWith("}")) ||
                    (jsonString.startsWith("[") && jsonString.endsWith("]"))
        } catch (e: Exception) {
            false
        }
    }

    private fun calculateHeadersSize(headers: Headers): Long = headers.entries().sumOf { entry ->
        "${entry.key}: ${entry.value.joinToString(", ")}\r\n".toByteArray().size.toLong()
    }
}
