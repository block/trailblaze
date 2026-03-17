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
# Kotlin
# ===========================================================================
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
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
-keep class dev.mobile.maestro.** { *; }

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
