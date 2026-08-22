# Lain — R8 rules.
#
# Only what reflection genuinely needs. Everything not named here is fair game for
# shrinking and obfuscation, which is the point: a smaller dex loads faster and
# costs less to keep resident.

# ---------------------------------------------------------------- serialization
# kotlinx.serialization resolves generated serializers reflectively from the class
# they belong to. Without these, every @Serializable model (ScheduledTask, Reminder,
# Note, the model catalogue) silently fails to parse at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lain.assistant.**$$serializer { *; }
-keepclassmembers class com.lain.assistant.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.lain.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ------------------------------------------------------------------------- Room
# Entities and DAOs are bound by generated code that looks them up by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ----------------------------------------------------------------------- OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --------------------------------------------------------- Android entry points
# The framework instantiates these by name from the manifest, so R8 cannot see the
# reference. A stripped AccessibilityService is the worst possible failure here:
# the app installs, and screen control simply never works.
-keep class com.lain.assistant.automation.LainAccessibilityService { *; }
-keep class com.lain.assistant.automation.LainNotificationListener { *; }
-keep class com.lain.assistant.automation.ScheduledTaskReceiver { *; }
-keep class com.lain.assistant.automation.ReminderReceiver { *; }
-keep class com.lain.assistant.automation.BootReceiver { *; }
-keep class com.lain.assistant.automation.LainWidgetProvider { *; }
-keep class com.lain.assistant.automation.VoiceInputTileService { *; }
-keep class com.lain.assistant.automation.WakeWordService { *; }
-keep class com.lain.assistant.automation.OverlayBubbleService { *; }
-keep class com.lain.assistant.automation.ScreenCaptureService { *; }
-keep class com.lain.assistant.agent.AgentForegroundService { *; }
-keep class com.lain.assistant.ui.alarm.AlarmActivity { *; }

# Keep line numbers so a crash report from a user is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------- Tink / security
# androidx.security-crypto pulls in Tink, which references build-time-only
# annotations (ErrorProne, Checker Framework, javax.annotation) that are not on the
# runtime classpath. They are compile-time metadata; ignoring them is correct
# rather than a workaround.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.api.client.http.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn com.google.j2objc.annotations.**
# Tink's KeysDownloader has an optional joda-time code path we never reach.
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
