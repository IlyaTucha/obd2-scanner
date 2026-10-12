# Compose / Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$$serializer { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# Yandex MapKit: нативный код обращается к Java-классам через JNI
-keep class com.yandex.** { *; }
-dontwarn com.yandex.**

# kotlinx.serialization: сериализаторы @Serializable-классов
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.obd2monitor.**$$serializer { *; }
-keepclassmembers class com.obd2monitor.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
