# kotlinx.serialization: keep generated serializers for @Serializable classes (routes + API models)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.thebridge.** { *** Companion; }
-keepclasseswithmembers class app.thebridge.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.thebridge.**$$serializer { *; }
# Google ID token credential is created reflectively from a Bundle
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Encrypted DMs: UniFFI bindings call the Rust library through JNA, which uses reflection
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.bridge_crypto.** { *; }
-dontwarn java.awt.**
