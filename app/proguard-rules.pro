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
# reference. Written structurally rather than as a list of class names: the list
# had already fallen behind — two widget providers added later were missing from
# it — and a stripped component fails silently at runtime rather than at build
# time, which is the worst way for this to go wrong.
-keep class * extends android.appwidget.AppWidgetProvider { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends androidx.activity.ComponentActivity { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.quicksettings.TileService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }
-keep class * extends android.content.ContentProvider { *; }
-keep class * extends android.app.Application { *; }

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
