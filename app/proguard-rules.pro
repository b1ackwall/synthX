# Add project specific ProGuard rules here.

# Keep Room entities
-keep class com.v1per.synthx.data.db.** { *; }

# Keep data classes used in serialization
-keep class com.v1per.synthx.data.repository.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-dontwarn androidx.compose.**
