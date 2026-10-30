# ─────────────────────────────────────────────────────────────────────────────
# R8 POLICY (2026-10-30): minification is OFF by default (isMinifyEnabled = false in
# app/build.gradle.kts) and must stay off unless development/r8-minification.md is
# satisfied. The native bridges (libagora_llama.so, libagora_cactus.so) resolve Java
# members by name through JNI; R8 renames them and the app aborts (SIGABRT) on the
# first model load. A release build with R8 on crashed on every phone with:
#   NoSuchFieldError: no "Z" field "useMmap" in LlamaEngineOptions (fields a/b/c...)
# The rules below are the defensive inventory for the case where shrinking is ever
# deliberately re-enabled; they are inert while minify is off.
# ─────────────────────────────────────────────────────────────────────────────

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# JSch (SSH/SFTP)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# JLaTeXMath creates environment macro handlers and resolves executeMacro by name.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}

# Compose
-dontwarn androidx.compose.**

# JNI (llama): native code reads these classes' fields and constructors, and calls the
# callback methods, by name.
-keep class com.newoether.agora.api.NativeChatCallback { *; }
-keep class * implements com.newoether.agora.api.NativeChatCallback { *; }
# JNI (cactus): the native bridge resolves onToken via GetMethodID by exact name.
-keep interface com.newoether.agora.api.CactusTokenStream { *; }
-keep class * implements com.newoether.agora.api.CactusTokenStream { *; }
-keep class com.newoether.agora.api.ChatTemplateToolCall { *; }
-keep class com.newoether.agora.api.ChatTemplateMessage { *; }
-keep class com.newoether.agora.api.ChatTemplateTool { *; }
# JNI (llama): read_engine_options() reads ALL of these fields by exact name via
# GetFieldID on every model load. This was the class missing a keep rule in the
# 2026-10-30 crash: R8 renamed flashAttention/useMmap/... to a/b/c... and the app
# aborted with SIGABRT while loading the first model.
-keep class com.newoether.agora.api.LlamaEngineOptions { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateRequest { *; }
-keep class com.newoether.agora.api.ChatTemplateGrammarTrigger { *; }
-keep class com.newoether.agora.api.LlamaChatTemplateResult { *; }
-keepclasseswithmembernames class com.newoether.agora.** { native <methods>; }
# Reflection: flavor sandbox factories (AppContainer) and the screenshot fixture (MainActivity).
-keep class com.newoether.agora.sandbox.FdroidSandboxManagerFactory { <init>(...); }
-keep class com.newoether.agora.sandbox.PlaySandboxManagerFactory { <init>(...); }
-keep class com.newoether.agora.screenshot.ScreenshotFixture { *; }# Ktor server: JVM-only debug detection (DevelopmentMode), absent on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
