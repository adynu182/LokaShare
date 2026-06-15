# =============================================================
# ProGuard Rules — LokaShare
# =============================================================

# ─── Firebase / Google Play Services ─────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ─── Firestore — jangan obfuscate model data ─────────────────
# Tambahkan package data class kamu di sini jika ada
-keepclassmembers class com.lokashare.** {
    @com.google.firebase.firestore.PropertyName <fields>;
}

# ─── Room — entity & DAO tidak boleh di-strip ────────────────
-keep class com.lokashare.** extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ─── Kotlinx Serialization ───────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lokashare.**$$serializer { *; }
-keepclassmembers class com.lokashare.** {
    *** Companion;
}
-keepclasseswithmembers class com.lokashare.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── WorkManager ─────────────────────────────────────────────
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ─── Coroutines ──────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ─── Jetpack Compose ─────────────────────────────────────────
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ─── Timber — strip log calls di release ─────────────────────
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

# ─── General ─────────────────────────────────────────────────
# Pertahankan stack trace agar crash report masih terbaca
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Hindari warning dari library pihak ketiga
-dontwarn okhttp3.**
-dontwarn okio.**
