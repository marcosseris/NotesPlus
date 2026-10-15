# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.notesplus.app.**$$serializer { *; }
-keepclassmembers class com.notesplus.app.** { *** Companion; }
-keepclasseswithmembers class com.notesplus.app.** { kotlinx.serialization.KSerializer serializer(...); }

# PdfBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
