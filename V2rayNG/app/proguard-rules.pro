# SkyRay (EthaVPN): R8 shrinks and obfuscates the release build. Everything reached by name stays:
# gson models (field names are the JSON keys), the Go core and the tunnel (JNI binds by class and method name),
# MMKV, the services and workers, enums whose names are stored. Line numbers stay for readable crash reports;
# the mapping file is published with every release.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,EnclosingMethod,InnerClasses
-renamesourcefileattribute SourceFile

# gson: every DTO and entity, generic signatures, TypeToken
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.google.gson.** { *; }
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }

# enums stored or matched by name
-keep enum com.v2ray.ang.** { *; }

# the Go core (gomobile), the hev tunnel, MMKV: JNI
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class com.tencent.mmkv.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# services, receivers, workers: referenced from the manifest and by class name
-keep class com.v2ray.ang.service.** { *; }
-keep class com.v2ray.ang.receiver.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class com.v2ray.ang.AngApplication { *; }

-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
