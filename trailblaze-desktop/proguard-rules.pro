# ===========================================================================
# Trailblaze ProGuard Shrinking Rules
# ===========================================================================
# These rules configure ProGuard to SHRINK (remove unused classes/methods)
# without obfuscating or optimizing. The Compose Desktop plugin provides
# default rules for Compose, Skiko, and AWT — this file covers everything else.
#
# Enable with: ./gradlew :trailblaze-desktop:packageReleaseUberJarForCurrentOS
# Or via releaseArtifacts: ./gradlew :trailblaze-desktop:releaseArtifacts -Ptrailblaze.proguard=true
# ===========================================================================

-dontobfuscate
-dontoptimize

# Preserve source file names and line numbers for stack traces
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Suppress warnings for optional/missing dependencies that are not on the classpath.
# These are compile-optional classes referenced by libraries but never loaded at runtime.
-ignorewarnings
-dontwarn org.graalvm.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn android.**
-dontwarn dalvik.**
-dontwarn com.oracle.**
-dontwarn org.codehaus.mojo.**
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn org.osgi.**
-dontwarn org.junit.jupiter.**
-dontwarn ch.qos.logback.**
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.net.httpserver.**
-dontwarn sun.security.**
-dontwarn sun.misc.**
-dontwarn java.beans.**
-dontwarn javax.naming.**

# ===========================================================================
# Picocli (CLI framework — annotation-driven, heavy reflection)
# ===========================================================================
-keep class picocli.** { *; }
-keep class * implements picocli.CommandLine$ITypeConverter { *; }
-keepclassmembers class * {
    @picocli.CommandLine$* <fields>;
    @picocli.CommandLine$* <methods>;
}

# ===========================================================================
# Compose Desktop / Skiko (JNI-heavy — native code calls back into Java)
# ===========================================================================
# Since we run ProGuard standalone (not via the Compose plugin), the default
# Compose/Skiko keep rules are NOT applied. Skiko's native rendering layer
# uses JNI extensively to call back into these classes; stripping any method
# causes SIGSEGV in get_method_id at runtime.
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keep class androidx.compose.ui.platform.** { *; }

# ===========================================================================
# quickjs-kt (JNI-heavy — native code calls back into Java)
# ===========================================================================
# The scripted-tool QuickJS engine (com.dokar.quickjs, io.github.dokar3:quickjs-kt)
# ships a native libquickjs.{dylib,so,dll} that reaches back into these Kotlin
# classes by name via JNI FindClass/GetMethodID/GetFieldID — the binding
# callbacks (binding/JsFunction, binding/JsObjectHandle, …) and QuickJs's own
# native-method declarations (invokeJsFunction, defineObject, …) are invoked
# ONLY from the native side, so ProGuard's shrinker sees them as unreachable and
# strips them (68 of 90 classes in a real release build). The native lib then
# dereferences a class graph that no longer has what it expects, and the
# func_data callback path segfaults the JVM (SIGABRT/EXC_BAD_ACCESS on a
# quickjs-tool-engine-N thread) — the exact same failure the Skiko/Netty/JNA
# keeps elsewhere in this file exist to prevent. This is installed-JAR-only because source/dev
# builds don't run ProGuard. See block/trailblaze#194.
-keep class com.dokar.quickjs.** { *; }

# ===========================================================================
# Coil (ServiceLoader-discovered fetchers/decoders)
# ===========================================================================
# Coil's `RealImageLoader` calls `addServiceLoaderComponents()` on EVERY
# ImageLoader construction, which runs `ServiceLoader.load(FetcherServiceLoaderTarget)`.
# `coil-network-ktor3` ships META-INF/services/coil3.util.FetcherServiceLoaderTarget
# naming `KtorNetworkFetcherServiceLoaderTarget` — an `internal` class referenced from
# nothing but that services file, so the shrinker deletes it while
# `-adaptresourcefilecontents META-INF/services/**` leaves the services file naming it
# behind. `ServiceLoader` then throws `ServiceConfigurationError` (an Error, not an
# Exception), which propagates out of the lazy `fetcherFactories` flatMap and takes down
# the ENTIRE fetcher list — including `FileUriFetcher`, so even purely local
# `file:///…` screenshot loads fail. `RealImageLoader.execute` catches Throwable and
# returns an ErrorResult, so the desktop session views just render blank panes with no
# error anywhere. Same shrink-away-what-only-a-string-references shape as the quickjs
# keep above.
#
# Confirmed in the shipped 2026.06.01 Homebrew JAR: 228 of Coil's 336 classes survived,
# `coil3/network/**` was emptied to bare directory entries, and
# META-INF/services/coil3.util.FetcherServiceLoaderTarget still named the deleted class.
#
# The package keep covers Coil's own bundled providers; the two `implements` keeps also
# cover any provider Coil discovers from outside the `coil3` package. The `implements`
# keeps alone would likely retain today's one offender, but the package keep is what makes
# that independent of ProGuard's hierarchy matching — and it costs ~108 classes (~0.1%) in
# a 228 MB artifact, for a failure mode that only ever appears in a published build.
-keep class coil3.** { *; }
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-dontwarn coil3.**

# ===========================================================================
# Kotlin
# ===========================================================================
# ProGuard corrupts Kotlin metadata when processing stdlib classes, causing
# KotlinReflectionInternalError at runtime (e.g., EmptySet.serialVersionUID).
# Keep all Kotlin/KotlinX classes to prevent metadata corruption.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keepclassmembers class * {
    static final long serialVersionUID;
}
-dontnote kotlin.**
-dontnote kotlinx.**

# ===========================================================================
# Kotlin Serialization (accessed via reflection / generated serializers)
# ===========================================================================
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    *;
}

# ===========================================================================
# Jackson (heavy reflection — keep all to avoid subtle runtime breaks)
# ===========================================================================
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}

# ===========================================================================
# Ktor (ServiceLoader-based engine loading and content negotiation)
# ===========================================================================
-keep class io.ktor.server.engine.** { *; }
-keep class io.ktor.server.netty.** { *; }
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.server.websocket.** { *; }
-keep class io.ktor.server.sse.** { *; }
-keep class io.ktor.websocket.** { *; }
-dontwarn io.ktor.**

# ===========================================================================
# gRPC & Protobuf & Wire (ServiceLoader + generated message classes)
# ===========================================================================
-keep class io.grpc.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keep class * extends com.squareup.wire.Message { *; }
-keep class * extends com.squareup.wire.ProtoAdapter { *; }

# ===========================================================================
# Netty (native transport loading, channel pipeline reflection)
# ===========================================================================
-keep class io.netty.** { *; }
-dontwarn io.netty.**

# ===========================================================================
# Maestro (device automation — may use reflection internally)
# ===========================================================================
# The Maven group is "dev.mobile" but the Java packages are top-level:
# maestro.*, device.*, ios.*, xcuitest.*, util.*, hierarchy.*, dadb.*, etc.
# (spread across maestro-client, maestro-ios-driver, maestro-orchestra JARs).
-keep class maestro.** { *; }
-keep class maestro_android.** { *; }
-keep class device.** { *; }
-keep class ios.** { *; }
-keep class xcuitest.** { *; }
-keep class util.** { *; }
-keep class hierarchy.** { *; }
-keep class dadb.** { *; }
-keep class difflib.** { *; }
-keep class pxb.** { *; }
-keep class CdpClient { *; }
-keep class CdpClient$* { *; }
-keep class CdpTarget { *; }
-keep class CdpTarget$* { *; }
# Maestro's XCTest installer resolves iOS driver bundles via resource directory
# lookups (getResource("driver-iPhoneSimulator")). ProGuard strips empty
# directory entries by default, which breaks the lookup.
-keepdirectories

# ===========================================================================
# Playwright (driver process spawning, internal impl classes)
# ===========================================================================
-keep class com.microsoft.playwright.** { *; }

# ===========================================================================
# Koog AI / Agents (reflection for prompt models, tool registration)
# ===========================================================================
-keep class ai.koog.** { *; }

# ===========================================================================
# MCP SDK (JSON-RPC reflection)
# ===========================================================================
-keep class io.modelcontextprotocol.** { *; }

# ===========================================================================
# SLF4J / Log4j (ServiceLoader-based logger binding)
# ===========================================================================
-keep class org.slf4j.** { *; }
-keep class org.apache.logging.log4j.** { *; }

# ===========================================================================
# OkHttp (platform detection via reflection)
# ===========================================================================
-keep class okhttp3.internal.platform.** { *; }
-dontwarn okhttp3.internal.platform.**

# ===========================================================================
# JNA (native library loading via reflection)
# ===========================================================================
-keep class com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }

# ===========================================================================
# Moshi (reflection-based JSON adapters)
# ===========================================================================
-keep class * extends com.squareup.moshi.JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
}

# ===========================================================================
# ServiceLoader pattern (keep all META-INF/services implementations)
# ===========================================================================
-adaptresourcefilecontents META-INF/services/**
-keepnames class * implements java.sql.Driver

# ===========================================================================
# Trailblaze application classes
# ===========================================================================
# Keep all Trailblaze classes — our code is small relative to dependencies,
# and many classes are accessed via Compose reflection, serialization, or
# dynamic tool registration. Safe to tighten later.
-keep class xyz.block.trailblaze.** { *; }
-keep class com.squareup.** { *; }
