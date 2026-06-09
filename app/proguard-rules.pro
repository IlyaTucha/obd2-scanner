# Compose / Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$$serializer { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
