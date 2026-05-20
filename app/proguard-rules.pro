# ─── youtubedl-android / yt-dlp wrapper ──────────────────────────────────────
# The library spawns subprocesses and uses reflection — keep everything public.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }

# ─── Kotlin ───────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { *; }

# ─── Coroutines (used internally by the library) ──────────────────────────────
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ─── Android / AndroidX ───────────────────────────────────────────────────────
# Keep View-binding generated classes
-keep class * extends androidx.viewbinding.ViewBinding { *; }

# Keep custom views referenced from XML
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep activity / fragment / application subclasses
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Fragment
-keep public class * extends androidx.fragment.app.Fragment

# ─── JSON (org.json is built into Android — still, keep it clean) ─────────────
-keep class org.json.** { *; }

# ─── Native methods ───────────────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── Serialisable / Parcelable ────────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ─── R8 full-mode aggressive shrink ──────────────────────────────────────────
# Flatten package names → smaller dex
-repackageclasses 'a'
# Allow R8 to widen visibility to enable more inlining
-allowaccessmodification
# Extra optimization passes
-optimizationpasses 5

# ─── Suppress common harmless warnings ───────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn java.lang.instrument.**
-dontwarn com.yausername.**
