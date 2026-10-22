# Add project specific ProGuard rules here.

# Keep Room entities
-keep class com.v1per.synthx.data.db.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-dontwarn androidx.compose.**

# WorkManager creates InputMergers (e.g. OverwritingInputMerger) via reflection —
# a bare "-keep class" only preserves the class, not its constructor, under R8 full mode.
# The no-arg constructor must be explicitly kept or Class.forName().getDeclaredConstructor()
# fails at runtime even though the class itself is present.
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
-keep class androidx.work.impl.** { *; }
